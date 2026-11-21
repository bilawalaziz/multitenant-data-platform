# Terraform: AWS target and local emulator environment (D2)

> **Cost and credentials.** A `plan` or `apply` in `envs/aws` creates real, billable infrastructure
> (EKS, NAT gateways, Multi-AZ RDS, MSK, ElastiCache) and needs the owner's AWS credentials. Do not run
> either without the owner's explicit go-ahead. Only `init -backend=false`, `validate`, `fmt` and the
> linters are run without credentials.

## Layout

```
deploy/terraform/
  modules/
    platform/     composition used by both envs: wires the modules below, owns the feature flags
    network/      VPC, 3 AZ x (public, app, data) subnets, routes, NAT, security groups
    kms/          platform key + alias, rotation on
    secrets/      Secrets Manager secrets with random_password values (+ AmazonMSK_ SCRAM secret)
    ecr/          sensor-platform/app and /consumer
    s3/           Tempo traces bucket
    iam/          Pod Identity roles and policies: External Secrets, Tempo, AWS Load Balancer Controller
    eks/          wraps terraform-aws-modules/eks 21.30.2 and the Pod Identity associations
    rds/          PostgreSQL 17 Multi-AZ
    elasticache/  Valkey 8 replication group
    msk/          3-broker SASL/SCRAM cluster
  envs/
    aws/          real AWS: all features on, validate-only here
    local/        AWS Emulator: provider endpoints plus flags create_eks/create_data_services/create_nat = false
  .tflint.hcl     tflint config (aws ruleset 0.50.0)
```

Each module has `variables.tf`, `main.tf`, `outputs.tf`, `versions.tf`. Both envs call only `modules/platform`;
the sole differences are the provider block and the flags.

Network uses plain resources in both envs instead of `terraform-aws-modules/vpc`: one code path, and
the subnet tiers and the security group matrix are specific to this platform anyway. Only EKS uses a
registry module (pinned), instantiated with `count = 0` when `create_eks` is false.

Tags: `project=sensor-platform` and `env=<aws|local>` through the provider's `default_tags`.

## Security group matrix

| From | To | Port | Note |
|------|----|------|------|
| 0.0.0.0/0 | alb | 443, 80 | the only public ingress; 80 redirects to 443 |
| alb | nodes | app port, Keycloak port (both 8080 by default; deduplicated when equal) | |
| nodes | rds | 5432 | |
| nodes | redis | 6379 | TLS |
| nodes | msk | 9096 | SASL/SCRAM over TLS |

`nodes` is an extra security group attached to the managed node group. The EKS module keeps its own
node group for control-plane traffic and general egress (ECR, STS, DNS, S3). Data tiers accept
nothing else and have no egress rules; the data subnets have no route to the internet.

## Run it

All Terraform runs in `hashicorp/terraform:1.17.2` (provider `hashicorp/aws` 6.73.0). From the repo
root, in Git Bash. State, plugin cache and logs go to `.scratch/tf/` (git-ignored).

```bash
export MSYS_NO_PATHCONV=1
mkdir -p .scratch/tf/cache
TF_IMAGE=hashicorp/terraform:1.17.2

# validation, no emulator and no credentials: tf_run <aws|local> <terraform args>
tf_run() { local env=$1; shift
  timeout 300 docker run --rm -v "$(pwd -W):/repo" -w "/repo/deploy/terraform/envs/$env" \
    -v "$(pwd -W)/.scratch/tf/cache:/tfcache" -e TF_DATA_DIR="/repo/.scratch/tf/data-$env" \
    -e TF_PLUGIN_CACHE_DIR=/tfcache -e TF_IN_AUTOMATION=1 $TF_IMAGE "$@"; }

tf_run aws fmt -check -recursive /repo/deploy/terraform
for e in aws local; do tf_run $e init -backend=false -input=false && tf_run $e validate; done
```

Linters:

```bash
# tflint (plugin is pinned in .tflint.hcl)
T="docker run --rm -v $(pwd -W):/repo -w /repo/deploy/terraform -e TFLINT_PLUGIN_DIR=/repo/.scratch/tf/tflint-plugins ghcr.io/terraform-linters/tflint:v0.66.0"
mkdir -p .scratch/tf/tflint-plugins
$T --init && $T --recursive --config=/repo/deploy/terraform/.tflint.hcl

# trivy (same pin as scripts/ci-local.sh)
docker run --rm -v "$(pwd -W):/repo" -v ci-local-trivy:/root/.cache aquasec/trivy:0.70.0 \
  config --severity HIGH,CRITICAL --tf-exclude-downloaded-modules /repo/deploy/terraform
```

Local apply against the AWS Emulator 1.0.556 (see `deploy/spike/README.md` for why it is built locally and
why Terraform shares its network namespace: the certificate only names `127.0.0.1`).
**Never point Terraform at a named host with the CA: it hangs.** Every call is wrapped in `timeout 300`.

```bash
export MSYS_NO_PATHCONV=1
docker build -t sensor-spike/aws-emulator:1.0.556 deploy/spike/emulator     # skip if the image exists
docker network create sensor-tf-net
mkdir -p .scratch/tf/emu-home .scratch/tf/local
docker run -d --name sensor-tf-emulator --network sensor-tf-net --memory=2g --memory-swap=2g \
  -p 127.0.0.1:9297:9297 -v "$(pwd -W)/.scratch/tf/emu-home:/home/emulator/.aws/emulator" \
  sensor-spike/aws-emulator:1.0.556 -p 9297 --telemetry off --auth off

tf_local() { timeout 300 docker run --rm --network container:sensor-tf-emulator -v "$(pwd -W):/repo" \
  -w /repo/deploy/terraform/envs/local -v "$(pwd -W)/.scratch/tf/emu-home/9297:/ca:ro" \
  -v "$(pwd -W)/.scratch/tf/cache:/tfcache" -e TF_DATA_DIR=/repo/.scratch/tf/data-local \
  -e TF_PLUGIN_CACHE_DIR=/tfcache -e AWS_CA_BUNDLE=/ca/ca.pem -e TF_IN_AUTOMATION=1 $TF_IMAGE "$@"; }

tf_local init -input=false
tf_local apply -auto-approve -input=false
tf_local plan -detailed-exitcode -input=false      # exit 0 = no changes
tf_local output                                    # what D3/D4 consume
tf_local destroy -auto-approve -input=false

docker rm -f sensor-tf-emulator; docker network rm sensor-tf-net
```

A `timeout` that kills the client leaves the Terraform container running and the state locked; remove the
container (`docker rm -f`) before the next call.

## Modules: inputs and outputs in brief

| Module | Main inputs | Outputs |
|--------|-------------|---------|
| `platform` | `azs` (3), `tempo_bucket_name`, flags `create_eks`, `create_data_services`, `create_nat`, lifecycle knobs (`kms_deletion_window_in_days`, `secret_recovery_window_in_days`, `force_delete`, `rds_deletion_protection`, `single_nat_gateway`), EKS sizing | everything below, re-exported; data-service and EKS outputs are `null` when off |
| `network` | `name`, `cluster_name`, `azs`, `vpc_cidr`, `create_nat`, `single_nat_gateway`, `app_port`, `keycloak_port` | `vpc_id`, `public/app/data_subnet_ids`, `security_group_ids` (alb, nodes, rds, redis, msk), `nat_gateway_ids` |
| `kms` | `name`, `deletion_window_in_days` | `key_arn`, `key_id`, `alias_name` |
| `secrets` | `kms_key_arn`, `name_prefix`, `recovery_window_in_days`, user names | `secret_names`, `secret_arns` (db/app, db/owner, db/keycloak, redis/auth, kafka/app, keycloak/admin, keycloak/provisioner), `msk_scram_secret_arn/name`, `redis_auth_token` (sensitive) |
| `ecr` | `repository_names`, `kms_key_arn`, `retained_image_count` | `repository_urls`, `repository_arns` |
| `s3` | `bucket_name`, `kms_key_arn`, `expiration_days` | `bucket_name`, `bucket_arn` |
| `iam` | `kms_key_arn`, `tempo_bucket_arn`, `service_accounts` | `role_arns`, `policy_arns`, `pod_identity_associations` |
| `eks` | `cluster_name`, `vpc_id`, `app_subnet_ids`, `nodes_security_group_id`, `kms_key_arn`, `pod_identity_associations` | `cluster_name`, `cluster_arn`, `cluster_endpoint`, `node_security_group_id` |
| `rds` | `data_subnet_ids`, `security_group_id`, `kms_key_arn` | `address`, `port`, `db_name`, `master_user_secret_arn` |
| `elasticache` | `data_subnet_ids`, `security_group_id`, `kms_key_arn`, `auth_token` (sensitive) | `primary_endpoint_address`, `reader_endpoint_address`, `port` |
| `msk` | `data_subnet_ids`, `security_group_id`, `kms_key_arn`, `scram_secret_arn` | `cluster_arn`, `bootstrap_brokers_sasl_scram` |

`envs/local` outputs what D3/D4 need: `secret_names`, `secret_arns`, `msk_scram_secret_name`, `kms_key_arn`,
`ecr_repository_urls`, `tempo_bucket_name`, `iam_role_arns`, `vpc_id`, `security_group_ids`.

Pod Identity bindings (namespace / service account): `external-secrets/external-secrets`,
`kube-system/aws-load-balancer-controller`, `observability/tempo`. The app and consumer service
accounts have no AWS role.

## Secrets and state

Passwords come from `random_password` (40 letters and digits), never from variables. **They are stored in
Terraform state in clear text**, as are the secret versions, so the state backend must be encrypted
(S3 backend with `encrypt = true`, a KMS key and `use_lockfile = true`, bucket access limited to the
platform owners). Outputs never carry secret values; `redis_auth_token` is sensitive.
RDS creates its own master secret (`rds!...`, outside `sensor-platform/`), so External Secrets cannot read it.

## AWS-only

`envs/aws` only: EKS (and the registry module), RDS, ElastiCache, MSK, NAT gateways, Pod Identity
associations, the ACM certificate for the ALB, the S3 backend (commented example in `versions.tf`).
The ALB itself is created by the AWS Load Balancer Controller from an Ingress; Terraform provides its
security group, the public subnets and the certificate. Not covered: VPC flow logs, an S3 gateway endpoint,
KMS encryption of the EKS and MSK log groups (trivy reports these at LOW/MEDIUM only).

## Emulator compatibility check (AWS Emulator 1.0.556)

Source: `issues/things-that-do-not-work/` in github.com/aws/aws-emulator (5005 files read through the GitHub
API: file list plus the raw issue bodies cited below, 2026-11-21). The tracker is a point-in-time bug list
(some stubs are fixed since), so the last column is the result of the real apply.

| Operation(s) | Expected | Evidence | Applied on 1.0.556 |
|--------------|----------|----------|--------------------|
| EC2 CreateVpc, CreateSubnet | risky | `ec2-createsubnet-stub` (501 stub, HIGH) | works |
| EC2 CreateSecurityGroup, Authorize/Revoke SG ingress and egress | risky | `ec2-revokesecuritygroupegress-stub`, `ec2-revokesecuritygroupingress-answers-400-for-a-rule-teardown` | works; `aws_vpc_security_group_*_rule` never converges (see below) |
| EC2 CreateInternetGateway, CreateRouteTable, CreateRoute, AssociateRouteTable | risky | `ec2-createroute-refuses-a-valid-route-and-authors` (valid CreateRoute answered 400) | works |
| EC2 AllocateAddress, CreateNatGateway | broken | `ec2-elastic-ip-allocations-are-invisible-to-describeaddresses`, `ec2-disassociatenatgatewayaddress-stub` | not attempted (`create_nat = false`) |
| KMS CreateKey, EnableKeyRotation, CreateAlias | likely | `kms-implement-kms-tagresource...` (TagResource stub; tags sent at CreateKey) | works |
| Secrets Manager CreateSecret, PutSecretValue | likely | only rotation issues (`secretsmanager-rotatesecret-*`), rotation is not used | works |
| ECR CreateRepository (KMS), PutLifecyclePolicy, scan and immutability settings | likely | `ecr-kms-ecr-createrepository-with-encryptiontype-kms-creates-no-kms` (no KMS grant is made) | works, no drift |
| IAM CreateRole, CreatePolicy, AttachRolePolicy | likely | `iam-policy-documents-are-returned-as-raw-json`, `iam-malformed-iam-policy-documents-are-accepted` | works, no drift |
| S3 CreateBucket, PutBucketEncryption/Versioning/Lifecycle/PublicAccessBlock/Policy | risky | `...putbucketpolicy-does-not-store-the-policy-document-verbatim` | works; the lifecycle call takes about 58 s; no drift |
| EKS, RDS, ElastiCache, MSK | faked, not applied | D26; `rds-describedbinstances-omits-modeled-members...`, `kafka-msk-not-found-already-exists-and-tagging-refusals` | skipped (`create_eks`, `create_data_services` false) |

Local-only findings that shape the code:

- **SG rules:** with `skip_requesting_account_id = true`, reading an `aws_vpc_security_group_ingress_rule`/
  `egress_rule` that references another group returns `<account>/sg-...`, so every plan shows 8 in-place
  changes. The modules use `aws_security_group_rule`, which reads back cleanly.
- **Account id lookup:** turning `skip_requesting_account_id` off makes the provider call S3 Control at
  `<account>.s3-control...`, which hangs or fails (`lookup 000000000000.127.0.0.1: no such host`). Keep it on.
- `terraform init` of `envs/local` still downloads the EKS registry module (and its tls, time, cloudinit, null
  providers) although `create_eks = false`.
