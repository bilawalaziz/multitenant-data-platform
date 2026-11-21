# Sensor Ingestion Platform

Spring Boot service that ingests sensor readings for several tenants. Each tenant uploads an OpenAPI 3.0.3
contract and a JSON mapping. Payloads are validated against the tenant's active contract, mapped to a canonical
event, stored in PostgreSQL, and published to a per-tenant Kafka topic through a transactional outbox.
`consumer/` holds a Python consumer. Operations: [docs/OPERATIONS.md](docs/OPERATIONS.md).

## Architecture

```
 client
   | POST /api/v1/ingestion/readings  (Bearer JWT, Idempotency-Key)
   v
 +-------------------- app (Spring Boot, port 8080) ---------------------+
 | JWT, tenant check, rate limit (Redis), contract validation, mapping,  |
 | device check                                                          |
 | one DB transaction: idempotency row + ingestion record + outbox row   |
 | 202 Accepted                                                          |
 | OutboxPublisher (scheduled, lease-based claim)                        |
 +----------+------------------------------------------+-----------------+
            | JDBC                                     | Kafka, acks=all
            v                                          v
      PostgreSQL 17                          <tenant>.readings topics
        platform.* tables                              |
                                                       v
 Keycloak (8180) issues tokens               consumer/consumer.py -> Redis (dedup)
 Redis: rate limit buckets, consumer dedup keys
 Prometheus (9090) scrapes :8081, Grafana (3000) reads Prometheus and Tempo
 app + consumer -> otel-collector -> Tempo (traces)
```

## Tech stack

| Component                       | Version                                          | Source              |
|---------------------------------|--------------------------------------------------|---------------------|
| Java, Spring Boot               | 17, 3.5.16 (Spring Kafka, Flyway, Hibernate managed) | pom.xml         |
| PostgreSQL                      | 17 server, JDBC driver 42.7.14                   | docker-compose.yml, pom.xml |
| Kafka                           | 3.9.0, KRaft, one broker in compose              | docker-compose.yml  |
| Keycloak                        | 26.8.2                                           | docker-compose.yml  |
| Redis                           | 7.4.12                                           | docker-compose.yml  |
| OpenTelemetry Collector, Tempo  | contrib 0.165.0, 2.10.10                         | docker-compose.yml  |
| Prometheus, Grafana             | v3.5.0, 11.4.0                                   | docker-compose.yml  |
| Python (consumer)               | 3.12.8                                           | consumer/Dockerfile |

## Quick start

Needs Docker with Compose, Python 3 and curl.

```
cp .env.example .env              # demo values; change them before any other use
docker compose up -d --build
python scripts/demo.py all        # 16 scenarios; non-zero exit code on failure
```

`python scripts/demo.py list` shows scenario names. The demo registers `tenant-alpha` and `tenant-beta`, uploads
and activates their contracts, and checks validation, isolation, idempotency and Kafka outage recovery.

`scripts/get-token.sh` accepts `platform-admin`, `alpha-admin`, `beta-admin`, `alpha-ingest`, `beta-ingest`.
To submit a reading, run demo scenarios 3 to 5 first, then:

```
TOKEN=$(scripts/get-token.sh alpha-ingest)
curl -sS -X POST http://localhost:8080/api/v1/ingestion/readings \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: reading-0001" -H "X-Tenant-ID: tenant-alpha" \
  -d @demo/payloads/alpha-valid.json
```

The response is `202`. Repeating the request with the same key and body returns `200` with `Idempotent-Replayed: true`.

## Deployment

Kubernetes deployment (Terraform for AWS, Helm and Helmfile for the workloads, a local run on k3d and the AWS
Emulator with `bash scripts/aws-local.sh up`) is documented in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## API

Port 8080. Roles are Keycloak realm roles (`realm_access.roles`). Non-platform roles only reach the tenant named in their `tenant_id`
claim. Swagger UI: `/swagger-ui.html`. OpenAPI document: `/v3/api-docs`.

| Method    | Path                                                                          | Role                                        |
|-----------|-------------------------------------------------------------------------------|---------------------------------------------|
| POST, GET | /api/v1/admin/tenants                                                         | platform-admin                              |
| GET       | /api/v1/admin/outbox, /api/v1/admin/outbox/stats                              | platform-admin                              |
| POST      | /api/v1/admin/outbox/{id}/requeue                                             | platform-admin                              |
| GET       | /api/v1/tenants/{tenantId}                                                    | platform-admin, tenant-admin                |
| GET, PUT  | /api/v1/tenants/{tenantId}/settings                                           | platform-admin, tenant-admin                |
| POST, GET | /api/v1/tenants/{tenantId}/contracts, .../contracts/active                    | platform-admin, tenant-admin                |
| GET       | /api/v1/tenants/{tenantId}/contracts/{contractId}[/versions/{version}]        | platform-admin, tenant-admin                |
| POST      | /api/v1/tenants/{tenantId}/contracts/{contractId}/versions/{version}/activate | platform-admin, tenant-admin                |
| POST, GET | /api/v1/tenants/{tenantId}/devices                                            | platform-admin, tenant-admin                |
| POST      | /api/v1/tenants/{tenantId}/devices/{deviceId}/credentials, .../deactivate     | platform-admin, tenant-admin                |
| POST      | /api/v1/ingestion/readings (`202`, `200` replay, `429` `RATE_LIMITED`)        | tenant-ingest                               |
| GET       | /api/v1/tenants/{tenantId}/ingestions[/{ingestionId}]                         | platform-admin, tenant-admin, tenant-ingest |

Contract upload is `multipart/form-data` with parts `name`, `spec` (OpenAPI), `mapping` (JSON) and optional
`ingestionPath` (default `/readings`). Ingestion listing takes `limit` (default 50, maximum 200).

Errors are `{"status":"REJECTED","errorCode":"...","message":"...","errors":[]}`. Device and rate limit codes:

| errorCode                             | HTTP | Meaning                                                           |
|---------------------------------------|------|-------------------------------------------------------------------|
| `DEVICE_MISMATCH`                     | 403  | Device token used for another device                              |
| `DEVICE_CREDENTIAL_REQUIRED`          | 403  | Tenant-wide token while tenant-wide tokens are disabled           |
| `DEVICE_NOT_REGISTERED`               | 422  | Unknown or inactive device                                        |
| `RATE_LIMITED`                        | 429  | Tenant or device bucket empty. `Retry-After` in whole seconds, rounded up |
| `CREDENTIAL_PROVISIONING_UNAVAILABLE` | 503  | Keycloak admin API unreachable or provisioner not configured      |

## Configuration

Environment variables read by `src/main/resources/application.yml`:

| Variable                  | Default                                                                      |
|---------------------------|------------------------------------------------------------------------------|
| `SPRING_PROFILES_ACTIVE`  | `docker` (set in Dockerfile). Use `prod` in production.                      |
| `DB_URL`                  | `jdbc:postgresql://localhost:5433/sensor_platform`                           |
| `DB_USER`, `DB_PASSWORD`  | `sensor_app`, `sensor_app_dev_pw`. Runtime role, subject to row-level security. |
| `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | `sensor`, `sensor_dev_pw`. Flyway runs as this owner role. `spring.flyway.url` follows `DB_URL`. |
| `DB_STATEMENT_TIMEOUT_MS` | `15000`. Applied to every application connection, not to Flyway.             |
| `DB_LOCK_TIMEOUT_MS`      | `5000`. Applied to every application connection, not to Flyway.              |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost`, `6379`, empty.                               |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318`. Collector base URL; the app appends `/v1/traces`. |
| `TRACING_EXPORT_ENABLED`  | `false`. Compose sets `true`. Spans are created and propagated either way.   |
| `TRACING_SAMPLING_PROBABILITY` | `1.0`; `0.1` with `prod`. Unsampled traces still propagate (flag `00`). |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094`                                                             |
| `KEYCLOAK_ISSUER_URI`     | `http://localhost:8180/realms/sensor-platform`                               |
| `KEYCLOAK_JWK_SET_URI`    | `http://localhost:8180/realms/sensor-platform/protocol/openid-connect/certs` |
| `KEYCLOAK_ADMIN_URL`      | `http://localhost:8180`. Keycloak base URL the app uses for the admin API.   |
| `PROVISIONER_CLIENT_ID`   | `sensor-platform-provisioner`                                                |
| `PROVISIONER_CLIENT_SECRET` | Empty. Without it device registration answers `503`.                       |
| `MANAGEMENT_PORT`         | `8081`. Serves `health`, `info`, `prometheus`. Keep internal.                |

Compose reads more variables from `.env.example` (`POSTGRES_*`, `DB_APP_*`, `REDIS_PASSWORD`, `KEYCLOAK_*`,
`*_INGEST_CLIENT_SECRET`, `TEMPO_HOST_PORT`, `CONSUMER_*`). The consumer reads `REDIS_HOST`, `REDIS_PORT`,
`REDIS_PASSWORD` and `DEDUP_TTL_SECONDS` (compose: `CONSUMER_DEDUP_TTL_SECONDS`, default `604800`). It subscribes
to the regex `TOPIC_PATTERN` (default `^.*\.readings$`), so a tenant created later is consumed within about 20 s; an
explicit comma-separated `TOPICS` overrides the pattern. Compose no longer passes `TOPICS`.

Key `platform.*` properties:

| Property                                  | Default      | Meaning                                         |
|-------------------------------------------|--------------|-------------------------------------------------|
| `platform.ingestion.max-payload-bytes`    | 65536        | Maximum reading body size                       |
| `platform.security.tenant-ingest-credentials-enabled` | true (`false` with `prod`) | Accept tokens without a `device_id` claim |
| `platform.rate-limit.enabled`             | true         | Switch for both buckets                         |
| `platform.rate-limit.tenant.capacity` / `refill-per-second` | 400 / 200 | Tenant bucket: burst / sustained readings per second |
| `platform.rate-limit.device.capacity` / `refill-per-second` | 20 / 10   | Device bucket                       |
| `platform.rate-limit.tenant-overrides.<code>.*` | none   | Same two fields per tenant code. Tenant bucket only |
| `platform.rate-limit.failure-cooldown`    | 2s           | Checks skipped for this long after a Redis failure |
| `platform.rate-limit.connect-timeout`     | 2s           | Background Redis connect timeout                |
| `spring.data.redis.timeout`               | 200ms        | Redis command timeout                           |
| `platform.kafka.send-timeout-ms`          | 12000        | Wait limit for one publish batch                |
| `platform.outbox.fixed-delay-ms`          | 1000         | Delay between publisher cycles                  |
| `platform.outbox.batch-size`              | 50           | Rows claimed per cycle                          |
| `platform.outbox.max-attempts`            | 50           | Failed attempts before a row becomes FAILED     |
| `platform.outbox.backoff-base-ms` / `backoff-cap-ms` | 1000 / 30000 | Exponential retry backoff           |
| `platform.outbox.lease`                   | 60s          | Claim lease. Must exceed `issue-deadline`, the blocking send time and `send-timeout-ms` combined |
| `platform.outbox.issue-deadline`          | 10s          | Time one cycle may keep issuing sends           |
| `platform.retention.fixed-delay`          | PT1H         | Retention run interval                          |
| `platform.retention.outbox-published`     | 7d           | Keep published outbox rows                      |
| `platform.retention.idempotency`          | 7d           | Keep idempotency keys. Must not exceed `ingestion-records`. |
| `platform.retention.ingestion-records`    | 90d          | Keep ingestion records                          |

With `prod`, these settings have no defaults and startup fails naming any that is missing: `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`,
`REDIS_PASSWORD`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`, `KEYCLOAK_ADMIN_URL`, `PROVISIONER_CLIENT_ID`,
`PROVISIONER_CLIENT_SECRET`.

## Tests and local CI

| Command                          | Runs                                                        | Needs          |
|----------------------------------|-------------------------------------------------------------|----------------|
| `./mvnw verify`                  | Unit tests and `*IT` integration tests                      | JDK 17, Docker |
| `bash scripts/mvn.sh verify`     | Same build inside a JDK 17 container                        | Docker         |
| `bash scripts/ci-local.sh [job]` | `workflows`, `lint`, `consumer`, `build`, `docker` or `infra` (Terraform, Helm, kubeconform, trivy config, shellcheck; all if omitted) | Docker |

Consumer tests run in the `consumer` job, which installs `consumer/requirements.txt`; to run them locally use
`pip install -r consumer/requirements.txt`. Integration tests use Testcontainers for PostgreSQL and Kafka. The `docker` job builds both images, scans the app
image with Trivy (honours `.trivyignore`) and runs `promtool` on the Prometheus config and rules.

Pre-commit hooks (`pip install pre-commit && pre-commit install`): whitespace, YAML, JSON, gitleaks, ruff, spotless.

## Design notes

- **Tenants.** Shared tables use schema `platform` and a `tenant_id` column. Tenant settings are rows of
  `platform.tenant_settings` (`V6`). `platform.tenants.schema_name` still holds `ts_<code>` and the API returns it, but
  no schema is created.
- **Row-level security.** The app connects as `sensor_app` (no superuser, no `BYPASSRLS`, owns nothing). Flyway
  migrates as the owner. `V3` enables RLS on the 7 tenant-owned tables (`devices`, `contracts`, `contract_versions`,
  `contract_mappings`, `ingestion_records`, `idempotency_records`, `outbox_events`) with policy
  `tenant_id = platform.current_tenant() OR platform.rls_system()`; `V6` adds `tenant_settings` with the same policy. The session settings `app.tenant_id` and
  `app.rls_system` are set on every connection checkout (one extra `set_config` round trip). Without a scope no row
  is visible or writable. `platform.tenants` has no policy. Outbox publishing, retention and administration run with
  `app.rls_system`.
- **Contracts.** Uploads are stored as versions. One version per tenant is active (unique index). The request
  schema is taken from the spec at the ingestion path. Unregistered devices get `DEVICE_NOT_REGISTERED`.
- **Mapping.** Target fields map to source paths, with optional `transforms`, `map` and `default`. Transforms:
  `trim`, `lowercase`, `uppercase`, `toNumber`, `toString`, `isoTimestamp`, `epochSeconds`, `epochMillis`.
- **Idempotency.** Keys are unique per tenant. The hash is SHA-256 of canonical JSON. A reused key with a different
  payload returns `409`. Retention: once the idempotency row is purged (7 days), a reused key is a new ingestion.
- **Outbox.** Rows are claimed with `FOR UPDATE SKIP LOCKED` and a lease. Kafka is called outside any transaction.
  Delivery is at-least-once, so consumers deduplicate on `event_id`. The producer uses `acks=all` and idempotence.
- **Device credentials.** Registering a device creates a Keycloak client (`client_credentials` only) and returns its
  `clientId` and `clientSecret` once; `POST .../credentials` rotates the secret, creates the client for a device
  registered earlier, or recreates it if it was deleted in Keycloak. The client's tokens carry `tenant_id`,
  `device_id`, the API audience and only `tenant-ingest`. A token with a `device_id` may only submit readings of that
  device (`403 DEVICE_MISMATCH`), including on an idempotent replay. Tokens without `device_id` (the tenant-wide
  clients) work while `platform.security.tenant-ingest-credentials-enabled` is true (default; `false` with `prod`);
  otherwise `403 DEVICE_CREDENTIAL_REQUIRED`. `POST .../deactivate` marks the device INACTIVE first (readings get
  `422 DEVICE_NOT_REGISTERED` at once), then disables the client; access tokens already issued stay valid until they
  expire. Device client tokens live 300 s (`platform.keycloak.provisioner.device-token-lifespan`); tenant-wide
  clients keep the realm default (900 s in the demo realm). If Keycloak is unreachable, registration fails with
  `503 CREDENTIAL_PROVISIONING_UNAVAILABLE` and no device is stored.
  Client ids are `device-<tenant>-<device id lower-cased, characters outside a-z0-9. replaced by '-'>-<16 hex of
  SHA-256(tenant, device id)>`; the hash keeps ids that differ only by case or punctuation distinct, because
  Keycloak lower-cases the service-account username.
- **Rate limiting.** Two token buckets in Redis, tenant then device, keyed only on token claims and checked before
  any database work (`rl:t:<tenantCode>`, `rl:d:<tenantCode>:<sha256(deviceId)>`; keys expire when idle). A
  tenant-wide token has no device claim, so its device bucket is checked after payload mapping. Exceeding a limit
  returns `429 RATE_LIMITED` with `Retry-After`. A changed limit applies once the bucket key has expired. Replayed
  requests (same idempotency key) consume tokens. Clocks must be synced. Redis health is excluded from
  `/actuator/health`.
  Fail open: after a Redis error, checks are skipped for `failure-cooldown` and counted in `ratelimit.skipped`;
  errors are counted in `ratelimit.errors` and logged as a WARN at most every 30 s. The first connect runs in the
  background at startup and requests are admitted unchecked until it succeeds.
- **Tracing.** The request trace is stored in `outbox_events.trace_parent` (`V5`). The publisher starts an
  `outbox.publish` span as its child and sets `traceparent` (and `tracestate` if present) on the Kafka record. The
  consumer continues the trace and logs `trace_id` and `span_id`; app JSON logs carry `traceId` and `spanId`. The
  incoming `tracestate` is not kept across the outbox. Scheduler and security observations are disabled.
  Look a trace up with `GET http://localhost:3200/api/traces/<traceId>` (Tempo) or in Grafana.
- **Consumer dedup.** The consumer runs `SET dedup:<event_id> <tenant> NX EX <DEDUP_TTL_SECONDS>` in Redis, so
  replicas share one view and `docker compose up -d --scale consumer=N` works. The key is set before processing,
  which is safe only because processing is logging; a consumer with side effects must mark after processing. An
  `event_id` that is not a UUID is skipped as invalid. A Redis outage stops commits: the consumer retries the record
  about every 3 s (2 s timeout, no client retries, 1 s pause) and resumes without loss. If Redis loses or evicts
  keys, redelivered events are processed again.
- **Topics.** `<tenant>.readings`, created at registration or before the first send. Message key is `device_id`.
- **Retention.** Hourly job, batches of 1000. Only PUBLISHED outbox rows are deleted. Ingestion records referenced by an idempotency row are kept.

## Known limitations

- Per-device clients are created in the same Keycloak realm; there is no automated clean-up if a client is left
  behind after both the insert and the compensating delete fail (it is logged by client id); the next
  registration or credential request for that device replaces such a client.
- RLS guards against a missing tenant filter or a wrong id, not against SQL run as `sensor_app`, which could set
  `app.rls_system`.
- Tenant settings live in `platform.tenant_settings` under RLS (`V6`). The app role has no `CREATE` on the database and
  creates no `ts_*` schemas. Schemas left by earlier versions stay until an operator drops them (docs/OPERATIONS.md).
- Device tokens live 300 s (`platform.keycloak.provisioner.device-token-lifespan`); clients created earlier get this on
  their next credential rotation. After a rotate or disable, an issued device token stays valid for at most 5 minutes;
  the device registry check makes deactivation immediate.
- Device tokens read only their own device's ingestions: another device's record answers `404` and an inactive device
  `422`. With `platform.security.tenant-ingest-credentials-enabled` off, tenant-wide ingest tokens get `403` on reads.
- In a rare cross-instance race a caller can end up with an invalid secret; `POST .../credentials` fixes it.
- Rate limiting fails open: while `RateLimiterDegraded` fires, limits are not enforced.
- Dedup keys are not durable if Redis loses them (at-least-once delivery).
- Compose runs one Kafka broker with replication factor 1 and Keycloak in `start-dev` mode. The Kubernetes and AWS
  path runs Keycloak in production mode (`start`).
- `.trivyignore` accepts CVE-2026-47884 and CVE-2026-47890 in spring-webmvc 6.2.19. The fix needs Spring Boot 4.
