#!/usr/bin/env bash
# Local AWS-style run: the AWS Emulator hosts the Secrets Manager/KMS/ECR/... resources created by Terraform, a
# k3d cluster stands in for EKS, and helmfile installs the platform (deploy/helm, deploy/helmfile.yaml.gotmpl).
# Only Docker (and Python for the demo) is needed on the host; every tool runs in a pinned container.
#
#   scripts/aws-local.sh up       # emulator, terraform, k3d, images, helmfile, wait until every pod is Ready
#   scripts/aws-local.sh deploy   # rebuild and push the images, helmfile apply, wait
#   scripts/aws-local.sh status   # pods, ingress, terraform outputs
#   scripts/aws-local.sh demo     # python scripts/demo.py all --target k8s
#   scripts/aws-local.sh down     # remove everything `up` created (logs under .scratch/aws-local/ are kept)
#
# The compose stack (project sensor-platform) and this run do not fit into the Docker memory limit together: stop
# compose first (docker compose -p sensor-platform stop). This script never touches the compose project.
set -euo pipefail

cd "$(dirname "$0")/.."

# Git Bash on Windows rewrites POSIX paths passed to docker; disable that and use the Windows path for mounts.
export MSYS_NO_PATHCONV=1
if pwd -W >/dev/null 2>&1; then
  REPO="$(pwd -W)"
  SOCK=//var/run/docker.sock
else
  REPO="$(pwd)"
  SOCK=/var/run/docker.sock
fi

# ---- pins (same as deploy/spike, deploy/terraform and deploy/helm) -------------------------------------------------
EMU_IMAGE=sensor-spike/aws-emulator:1.0.556 # built from deploy/spike/emulator (no published image)
TF_IMAGE=hashicorp/terraform:1.17.2
K3D_IMAGE=ghcr.io/k3d-io/k3d:5.9.0
K3S_IMAGE=rancher/k3s:v1.33.5-k3s1
KUBECTL_IMAGE=bitnamilegacy/kubectl:1.33.4
HELMFILE_IMAGE=ghcr.io/helmfile/helmfile:v1.1.9

# ---- names ---------------------------------------------------------------------------------------------------------
COMPOSE_PROJECT=sensor-platform
NET=sensor-local-net
EMU=sensor-local-emulator
CLUSTER=sensor-local
REGISTRY=k3d-sensor-registry # the name D3 values expect; k3d uses the configured name as is
REGISTRY_HOST_PORT=5001
KUBECTL_HELPER=sensor-local-kubectl
APP_IMAGE=sensor-platform/app
CONSUMER_IMAGE=sensor-platform/consumer

# ---- paths (relative to the repository root) ----------------------------------------------------------------------
STATE=.scratch/aws-local
EMU_HOME=$STATE/emu-home
CA_FILE=$EMU_HOME/9297/ca.pem
KUBECONFIG_RAW=$STATE/kubeconfig.raw
KUBECONFIG_FILE=$STATE/kubeconfig
TF_DIR=deploy/terraform/envs/local
TF_STATE=.scratch/tf/local # backend path in $TF_DIR/versions.tf

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# ---- tools: one pinned container per call --------------------------------------------------------------------------
# A timeout that kills the docker client leaves the container running, so it is always removed afterwards.
run_tool() { # run_tool <name> <timeout-seconds> <docker run args...>
  local name=$1 limit=$2 rc=0
  shift 2
  timeout "$limit" docker run --rm --name "$name" "$@" || rc=$?
  docker rm -f "$name" >/dev/null 2>&1 || true
  return "$rc"
}

# Terraform shares the emulator's network namespace: its certificate only names 127.0.0.1 (deploy/spike/README.md).
tf() { # tf <timeout> <terraform args...>
  local limit=$1
  shift
  run_tool sensor-local-tf "$limit" --network "container:$EMU" -v "$REPO:/repo" -w "/repo/$TF_DIR" \
    -v "$REPO/$EMU_HOME/9297:/ca:ro" -v "$REPO/$STATE/tf-cache:/tfcache" \
    -e TF_DATA_DIR=/repo/$STATE/tf-data -e TF_PLUGIN_CACHE_DIR=/tfcache -e AWS_CA_BUNDLE=/ca/ca.pem \
    -e TF_IN_AUTOMATION=1 "$TF_IMAGE" "$@"
}

k3d() {
  run_tool sensor-local-k3d 600 --network "$NET" -v "$SOCK:/var/run/docker.sock" -v "$REPO/$STATE:/cfg:ro" \
    "$K3D_IMAGE" "$@"
}

hf() { # helmfile reaches the k3d API through the load balancer name, so it runs on the shared network
  run_tool sensor-local-helmfile 1500 --network "$NET" -v "$REPO:/repo" -w /repo/deploy \
    -e KUBECONFIG="/repo/$KUBECONFIG_FILE" -e HELM_CACHE_HOME="/repo/$STATE/helm-cache" \
    "$HELMFILE_IMAGE" helmfile "$@"
}

# kubectl goes through one long-running container: a `docker run` per call is too slow for the demo's polling.
ensure_kubectl() {
  if [ "$(docker inspect -f '{{.State.Running}}' "$KUBECTL_HELPER" 2>/dev/null || true)" != true ]; then
    docker rm -f "$KUBECTL_HELPER" >/dev/null 2>&1 || true
    docker run -d --name "$KUBECTL_HELPER" --network "$NET" -v "$REPO/$STATE:/kc:ro" -e KUBECONFIG=/kc/kubeconfig \
      --label sensor-local=1 --entrypoint sleep "$KUBECTL_IMAGE" infinity >/dev/null
  fi
}
kc() {
  ensure_kubectl
  docker exec -i "$KUBECTL_HELPER" kubectl "$@"
}

# ---- up ------------------------------------------------------------------------------------------------------------
preflight() {
  log "pre-flight: compose project $COMPOSE_PROJECT must not be running"
  if [ -n "$(docker ps -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT")" ]; then
    {
      echo "The compose project '$COMPOSE_PROJECT' has running containers; the Docker memory limit cannot hold both."
      echo "Stop it first (this keeps its volumes):"
      echo "  docker compose -p $COMPOSE_PROJECT stop"
    } >&2
    exit 1
  fi
}

start_emulator() {
  log "emulator"
  docker image inspect "$EMU_IMAGE" >/dev/null 2>&1 || docker build -t "$EMU_IMAGE" deploy/spike/emulator
  docker network inspect "$NET" >/dev/null 2>&1 || docker network create --label sensor-local=1 "$NET" >/dev/null
  mkdir -p "$EMU_HOME" "$STATE/tf-cache" "$STATE/tf-data" "$STATE/helm-cache" .scratch/tf/local
  if [ "$(docker inspect -f '{{.State.Running}}' "$EMU" 2>/dev/null || true)" != true ]; then
    docker rm -f "$EMU" >/dev/null 2>&1 || true
    docker run -d --name "$EMU" --network "$NET" --label sensor-local=1 --memory=2g --memory-swap=2g \
      -p 127.0.0.1:9297:9297 -v "$REPO/$EMU_HOME:/home/emulator/.aws/emulator" \
      "$EMU_IMAGE" -p 9297 --telemetry off --auth off >/dev/null
  fi
  for _ in $(seq 1 60); do
    if [ "$(curl -sk --max-time 3 https://127.0.0.1:9297/__admin/health || true)" = ok ] && [ -f "$CA_FILE" ]; then
      docker logs "$EMU" 2>&1 | grep -i telemetry | head -1 || true
      return 0
    fi
    sleep 2
  done
  docker logs --tail 40 "$EMU" >&2 || true
  die "the emulator did not become healthy"
}

apply_terraform() {
  log "terraform apply (envs/local) in the emulator's network namespace"
  tf 300 init -input=false >"$STATE/tf-init.log" 2>&1 || { tail -30 "$STATE/tf-init.log" >&2; die "terraform init failed"; }
  tf 900 apply -auto-approve -input=false >"$STATE/tf-apply.log" 2>&1 || { tail -40 "$STATE/tf-apply.log" >&2; die "terraform apply failed"; }
  tail -3 "$STATE/tf-apply.log"
  log "terraform second plan must show no drift"
  local rc=0
  tf 600 plan -detailed-exitcode -input=false >"$STATE/tf-plan2.log" 2>&1 || rc=$?
  if [ "$rc" -ne 0 ]; then
    tail -40 "$STATE/tf-plan2.log" >&2
    die "terraform plan after apply exited $rc (2 = drift)"
  fi
  tf 300 output -json >"$STATE/tf-outputs.json"
}

# One server and no agent: a second node would only add a kubelet and containerd (~300 MB) to a 7.6 GB budget that
# also holds the emulator, Keycloak, Kafka and the observability stack; the demo needs no scheduling across nodes.
create_cluster() {
  log "k3d cluster $CLUSTER"
  if k3d cluster list 2>/dev/null | grep -q "^$CLUSTER "; then
    log "cluster exists, reusing it"
  else
    cat >"$STATE/k3d.yaml" <<EOF
apiVersion: k3d.io/v1alpha5
kind: Simple
metadata:
  name: $CLUSTER
servers: 1
agents: 0
image: $K3S_IMAGE
network: $NET
kubeAPI:
  host: 127.0.0.1
  hostIP: 127.0.0.1
  hostPort: "6550"
registries:
  create:
    name: $REGISTRY
    hostPort: "$REGISTRY_HOST_PORT"
ports:
  # Traefik (k3s default) answers on the service load balancer; the *.localtest.me names resolve to 127.0.0.1.
  - port: 127.0.0.1:80:80
    nodeFilters: [loadbalancer]
options:
  k3d:
    wait: true
    timeout: 240s
  k3s:
    extraArgs:
      # Traefik and servicelb stay on: the D3 ingress needs them. Metrics server is not used.
      - arg: --disable=metrics-server
        nodeFilters: [server:*]
EOF
    k3d cluster create --config /cfg/k3d.yaml >"$STATE/k3d-create.log" 2>&1 || { tail -30 "$STATE/k3d-create.log" >&2; die "k3d cluster create failed"; }
  fi
  k3d kubeconfig get "$CLUSTER" >"$KUBECONFIG_RAW"
  # The host uses 127.0.0.1:6550; tool containers on the shared network use the load balancer by name.
  sed "s#https://127.0.0.1:6550#https://k3d-$CLUSTER-serverlb:6443#" "$KUBECONFIG_RAW" >"$KUBECONFIG_FILE"
  ensure_kubectl
  kc get nodes
}

build_images() {
  IMAGE_TAG="local-$(date +%Y%m%d%H%M%S)"
  # Pushing through the published registry port keeps the image reference the pods use (k3d-sensor-registry:5000),
  # which k3s resolves through the mirror k3d writes; `k3d image import` would need the tag set in the chart values.
  log "build and push images ($IMAGE_TAG)"
  docker build -q -t "$APP_IMAGE:$IMAGE_TAG" . >"$STATE/build-app.log" 2>&1 || { tail -30 "$STATE/build-app.log" >&2; die "app image build failed"; }
  docker build -q -t "$CONSUMER_IMAGE:$IMAGE_TAG" consumer >"$STATE/build-consumer.log" 2>&1 || { tail -30 "$STATE/build-consumer.log" >&2; die "consumer image build failed"; }
  local img
  for img in "$APP_IMAGE" "$CONSUMER_IMAGE"; do
    docker tag "$img:$IMAGE_TAG" "localhost:$REGISTRY_HOST_PORT/$img:$IMAGE_TAG"
    docker push -q "localhost:$REGISTRY_HOST_PORT/$img:$IMAGE_TAG" >/dev/null
  done
}

apply_helmfile() {
  log "helmfile -e local apply (log: $STATE/helmfile-apply.log)"
  hf -e local apply --skip-diff-on-install --concurrency 3 --state-values-set "imageTag=$IMAGE_TAG" \
    >"$STATE/helmfile-apply.log" 2>&1 || { tail -60 "$STATE/helmfile-apply.log" >&2; diagnose; die "helmfile apply failed"; }
  tail -5 "$STATE/helmfile-apply.log"
}

diagnose() {
  echo "---- kubectl get pods -A" >&2
  kc get pods -A -o wide >&2 || true
  local ns pod
  kc get pods -A --no-headers 2>/dev/null | awk '$4 !~ /^(Running|Completed)$/ || $3 !~ /^([0-9]+)\/\1$/ {print $1, $2}' |
    while read -r ns pod; do
      echo "---- describe $ns/$pod" >&2
      kc -n "$ns" describe pod "$pod" 2>&1 | tail -40 >&2 || true
      echo "---- logs $ns/$pod" >&2
      kc -n "$ns" logs "$pod" --all-containers --tail=40 >&2 || true
    done
}

wait_ready() {
  log "wait until every pod is Ready (timeout 600s)"
  # Finished Job pods (db-bootstrap, k3s helm installers) are Succeeded and never Ready.
  if ! kc wait --for=condition=Ready pod --all -A --field-selector=status.phase!=Succeeded --timeout=600s; then
    diagnose
    die "pods did not become Ready"
  fi
  kc get pods -A
}

cmd_up() {
  local t0=$SECONDS
  mkdir -p "$STATE"
  preflight
  start_emulator
  apply_terraform
  create_cluster
  build_images
  apply_helmfile
  wait_ready
  log "up finished in $((SECONDS - t0)) s"
  echo "  app       http://sensor.localtest.me   keycloak http://keycloak.localtest.me   grafana http://grafana.localtest.me"
}

cmd_deploy() {
  local t0=$SECONDS
  [ -f "$KUBECONFIG_FILE" ] || die "no cluster: run 'up' first"
  ensure_kubectl
  build_images
  apply_helmfile
  wait_ready
  log "deploy finished in $((SECONDS - t0)) s"
}

cmd_status() {
  [ -f "$KUBECONFIG_FILE" ] || die "no cluster: run 'up' first"
  kc get pods -A
  kc get ingress -A
  kc get externalsecret -A
  echo "---- terraform outputs"
  tf 300 output -json 2>/dev/null | docker run --rm -i python:3.12.8-slim python -c \
    'import json,sys; d=json.load(sys.stdin); [print("%-22s %s" % (k, json.dumps(v["value"])[:150])) for k,v in sorted(d.items())]' ||
    echo "(terraform outputs unavailable)"
}

cmd_demo() {
  local py=python
  command -v python >/dev/null 2>&1 || py=python3
  "$py" scripts/demo.py all --target k8s "$@"
}

# ---- down: only what `up` created, by exact name --------------------------------------------------------------------
cmd_down() {
  local t0=$SECONDS
  log "down"
  if docker inspect "k3d-$CLUSTER-server-0" >/dev/null 2>&1; then
    k3d cluster delete "$CLUSTER" || true
  fi
  # k3d leaves the registry behind.
  docker rm -f "$REGISTRY" "$KUBECTL_HELPER" "$EMU" sensor-local-tf sensor-local-k3d sensor-local-helmfile >/dev/null 2>&1 || true
  if docker network inspect "$NET" >/dev/null 2>&1; then
    docker network rm "$NET" >/dev/null || true
  fi
  # The state describes resources of the emulator that is now gone.
  rm -f "$TF_STATE/terraform.tfstate" "$TF_STATE/terraform.tfstate.backup" "$TF_STATE/.terraform.tfstate.lock.info"
  rm -rf "$STATE/tf-data" "$EMU_HOME" "$KUBECONFIG_RAW" "$KUBECONFIG_FILE" "$STATE/k3d.yaml" "$STATE/tf-outputs.json"
  log "down finished in $((SECONDS - t0)) s (logs and tool caches under $STATE are kept)"
}

case "${1:-}" in
  up) cmd_up ;;
  deploy) cmd_deploy ;;
  status) cmd_status ;;
  demo) shift; cmd_demo "$@" ;;
  down) cmd_down ;;
  *)
    echo "usage: $0 up|deploy|status|demo|down" >&2
    exit 2
    ;;
esac
