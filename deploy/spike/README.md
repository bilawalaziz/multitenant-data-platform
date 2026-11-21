# AWS emulator + k3d spike (D1)

Feasibility spike: AWS Emulator 1.0.556 for AWS API resources, k3d for EKS,
External Secrets Operator for secrets. Dummy credentials only. State, logs and
kubeconfig go in `.scratch/spike/` (git-ignored). Run from the repo root in Git
Bash; every docker call needs `MSYS_NO_PATHCONV=1`.

## Results

| # | Item | Works | Note |
|---|------|-------|------|
| 1 | Emulator in Docker, telemetry off | yes | no published image: build from vendor Dockerfile; cert SANs are localhost/127.0.0.1/::1 only |
| 2 | Terraform VPC, 2 subnets, SG + rule, IAM role/policy, KMS key + alias, secret + version, ECR repo | yes | needs `--auth off` for static `test`/`test`; 13 created, second plan no-op, destroy clean |
| 3 | k3d 1 server + registry, kubectl, pod reaches emulator | yes | emulator and cluster share one Docker network; pods resolve the emulator by container name |
| 4 | ESO `SecretStore` + `ExternalSecret` from emulator | yes | needs a socat sidecar so the endpoint is `127.0.0.1` (cert name check) |

## Pinned versions

- emulator RPM 1.0.556 (sha256 in `emulator/Dockerfile`), base `amazonlinux:2023-minimal`
- `hashicorp/terraform:1.17.2`, provider `hashicorp/aws` 6.73.0
- `ghcr.io/k3d-io/k3d:5.9.0`, k3s `rancher/k3s:v1.33.5-k3s1`
- `bitnamilegacy/kubectl:1.33.4` (versioned `bitnami/kubectl` tags no longer exist), `alpine/helm:3.19.0`
- external-secrets chart/app 2.15.0, `alpine/socat:1.8.0.1`, `curlimages/curl:8.10.1`

## 1. Emulator

```bash
export MSYS_NO_PATHCONV=1
docker build -t sensor-spike/aws-emulator:1.0.556 deploy/spike/emulator
docker network create sensor-spike-net
mkdir -p .scratch/spike/emu-home
docker run -d --name sensor-spike-emulator --network sensor-spike-net \
  --memory=2g --memory-swap=2g -p 127.0.0.1:9297:9297 \
  -v "$(pwd -W)/.scratch/spike/emu-home:/home/emulator/.aws/emulator" \
  sensor-spike/aws-emulator:1.0.556 -p 9297 --telemetry off --auth off
docker logs sensor-spike-emulator 2>&1 | grep -i telemetry   # "... telemetry: disabled ..."
```

Endpoints: host `https://127.0.0.1:9297`; containers and pods
`https://sensor-spike-emulator:9297`. CA: `.scratch/spike/emu-home/9297/ca.pem`.
Health: `/__admin/health` returns `ok`.

TLS: the certificate names only `localhost`, `127.0.0.1` and `::1`. Clients
that use the container name fail verification. Either run the client in the
emulator's network namespace (`--network container:sensor-spike-emulator`, as
Terraform does here), use `-k`/`insecure = true`, or tunnel to a local
127.0.0.1 port (ESO sidecar).

## 2. Terraform

```bash
export MSYS_NO_PATHCONV=1
tf() { docker run --rm --network container:sensor-spike-emulator \
  -v "$(pwd -W)/deploy/spike/terraform:/work" \
  -v "$(pwd -W)/.scratch/spike/emu-home/9297:/ca:ro" \
  -v "$(pwd -W)/.scratch/spike/tf-data:/tfdata" -v "$(pwd -W)/.scratch/spike/tf-cache:/tfcache" \
  -w /work -e TF_DATA_DIR=/tfdata -e TF_PLUGIN_CACHE_DIR=/tfcache -e AWS_CA_BUNDLE=/ca/ca.pem \
  hashicorp/terraform:1.17.2 "$@"; }
tf init && tf apply -auto-approve && tf plan -detailed-exitcode && tf destroy -auto-approve
```

## 3. k3d

```bash
export MSYS_NO_PATHCONV=1
k3d() { docker run --rm --network sensor-spike-net -v //var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd -W)/deploy/spike/k3d:/cfg:ro" ghcr.io/k3d-io/k3d:5.9.0 "$@"; }
k3d cluster create --config /cfg/k3d.yaml
k3d kubeconfig get sensor-spike > .scratch/spike/kubeconfig.raw
# host uses https://127.0.0.1:6550; containers on the network use the load balancer by name
sed 's#https://127.0.0.1:6550#https://k3d-sensor-spike-serverlb:6443#' \
  .scratch/spike/kubeconfig.raw > .scratch/spike/kubeconfig
kc() { docker run --rm -i --network sensor-spike-net \
  -v "$(pwd -W)/.scratch/spike/kubeconfig:/kc:ro" -v "$(pwd -W)/deploy/spike:/spike:ro" \
  -e KUBECONFIG=/kc bitnamilegacy/kubectl:1.33.4 "$@"; }
kc get nodes
kc run curl-spike --image=curlimages/curl:8.10.1 --restart=Never --command -- sleep 600
kc exec curl-spike -- curl -sk https://sensor-spike-emulator:9297/__admin/health   # ok
```

## 4. External Secrets Operator

Re-run the Terraform apply first so the secret exists.

```bash
# CA ConfigMap from the emulator CA (the kubectl image cannot read the 0600 file)
{ printf 'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: emulator-ca\n  namespace: external-secrets\ndata:\n  ca.pem: |\n'; \
  sed 's/^/    /' .scratch/spike/emu-home/9297/ca.pem; } > .scratch/spike/emulator-ca-cm.yaml
kc create namespace external-secrets
kc apply -f - < .scratch/spike/emulator-ca-cm.yaml
helm() { docker run --rm -i --network sensor-spike-net \
  -v "$(pwd -W)/.scratch/spike/kubeconfig:/kc:ro" -v "$(pwd -W)/deploy/spike:/spike:ro" \
  -v "$(pwd -W)/.scratch/spike/helm:/h" -e KUBECONFIG=/kc \
  -e HELM_CACHE_HOME=/h/cache -e HELM_CONFIG_HOME=/h/config -e HELM_DATA_HOME=/h/data \
  alpine/helm:3.19.0 "$@"; }
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets --version 2.15.0 \
  -n external-secrets -f /spike/eso/eso-values.yaml --wait
kc apply -f /spike/eso/secretstore.yaml -f /spike/eso/externalsecret.yaml
kc get secretstore,externalsecret   # Valid / SecretSynced
```

## Cleanup

```bash
k3d cluster delete sensor-spike
docker rm -f sensor-spike-registry sensor-spike-emulator
docker network rm sensor-spike-net
```
