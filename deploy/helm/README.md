# Helm charts and Helmfile

Charts in this directory, releases in `../helmfile.yaml.gotmpl` (named `.gotmpl` because Helmfile v1 renders Go
templates only for that extension). Environments: `local` (k3d + the AWS emulator) and `aws` (EKS, never applied from
here). Nothing in this tree is installed by a build or a test.

| Chart | Purpose |
|-------|---------|
| `sensor-platform` | The application: API Deployment, Service, Ingress, HPA, PDB; the consumer Deployment; ConfigMaps; ExternalSecrets; the `db-bootstrap` hook; NetworkPolicies |
| `local-data` | Local only. PostgreSQL 17, Redis 7.4 (AUTH) and a single-broker Kafka 3.9 (KRaft, SASL_PLAINTEXT, SCRAM-SHA-512) standing in for RDS, ElastiCache and MSK |
| `keycloak` | Keycloak 26.8.2 in production mode with the realm import |
| `eso-config` | The `ClusterSecretStore` (both envs) and, locally, the emulator CA ConfigMap |

Third-party releases (versions pinned in the helmfile): external-secrets 2.15.0, opentelemetry-collector 0.181.0,
tempo (grafana-community) 2.4.0, prometheus 29.48.0, grafana (grafana-community) 14.5.0,
aws-load-balancer-controller 3.6.3 (aws only). Their values are in `../values/`; the alert rules and the dashboard are
read from `infra/` by the template, so compose and the cluster share one copy.

## Values per environment

| | `local` | `aws` |
|---|---|---|
| Images | `image.registry` = `k3d-sensor-registry:5000` (one value, also `registry` in `environments/local.yaml`) | `registry` in `environments/aws.yaml`: the ECR registry host; repositories `sensor-platform/app` and `sensor-platform/consumer` |
| Ingress | Traefik, `sensor.localtest.me`, `keycloak.localtest.me`, `grafana.localtest.me` | ALB (`className: alb`), ACM certificate ARN annotation |
| Spring profile | `docker` | `prod` (strict `ProdRequiredSettings`) |
| PostgreSQL | `local-data` | RDS, `sslmode=require` in `DB_URL` |
| Redis | `local-data`, AUTH, no TLS (`REDIS_SSL=false`) | ElastiCache, AUTH, `REDIS_SSL=true` |
| Kafka | `local-data`, `SASL_PLAINTEXT` + SCRAM-SHA-512 on 9092, replication factor 1 | MSK, `SASL_SSL` + SCRAM-SHA-512 on 9096, replication factor 3 |
| Secrets | External Secrets, ClusterSecretStore on the emulator (D1 overlay) | External Secrets, ClusterSecretStore with Pod Identity (no endpoint overrides) |
| Tempo | local storage | S3 bucket, service account `observability/tempo` |
| Replicas | app 1, consumer 2 | app 3 + HPA 3-9, consumer 3, PDB |

`values-aws.yaml`, `environments/aws.yaml` and `keycloak/values-aws.yaml` contain `REPLACE_*` placeholders for the
Terraform outputs (`rds_address`, `redis_primary_endpoint`, `msk_bootstrap_brokers_sasl_scram`,
`rds_master_secret_arn`, `acm_certificate_arn`, `security_group_ids`, `ecr_repository_urls`, `tempo_bucket_name`).
The service-account names in the values match `deploy/terraform` (Pod Identity): `external-secrets/external-secrets`,
`kube-system/aws-load-balancer-controller`, `observability/tempo`. The workloads in `sensor-platform` have no AWS role.

JDBC TLS needs no application setting: it is part of `DB_URL` (`...?sslmode=require`), which the chart builds from
`config.db.sslmode`.

## Render and lint

Everything runs in pinned containers (`alpine/helm:3.19.0`, `ghcr.io/helmfile/helmfile:v1.1.9`,
`ghcr.io/yannh/kubeconform:v0.7.0-alpine`, `aquasec/trivy:0.70.0`); from the repository root in Git Bash:

```bash
export MSYS_NO_PATHCONV=1
# lint (the keycloak chart takes the realm file as a value)
docker run --rm -v "$(pwd -W)":/repo -w /repo/deploy/helm alpine/helm:3.19.0 lint sensor-platform -f sensor-platform/values-local.yaml
docker run --rm -v "$(pwd -W)":/repo -w /repo/deploy/helm alpine/helm:3.19.0 lint keycloak -f keycloak/values-aws.yaml --set-file realm.json=/repo/infra/keycloak/sensor-platform-realm.json
# the whole environment, with the third-party charts (needs network for the chart repositories)
docker run --rm -v "$(pwd -W)":/repo -w /repo/deploy ghcr.io/helmfile/helmfile:v1.1.9 helmfile -e aws template --skip-tests > out.yaml
# validate; CustomResourceDefinition has no published schema, ExternalSecret and ClusterSecretStore are validated
docker run --rm -v "$(pwd -W)":/w ghcr.io/yannh/kubeconform:v0.7.0-alpine -strict -summary -kubernetes-version 1.33.0 \
  -schema-location default \
  -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
  -skip CustomResourceDefinition /w/out.yaml
# misconfiguration scan (the kube version flag is needed: Trivy assumes 1.20 and skips charts with a kubeVersion)
docker run --rm -v "$(pwd -W)":/repo -w /repo aquasec/trivy:0.70.0 config --helm-kube-version 1.33.0 \
  --severity HIGH,CRITICAL --ignorefile deploy/helm/.trivyignore.yaml --exit-code 1 deploy/helm
```

Trivy's chart mode does not render `sensor-platform/templates/db-bootstrap.yaml` (it is silently left out of the
report). Scan the rendered manifests as well: render each chart into a directory and run `trivy config` on it with the
same flags. The suppressions in `.trivyignore.yaml` carry their reasons.

## Hook ordering (db-bootstrap)

`helm install/upgrade` of `sensor-platform` runs, before any other object of the release:

1. weight `-10`: ServiceAccount, ConfigMap (the SQL), and the `db-bootstrap` Secret. The Secret is an `ExternalSecret`
   (or a plain Secret when `externalSecrets.enabled=false`) holding the owner, app and keycloak logins and, on AWS, the
   RDS master login read from `dbBootstrap.master.secretId` (the `rds!db-...` secret; Terraform granted External
   Secrets read access to exactly that ARN). Locally the master login is the `postgres-superuser` Secret of `local-data`.
2. weight `0`: the `db-bootstrap` Job (`postgres:17-alpine`).
3. After the release is installed (post-install/post-upgrade): a small Job deletes the `db-bootstrap` ExternalSecret,
   and with it the Secret it owns, so the master credentials do not stay in the cluster. It has its own
   ServiceAccount and a Role limited to `delete` on that one object.

Helm does not wait for an `ExternalSecret` to become ready, so the Job can start before External Secrets has synced
the Secret. That is safe: the pod references the Secret with a non-optional `secretKeyRef`, so it stays in
`CreateContainerConfigError` and retries until the Secret exists, bounded by `activeDeadlineSeconds` (900 s). This was
chosen over a two-phase install because a single `helm upgrade --install` stays idempotent and nothing has to be
remembered. The only prerequisite is the `ClusterSecretStore` (the `eso-config` release, which the helmfile orders
first) and, locally, `local-data` being ready (the Job also waits for `pg_isready`, up to 5 minutes).

The Job creates, idempotently (roles only when missing, passwords and attributes asserted on every run so a rotated
secret reaches the database):

- `sensor_owner`: LOGIN, NOSUPERUSER, NOBYPASSRLS; owns the `sensor_platform` database; the Flyway user
  (`DB_MIGRATION_USER`). Because it owns the database, V3's `GRANT CREATE ON DATABASE` works.
- `sensor_app`: LOGIN, NOSUPERUSER, NOBYPASSRLS, owns nothing (same attributes as `infra/postgres/init/10-app-role.sh`);
  `CONNECT` is granted, the table grants come from Flyway V3.
- `keycloak`: role and database `keycloak`, owned by the role; `CONNECT` revoked from PUBLIC on both databases.

`CREATE DATABASE` cannot run inside a `DO` block, so databases use a conditional `\gexec`; roles use `DO` blocks that
check `pg_roles`. It was run twice against `postgres:17-alpine`; the second run changes nothing.

The hooks run before the NetworkPolicies of the same release exist on first install; on upgrades the policy for
`db-bootstrap` (DNS and PostgreSQL only) applies.

The helmfile installs `keycloak` after `sensor-platform` for the same reason: the Job creates Keycloak's database.

## Secret mapping

ExternalSecrets read `<externalSecrets.prefix>/<key>` (default `sensor-platform/...`, created by
`deploy/terraform/modules/secrets`) and write Kubernetes Secrets that the pods load with `envFrom`.

| Environment variable | Secrets Manager name | JSON key | Kubernetes Secret | Used by |
|---|---|---|---|---|
| `DB_USER` / `DB_PASSWORD` | `sensor-platform/db/app` | `username` / `password` | `sensor-platform-app-secrets` | app |
| `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` | `sensor-platform/db/owner` | `username` / `password` | `sensor-platform-app-secrets` | app (Flyway) |
| `REDIS_PASSWORD` | `sensor-platform/redis/auth` | `auth_token` | `sensor-platform-app-secrets`, `-consumer-secrets`, `redis-auth` (local-data) | app, consumer, Redis |
| `KAFKA_SASL_USERNAME` / `KAFKA_SASL_PASSWORD` | `sensor-platform/kafka/app` | `username` / `password` | `sensor-platform-app-secrets`, `-consumer-secrets`, `kafka-scram` (local-data) | app, consumer, local Kafka |
| `PROVISIONER_CLIENT_SECRET` | `sensor-platform/keycloak/provisioner` | `client_secret` | `sensor-platform-app-secrets`, `keycloak-secrets` | app, Keycloak realm import |
| `KC_BOOTSTRAP_ADMIN_USERNAME` / `_PASSWORD` | `sensor-platform/keycloak/admin` | `username` / `password` | `keycloak-secrets` | Keycloak |
| `KC_DB_USERNAME` / `KC_DB_PASSWORD` | `sensor-platform/db/keycloak` | `username` / `password` | `keycloak-secrets` | Keycloak |
| `OWNER_*`, `APP_*`, `KEYCLOAK_DB_*` | `db/owner`, `db/app`, `db/keycloak` | as above | `sensor-platform-db-bootstrap-secrets` (hook, removed after) | db-bootstrap |
| `PGUSER` / `PGPASSWORD` | the RDS managed master secret (ARN from values) | `username` / `password` | same hook Secret | db-bootstrap (AWS) |

Not secrets, so in the ConfigMaps: `PROVISIONER_CLIENT_ID` (`sensor-platform-provisioner`, which is also the
`client_id` of `keycloak/provisioner`), hosts, ports and URLs. A rotated secret reaches the pods only when they
restart (config changes roll them through a checksum annotation; secret changes do not).

The demo users and the tenant ingestion clients of the imported realm (`DEMO_USER_PASSWORD`,
`ALPHA_INGEST_CLIENT_SECRET`, `BETA_INGEST_CLIENT_SECRET`) have no entry in Secrets Manager. The `keycloak` chart
generates them once into `keycloak-realm-secrets` and keeps them across upgrades. Terraform would need a
`keycloak/realm` secret to move them there.

`externalSecrets.enabled=false` renders plain Secrets from `plainSecrets` (environment variable names as keys): for tests
only, the values end up in the Helm release.

## NetworkPolicies

A default-deny policy selects the `app`, `consumer` and `db-bootstrap` pods of the release (the other workloads in the
namespace are not affected). Peers are lists of NetworkPolicyPeer in values: pod and namespace selectors locally, CIDRs
for the managed services on AWS; a target without peers is not allowed. Kubelet probes originate from the node, which
the CNIs allow.

| From | To | Local peers | AWS peers | Port(s) |
|---|---|---|---|---|
| ingress controller | app | Traefik pods in `kube-system` | the three public subnets (ALB targets are pod IPs) | 8080 |
| Prometheus | app | `prometheus` pods in `observability` | same | 8081 |
| app | DNS | `kube-dns` in `kube-system` | same | 53 udp/tcp |
| app | PostgreSQL | `postgres` pods | data subnets 10.20.100-102.0/24 | 5432 |
| app | Redis | `redis` pods | data subnets | 6379 |
| app | Kafka | `kafka` pod | data subnets | 9092 local / 9096 aws |
| app | Keycloak | `keycloak` pods | same | 8080 |
| app | OTLP collector | `opentelemetry-collector` in `observability` | same | 4318 |
| consumer | DNS, Kafka, Redis, collector | as above | as above | as above |
| db-bootstrap | DNS, PostgreSQL | as above | as above | 53, 5432 |

The consumer has no ingress rule at all. The CIDRs in `values-aws.yaml` are the Terraform defaults for
`10.20.0.0/16`; change them with the VPC.

## Keycloak

Chosen: a small chart in this repository (`keycloak`) instead of codecentric/keycloakx. The realm
(`infra/keycloak/sensor-platform-realm.json`) has to reach the pod as a ConfigMap and its `${...}` placeholders have to
resolve from Secret-backed environment variables; with keycloakx both need objects created outside the chart, and the
database role and credentials come from the same External Secrets and bootstrap flow as the rest of the platform. The
helmfile passes the realm file with `--set-file`, so the repository keeps one copy.

It runs `start` (production), `KC_HOSTNAME` is the public URL, `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` lets the application
use the in-cluster URL for the JWK set and the admin API (the issuer stays public, as in compose), TLS ends at the ingress
(`KC_HTTP_ENABLED`, `KC_PROXY_HEADERS=xforwarded`). More than one replica enables JGroups with DNS discovery.
The root filesystem is writable: `start` (not `--optimized`) builds the server for PostgreSQL on first start; this is the
one Trivy suppression (`KSV-0014`).

## Local install order (D4)

`helmfile -e local apply` orders the releases by `needs`: `eso-prereq` (CA ConfigMap) -> `external-secrets` ->
`eso-config` -> `local-data` -> `sensor-platform` (hook) -> `keycloak`; the observability releases are independent.
The emulator CA is read from `.scratch/aws-local/emu-home/9297/ca.pem` (`emulatorCaFile` in `environments/local.yaml`).
The Kafka SCRAM user is written when the broker's volume is first formatted; if the secret changes later, delete the
volume (`kubectl -n sensor-platform delete pvc data-kafka-0`).

## Running it locally (D4)

`scripts/aws-local.sh` runs the whole local environment; only Docker is needed (Python for the demo).

```bash
docker compose -p sensor-platform stop      # compose and k3d do not fit into the 7.6 GB Docker limit together
bash scripts/aws-local.sh up                # ~11 min cold: emulator, terraform (+ drift check), k3d, images, helmfile, wait
bash scripts/aws-local.sh status            # pods, ingress, ExternalSecrets, terraform outputs
bash scripts/aws-local.sh demo              # python scripts/demo.py all --target k8s
bash scripts/aws-local.sh deploy            # after code changes: rebuild, push, helmfile apply
bash scripts/aws-local.sh down              # removes the cluster, registry, emulator, network and local state
docker compose -p sensor-platform start
```

- One k3d server, no agent (memory), Traefik on in k3s, host port `127.0.0.1:80`, API on `127.0.0.1:6550`, registry
  `k3d-sensor-registry:5000` (pushed through `localhost:5001`). `up` refuses to run while compose containers run.
- Images are tagged `local-<timestamp>` and passed to helmfile as `imageTag` so every `deploy` rolls the pods.
- `http://sensor.localtest.me`, `keycloak.localtest.me` and `grafana.localtest.me` resolve to 127.0.0.1 through public DNS.
  If port 80 is taken on the host the Keycloak issuer (`values-local.yaml`, `keycloak/values-local.yaml`) must change with it.
- `demo.py --target k8s` reads the credentials from the cluster Secrets and uses kubectl in the `sensor-local-kubectl`
  container; Prometheus, Tempo and the management port are read through the API server's service proxy.
- Logs and tool caches stay in `.scratch/aws-local/`.
