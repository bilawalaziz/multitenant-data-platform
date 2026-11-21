#!/usr/bin/env bash
# Runs the CI jobs from .github/workflows/ci.yml locally, each in a pinned Docker image.
# Only Docker is required on the host.
#
#   scripts/ci-local.sh            # all jobs
#   scripts/ci-local.sh build      # one job: lint | build | consumer | docker | workflows
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
  all)
    run workflows workflows
    run lint lint
    run consumer consumer
    run build build
    run docker docker_job
    ;;
  *)
    echo "usage: $0 [all|lint|build|consumer|docker|workflows]" >&2
    exit 2
    ;;
esac
