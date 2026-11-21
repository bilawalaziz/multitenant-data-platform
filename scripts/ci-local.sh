#!/usr/bin/env bash
# Runs the CI jobs from .github/workflows/ci.yml locally, each in a pinned Docker image.
# Only Docker is required on the host.
#
#   scripts/ci-local.sh            # all jobs
#   scripts/ci-local.sh build      # one job: lint | build | consumer | docker | workflows | infra
set -euo pipefail

cd "$(dirname "$0")/.."

# Git Bash on Windows rewrites POSIX paths passed to docker; disable that and use the Windows path for mounts.
export MSYS_NO_PATHCONV=1
if pwd -W >/dev/null 2>&1; then
  REPO="$(pwd -W)"
else
  REPO="$(pwd)"
fi

JDK_IMAGE=eclipse-temurin:17.0.13_11-jdk
PYTHON_IMAGE=python:3.12.8
PYTHON_SLIM_IMAGE=python:3.12.8-slim
TRIVY_IMAGE=aquasec/trivy:0.70.0
ACTIONLINT_IMAGE=rhysd/actionlint:1.7.4
PROMETHEUS_IMAGE=prom/prometheus:v3.5.0
TERRAFORM_IMAGE=hashicorp/terraform:1.17.2
TFLINT_IMAGE=ghcr.io/terraform-linters/tflint:v0.66.0
HELM_IMAGE=alpine/helm:3.19.0
KUBECONFORM_IMAGE=ghcr.io/yannh/kubeconform:v0.7.0-alpine
SHELLCHECK_IMAGE=koalaman/shellcheck:v0.10.0

# On Docker Desktop, containers started by Testcontainers are reached through host.docker.internal.
TC_ENV=()
if docker info --format '{{.OperatingSystem}}' 2>/dev/null | grep -q 'Docker Desktop'; then
  TC_ENV=(-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal)
fi

lint() {
  docker run --rm -v "$REPO":/repo -w /repo -v ci-local-pre-commit:/root/.cache/pre-commit \
    -e SKIP=spotless "$PYTHON_IMAGE" bash -c '
      git config --global --add safe.directory /repo &&
      pip install -q pre-commit==4.0.1 &&
      pre-commit run --all-files --show-diff-on-failure'
}

build() {
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$REPO":/workspace -w /workspace \
    -v ci-local-m2:/root/.m2 "${TC_ENV[@]}" "$JDK_IMAGE" bash -c '
      printf "api.version=1.44\n" > ~/.docker-java.properties &&
      ./mvnw -B -ntp verify'
}

consumer() {
  docker run --rm -v "$REPO":/repo -w /repo "$PYTHON_SLIM_IMAGE" bash -c '
    pip install -q --no-cache-dir --root-user-action=ignore -r consumer/requirements.txt &&
    python -m unittest discover -s consumer -v &&
    python -m py_compile scripts/demo.py'
}

docker_job() {
  docker compose --env-file .env.example config -q
  docker build -t sensor-ingestion-platform:ci .
  docker build -t sensor-consumer:ci consumer
  local trivy=(docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$REPO":/repo
    -v ci-local-trivy:/root/.cache "$TRIVY_IMAGE" image --quiet --ignore-unfixed)
  echo "Fixable critical findings (fails the job):"
  "${trivy[@]}" --severity CRITICAL --ignorefile /repo/.trivyignore --exit-code 1 sensor-ingestion-platform:ci
  echo "Fixable critical and high findings (report only):"
  "${trivy[@]}" --severity CRITICAL,HIGH --exit-code 0 sensor-ingestion-platform:ci
  echo "Prometheus config and alert rules:"
  local promtool=(docker run --rm --entrypoint promtool -v "$REPO/infra/prometheus":/etc/prometheus:ro "$PROMETHEUS_IMAGE")
  "${promtool[@]}" check config /etc/prometheus/prometheus.yml
  "${promtool[@]}" check rules /etc/prometheus/alerts.yml
  "${promtool[@]}" test rules /etc/prometheus/alerts.test.yml
}

workflows() {
  docker run --rm -v "$REPO":/repo -w /repo "$ACTIONLINT_IMAGE" \
    .github/workflows/ci.yml .github/workflows/codeql.yml
}

# Terraform (both envs), Helm charts and the local runner script: format, validate, lint, schema and misconfiguration
# checks. Nothing here talks to AWS or to a cluster; the aws env is initialised without a backend.
infra() {
  local tf=(docker run --rm -v "$REPO":/repo -v ci-local-tf-plugins:/tfcache -e TF_PLUGIN_CACHE_DIR=/tfcache
    -e TF_IN_AUTOMATION=1)
  echo "terraform fmt:"
  "${tf[@]}" -w /repo/deploy/terraform "$TERRAFORM_IMAGE" fmt -check -recursive
  local env
  for env in aws local; do
    echo "terraform validate envs/$env:"
    "${tf[@]}" -w "/repo/deploy/terraform/envs/$env" -e TF_DATA_DIR="/repo/target/infra-ci/tf-$env" \
      "$TERRAFORM_IMAGE" init -backend=false -input=false >/dev/null
    "${tf[@]}" -w "/repo/deploy/terraform/envs/$env" -e TF_DATA_DIR="/repo/target/infra-ci/tf-$env" \
      "$TERRAFORM_IMAGE" validate
  done

  echo "tflint:"
  local tflint=(docker run --rm -v "$REPO":/repo -w /repo/deploy/terraform -v ci-local-tflint:/tflint
    -e TFLINT_PLUGIN_DIR=/tflint "$TFLINT_IMAGE")
  "${tflint[@]}" --init >/dev/null
  "${tflint[@]}" --recursive --config=/repo/deploy/terraform/.tflint.hcl

  echo "helm lint and render (our charts, both envs):"
  docker run --rm -v "$REPO":/repo -w /repo/deploy/helm --entrypoint sh "$HELM_IMAGE" -c '
    set -e
    out=/repo/target/infra-ci/rendered
    rm -rf "$out" && mkdir -p "$out"
    realm=/repo/infra/keycloak/sensor-platform-realm.json
    for e in local aws; do
      helm lint --quiet sensor-platform -f sensor-platform/values-$e.yaml
      helm template sensor-platform sensor-platform -n sensor-platform -f sensor-platform/values-$e.yaml \
        > $out/sensor-platform-$e.yaml
      helm lint --quiet keycloak -f keycloak/values-$e.yaml --set-file realm.json=$realm
      helm template keycloak keycloak -n sensor-platform -f keycloak/values-$e.yaml --set-file realm.json=$realm \
        > $out/keycloak-$e.yaml
    done
    helm lint --quiet local-data
    helm template local-data local-data -n sensor-platform > $out/local-data-local.yaml
    # The emulator CA only exists while the local stack runs; a placeholder is enough to render.
    eso_local="--set store.enabled=true --set store.staticCredentials.enabled=true --set caConfigMap.enabled=true"
    helm lint --quiet eso-config $eso_local --set caConfigMap.caPem=placeholder
    helm template eso-config eso-config $eso_local --set caConfigMap.caPem=placeholder > $out/eso-config-local.yaml
    helm template eso-config eso-config --set store.enabled=true > $out/eso-config-aws.yaml'

  echo "kubeconform (CRDs from the datreeio catalog):"
  docker run --rm -v "$REPO":/repo "$KUBECONFORM_IMAGE" -strict -summary -kubernetes-version 1.33.0 \
    -schema-location default \
    -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
    /repo/target/infra-ci/rendered

  echo "trivy config (HIGH/CRITICAL fail the job):"
  local trivy=(docker run --rm -v "$REPO":/repo -v ci-local-trivy:/root/.cache "$TRIVY_IMAGE" config --quiet
    --severity "HIGH,CRITICAL" --exit-code 1)
  "${trivy[@]}" --tf-exclude-downloaded-modules /repo/deploy/terraform
  # Without --helm-kube-version trivy renders for Kubernetes 1.20, which the charts refuse, and scans nothing.
  "${trivy[@]}" --helm-kube-version 1.33.0 --ignorefile /repo/deploy/helm/.trivyignore.yaml /repo/deploy/helm
  "${trivy[@]}" --ignorefile /repo/deploy/helm/.trivyignore.yaml /repo/target/infra-ci/rendered

  echo "shellcheck:"
  docker run --rm -v "$REPO":/repo -w /repo "$SHELLCHECK_IMAGE" scripts/aws-local.sh scripts/ci-local.sh
}

run() {
  echo "==> $1"
  "$2"
  echo "==> $1 passed"
}

case "${1:-all}" in
  lint) run lint lint ;;
  build) run build build ;;
  consumer) run consumer consumer ;;
  docker) run docker docker_job ;;
  workflows) run workflows workflows ;;
  infra) run infra infra ;;
  all)
    run workflows workflows
    run lint lint
    run consumer consumer
    run build build
    run docker docker_job
    run infra infra
    ;;
  *)
    echo "usage: $0 [all|lint|build|consumer|docker|workflows|infra]" >&2
    exit 2
    ;;
esac
