[![CI](https://github.com/bilawalaziz/multitenant-data-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/bilawalaziz/multitenant-data-platform/actions/workflows/ci.yml)

# Multi-Tenant Sensor Ingestion Platform

A Spring Boot service that ingests sensor readings from several tenants. Each tenant uploads its own OpenAPI 3.0.3
contract and a declarative mapping. The platform validates every incoming payload against the tenant's active
contract, maps it to one canonical event, stores it, and publishes it to a per-tenant Kafka topic through a
transactional outbox. A small Python consumer shows downstream processing. Everything runs locally with Docker Compose.

## Architecture

```
 client                                   Keycloak (JWT: tenant_id, realm role, aud)
   | POST /api/v1/ingestion/readings            ^ JWKS
   | Bearer token, Idempotency-Key              |
   v                                            |
 +------------------------- Spring Boot app ---------------------------+
 | JWT + audience + roles, TenantResolver                              |
 | active contract -> schema validation -> mapping -> device check     |
 | one DB transaction: idempotency + ingestion record + outbox row     |
 | 202 ACCEPTED                                                        |
 |                                                                     |
 | OutboxPublisher (scheduled, lease-based claim)                      |
 +--------------+---------------------------------+--------------------+
                | JDBC                            | Kafka producer (acks=all)
                v                                 v
           PostgreSQL                  <tenant>.readings (key = device_id)
     platform.* shared tables                     |
     ts_<tenant>.* per tenant                     v
                                          Python consumer (validate,
   /actuator/prometheus -> Prometheus     data-quality checks, sqlite
                        -> Grafana        dedup, commit after processing)
```

## Features

- Tenant registration with a dedicated PostgreSQL schema and Kafka topic per tenant.
- Contract registry: immutable, versioned OpenAPI contracts with one active version per tenant.
- Runtime JSON Schema validation of payloads against the active contract.
- Declarative mapping from tenant payloads to a common canonical event.
- Idempotent ingestion keyed by `Idempotency-Key`.
- Transactional outbox with at-least-once delivery to Kafka.
- JWT authentication through Keycloak with platform-admin, tenant-admin and tenant-ingest roles.
- Prometheus metrics, a Grafana dashboard, structured JSON logs and Swagger UI.

## Tech stack

| Component | Version |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.16 |
| springdoc-openapi | 2.8.17 |
| networknt json-schema-validator | 1.5.9 |
| PostgreSQL | 17 |
| Apache Kafka (KRaft, single node) | 3.9.0 |
| Keycloak | 26.8.2 |
| Prometheus | 3.5.0 |
| Grafana | 11.4.0 |
| Consumer | Python 3.12, confluent-kafka 2.6.1 |
| Build | Maven 3.9.9 (wrapper), Flyway, Testcontainers |

## Project structure

```
src/main/java/com/platform/ingestion
  tenant/          tenant registration, tenant schema, settings endpoints
  security/        resource server config, tenant resolution and access policy
  device/          device registry per tenant
  contract/        contract upload, OpenAPI parsing, versions, activation
  validation/      runtime schema validation
  transformation/  mapping document, path expressions, transforms
  ingestion/       ingestion endpoint, idempotency, transactional writer, queries
  persistence/     JPA entities and repositories (ingestion, idempotency, outbox)
  messaging/       outbox publisher, Kafka publisher, topic provisioning, outbox admin
  observability/   metrics, MDC keys, OpenAPI config
  common/          error model, exception handling, hashing, JSON canonicalisation
src/main/resources/db/migration   Flyway migrations
consumer/          Python consumer and its unit tests
demo/              example contracts, mappings and payloads for two tenants
infra/             Keycloak realm, Prometheus and Grafana configuration, Kafka notes
scripts/           demo.py, get-token.sh, mvn.sh
```

## Quick start

Prerequisites: Docker with Compose v2, and Python 3 (standard library only) for the demo script.

```bash
cp .env.example .env            # throwaway dev values
docker compose up -d --build    # postgres, kafka, keycloak, app, prometheus, grafana, consumer
docker compose ps               # wait until postgres, kafka, keycloak and app are healthy
python scripts/demo.py all      # end-to-end demo, exits non-zero if an expectation fails
```

| Service | URL |
|---|---|
| App | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Keycloak | http://localhost:8180 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
| Postgres (host) | localhost:5433 |
| Kafka (host) | localhost:9094 |

`python scripts/demo.py list` shows the scenarios, and `python scripts/demo.py 6 7 8` runs selected ones by number or
name. Scenarios 6 and 7 write `.demo-state.json`, which later scenarios read, so run them in order. Scenarios 13 and
14 stop and start the Kafka container. The script uses a fresh idempotency key per request and reuses existing tenants
and active contracts; set `DEMO_FORCE_UPLOAD=1` to upload new contract versions.

The same flow with curl (Git Bash, Linux or macOS):

```bash
TOKEN=$(scripts/get-token.sh platform-admin)   # also: alpha-admin, beta-admin, alpha-ingest, beta-ingest
curl -s -X POST localhost:8080/api/v1/admin/tenants -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{"tenantId":"tenant-alpha","displayName":"Tenant Alpha"}'
curl -s -X POST localhost:8080/api/v1/tenants/tenant-alpha/devices -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{"deviceId":"MACHINE-001","description":"demo"}'

ADMIN=$(scripts/get-token.sh alpha-admin)
curl -s -X POST localhost:8080/api/v1/tenants/tenant-alpha/contracts -H "Authorization: Bearer $ADMIN" \
     -F name=alpha-readings -F spec=@demo/tenant-alpha/openapi.yaml -F mapping=@demo/tenant-alpha/mapping.json
curl -s -X POST localhost:8080/api/v1/tenants/tenant-alpha/contracts/<contractId>/versions/1/activate \
     -H "Authorization: Bearer $ADMIN"

INGEST=$(scripts/get-token.sh alpha-ingest)
curl -i -X POST localhost:8080/api/v1/ingestion/readings \
     -H "Authorization: Bearer $INGEST" -H "Idempotency-Key: demo-001" \
     -H "X-Tenant-ID: tenant-alpha" -H 'Content-Type: application/json' \
     -d @demo/payloads/alpha-valid.json
```

Inspecting Kafka, the database and the consumer:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic tenant-alpha.readings --from-beginning --property print.key=true --timeout-ms 10000
docker compose exec postgres psql -U sensor -d sensor_platform -c '\dn'
docker compose logs consumer
```

On Git Bash for Windows, set `MSYS_NO_PATHCONV=1` for commands that pass container paths to `docker exec`.

## API overview

All endpoints require a bearer token unless marked public.

| Method and path | Roles | Result |
|---|---|---|
| `POST /api/v1/ingestion/readings` (`Idempotency-Key`, optional `X-Tenant-ID`) | tenant-ingest | 202 `ACCEPTED`, 200 `DUPLICATE`, or 400/403/409/422 |
| `POST`, `GET /api/v1/admin/tenants` | platform-admin | register / list tenants |
| `GET /api/v1/admin/outbox?status=PENDING\|PUBLISHED\|FAILED` | platform-admin | outbox rows |
| `GET /api/v1/admin/outbox/stats` | platform-admin | counts, oldest pending age |
| `POST /api/v1/admin/outbox/{id}/requeue` | platform-admin | requeue a FAILED row |
| `GET /api/v1/tenants/{tenantId}` | platform-admin, tenant-admin (own) | tenant |
| `POST`, `GET /api/v1/tenants/{tenantId}/devices` | platform-admin, tenant-admin (own) | register / list devices |
| `POST /api/v1/tenants/{tenantId}/contracts` (multipart `name`, `spec`, `mapping`, optional `ingestionPath`) | platform-admin, tenant-admin (own) | 201, new immutable version |
| `GET /api/v1/tenants/{tenantId}/contracts`, `.../contracts/{contractId}`, `.../contracts/{contractId}/versions/{version}`, `.../contracts/active` | platform-admin, tenant-admin (own) | list, details, version, active |
| `POST /api/v1/tenants/{tenantId}/contracts/{contractId}/versions/{version}/activate` | platform-admin, tenant-admin (own) | activate a version |
| `GET /api/v1/tenants/{tenantId}/ingestions?limit=`, `.../ingestions/{ingestionId}` | platform-admin, tenant-admin (own), tenant-ingest (own) | records with canonical event |
| `GET`, `PUT /api/v1/tenants/{tenantId}/settings` | platform-admin, tenant-admin (own) | tenant schema settings |
| `GET /actuator/health`, `/actuator/info`, `/actuator/prometheus` | public, management port only (8081) | health and metrics |
| `GET /swagger-ui.html`, `/v3/api-docs` | public | API docs |

All errors share one body:

```json
{
  "status": "REJECTED",
  "errorCode": "PAYLOAD_VALIDATION_FAILED",
  "message": "Payload does not conform to the active contract",
  "errors": [
    { "field": "sensorId",    "message": "Missing required field" },
    { "field": "temperature", "message": "Expected numeric value" },
    { "field": "unit",        "message": "Must be one of: celsius, fahrenheit" }
  ]
}
```

Error codes and HTTP status: `UNAUTHENTICATED` 401; `ACCESS_DENIED`, `TENANT_MISMATCH`, `TENANT_NOT_REGISTERED`,
`TENANT_INACTIVE` 403; `MALFORMED_REQUEST`, `MISSING_IDEMPOTENCY_KEY`, `PAYLOAD_VALIDATION_FAILED`, `INVALID_CONTRACT`,
`INVALID_MAPPING` 400; `RESOURCE_NOT_FOUND` 404; `IDEMPOTENCY_KEY_CONFLICT`, `CONFLICT` 409; `NO_ACTIVE_CONTRACT`,
`DEVICE_NOT_REGISTERED`, `TRANSFORMATION_FAILED` 422; `INTERNAL_ERROR` 500.

## Configuration

Compose reads `.env` (copy `.env.example`; all values there are throwaway demo values).

| Variable | Purpose |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_HOST_PORT` | database name, credentials and host port (5433) |
| `KAFKA_EXTERNAL_PORT` | Kafka port exposed to the host (9094) |
| `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_HOST_PORT` | Keycloak admin account and host port (8180) |
| `DEMO_USER_PASSWORD` | password of the demo users (`platform-admin`, `alpha-admin`, `beta-admin`) |
| `ALPHA_INGEST_CLIENT_SECRET`, `BETA_INGEST_CLIENT_SECRET` | client secrets of the ingestion service accounts |
| `APP_HOST_PORT` | application host port (8080); the management port is published on 127.0.0.1:8081 only |
| `PROMETHEUS_HOST_PORT`, `GRAFANA_HOST_PORT` | host ports (9090, 3000) |
| `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | Grafana admin account |
| `CONSUMER_TOPICS`, `CONSUMER_GROUP_ID` | topics and consumer group of the Python consumer |

Application settings live in `src/main/resources/application.yml`, for example `platform.outbox.batch-size`,
`platform.outbox.max-attempts` and `platform.outbox.lease`.

## Running tests

```bash
bash scripts/mvn.sh verify   # Maven inside a JDK 17 container; needs only Docker
./mvnw verify                # same on a host with JDK 17
cd consumer && python -m unittest -v test_consumer   # consumer tests, no Docker or Kafka needed
```

Unit tests need no Docker. The integration tests (`*IT`) start PostgreSQL and a single-node Kafka with Testcontainers,
so Docker must be running.

Testcontainers 1.20.x bundles a docker-java client that defaults to Docker API 1.32, which Docker Engine 29 rejects.
`scripts/mvn.sh` writes `api.version=1.44` to `~/.docker-java.properties` inside the build container; any Engine 25 or
newer accepts it. If you run `./mvnw verify` on the host against such an Engine, create that file yourself with the
same content.

## Development

Install the commit hooks once per clone:

```bash
pip install pre-commit
pre-commit install
```

The hooks check whitespace, YAML and JSON, scan for secrets with gitleaks, run ruff on the Python code and
`spotless:check` on the Java code. To format by hand:

```bash
./mvnw spotless:apply     # Java (palantir-java-format)
ruff format consumer scripts/demo.py
```

`./mvnw verify` also runs `spotless:check`, so unformatted Java fails the build.

To run the CI jobs locally before pushing, each in the same pinned Docker image CI uses (only Docker is needed):

```bash
bash scripts/ci-local.sh            # all jobs
bash scripts/ci-local.sh build      # or: lint | consumer | docker | workflows
```

## Design notes

### Multi-tenancy

Tenant-owned tables live in a shared `platform` schema and carry `tenant_id`. In addition, registering a tenant creates
a schema `ts_<code_with_underscores>` (for example `ts_tenant_alpha`) holding a `tenant_settings` table, exposed through
the settings endpoints. The schema name is derived from the validated tenant code (`^[a-z][a-z0-9-]{2,30}$`),
re-validated against `^ts_[a-z][a-z0-9_]{2,30}$` and quoted, because identifiers cannot be bound as parameters.

The tenant comes from the `tenant_id` claim of the JWT. `X-Tenant-ID` is only a hint: a different value is rejected
with `TENANT_MISMATCH`. The tenant must exist and be ACTIVE in `platform.tenants`. Every repository method for
tenant-owned data takes the tenant id, and resources addressed by id under another tenant's path return 404. Platform
admins may address any tenant; tenant roles only their own.

Keycloak realm `sensor-platform` is imported from `infra/keycloak/sensor-platform-realm.json`, with realm roles
`platform-admin`, `tenant-admin` and `tenant-ingest`, and audience `sensor-platform-api`. Tokens are issued with the
public issuer `http://localhost:8180/realms/sensor-platform`, while the app fetches keys from the internal
`http://keycloak:8080/...` JWKS URL.

### Contract registry and runtime validation

A contract is uploaded as an OpenAPI document (JSON or YAML, at most 512 KB) plus a mapping. The parser requires
`openapi: 3.0.3` and a `POST <ingestionPath>` operation (default `/readings`) with an `application/json` request
schema. Every `$ref` must be local (`#/...`); external references are rejected so that validation never depends on a
network or file lookup. The request schema is extracted with the original `components` attached so local references
resolve, and the SHA-256 checksum of the raw spec is stored.

Each upload creates a new immutable version. Activating a version re-validates its mapping and makes it the tenant's
single ACTIVE version (enforced by a partial unique index).

At ingestion time `SchemaValidationService` validates the payload with networknt json-schema-validator in the OpenAPI
3.0 dialect, with format assertions on and a schema loader that refuses any remote resource. Compiled schemas are cached
per (tenant, contract, version) in a bounded map; versions are immutable, so entries never go stale.

### Mapping to the canonical event

A mapping is a flat JSON object: the key is a canonical target, the value is a path string or
`{path, transforms, map, default}`. Paths use a small syntax (`$.a.b[0].c`) with its own parser. Required targets are
`device_id`, `sensor_id`, `value`, `unit` and `event_timestamp`; optional ones are `metadata.<name>`. Transforms come from
a fixed list (`trim`, `lowercase`, `uppercase`, `toNumber`, `toString`, `isoTimestamp`, `epochSeconds`, `epochMillis`),
applied in the order extract, transforms, `map`, `default`. Mappings are checked at upload and activation, including
that every path exists in the request schema.

The two demo tenants use different payload shapes (camelCase for alpha, snake_case with nested diagnostics for beta)
and produce the same canonical event. After mapping, `device_id` must be a registered ACTIVE device of the tenant,
otherwise the request fails with 422 `DEVICE_NOT_REGISTERED`.

### Idempotency

`Idempotency-Key` is required (1 to 128 characters of `[A-Za-z0-9._:-]`) and scoped to the tenant. The request hash is
the SHA-256 of the canonical JSON of the payload (sorted keys, normalised numbers). Inside the ingestion transaction the
key is claimed with `INSERT ... ON CONFLICT DO NOTHING`, so concurrent duplicates are decided by the primary key rather
than by a read followed by a write. The same key and hash returns 200 `DUPLICATE` with the original ids and the header
`Idempotent-Replayed: true`; the same key with a different payload returns 409 `IDEMPOTENCY_KEY_CONFLICT`.

### Transactional outbox and delivery

The ingestion transaction writes the idempotency record, the ingestion record and an outbox row together, and the 202 is
returned only after commit. `OutboxPublisher` then works in three steps, none of which holds a database transaction or
row lock while talking to Kafka:

1. Claim: in a short transaction it selects due PENDING rows with `FOR UPDATE SKIP LOCKED` and pushes their
   `next_attempt_at` to now plus `platform.outbox.lease` (default 60 s). Other instances skip them because they are no
   longer due.
2. Publish: rows are sent in order and synchronously. The batch stops at the first failure, since the broker is likely
   down, and the remaining claimed rows are released to the failed row's backoff time.
3. Outcome: one short transaction per row marks it PUBLISHED, or records the failed attempt with exponential backoff
   (base 1 s, cap 30 s). Updates apply only while the row is still PENDING. After `max-attempts` (default 50) the row
   becomes FAILED and a platform admin can requeue it.

Delivery is at-least-once. If the process dies after Kafka accepted a message but before the row is marked, the lease
expires and the message is published again, so consumers must deduplicate on `event_id`. The demo consumer does so with
sqlite, commits offsets only after processing, and skips and commits poison messages.

### Per-tenant topics

Each tenant has a topic `<tenantCode>.readings`, taken from the tenant record and never from the request. Topics are
created through the Kafka AdminClient (3 partitions, replication factor 1) on tenant registration; if Kafka is down then,
registration still succeeds and the publisher ensures the topic before the first publish. The broker runs with
`auto.create.topics.enable=false`. The message key is `device_id`, giving per-device ordering, and headers are `event_id`,
`tenant_id` and `schema_version`. The producer uses `acks=all`, idempotence and bounded timeouts.

### Observability

Prometheus scrapes `/actuator/prometheus` on the management port (`MANAGEMENT_PORT`, default 8081), which is not
served on the API port and must not be exposed publicly. Alert rules are in `infra/prometheus/alerts.yml`; delivering them to on-call needs an Alertmanager configured at
deployment (the compose stack only evaluates the rules). Custom metrics use low-cardinality labels only:

| Metric | Labels |
|---|---|
| `ingestion_requests_total` | `outcome`: accepted, duplicate, rejected |
| `ingestion_rejections_total` | `reason`: error code |
| `ingestion_validation_failures_total` | none |
| `ingestion_processing_seconds` | timer with histogram buckets |
| `outbox_publish_total` | `result`: success, failure |
| `outbox_pending_events`, `outbox_failed_events` | gauges, refreshed every 5 s |

Grafana provisions the `ingestion-platform` dashboard (`/d/ingestion-platform`) and allows anonymous viewing. In the
`docker` profile the app logs one JSON object per line (Spring Boot `logstash` format), and ingestion lines carry the
MDC fields `tenantId`, `eventId` and `ingestionId`.

## Known limitations and future work

- The setup is dev-grade: Keycloak `start-dev`, plain HTTP, password grant on the demo client, secrets in `.env.example`.
- Single Kafka broker with replication factor 1, and a single PostgreSQL instance.
- Tenant isolation is enforced in application code (scoped queries and authorisation). There is no PostgreSQL row-level
  security and no per-tenant schema routing; tenant schemas only hold `tenant_settings`. Row-level security is the
  natural next hardening step.
- Each instance publishes the outbox from a single scheduler thread, sequentially. That is fine at demo scale but not
  tuned for throughput, and the lease must stay longer than the time a batch takes to publish.
- Delivery is at-least-once only. The demo consumer has no dead-letter topic and its sqlite dedup store is
  single-instance.
- Only OpenAPI 3.0.3 with local `$ref`s is supported, and mapping transforms are a fixed list.
- No rate limiting or quotas, and no distributed tracing.
