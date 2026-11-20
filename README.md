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
 | JWT, tenant check, contract validation, mapping, device check         |
 | one DB transaction: idempotency row + ingestion record + outbox row   |
 | 202 Accepted                                                          |
 | OutboxPublisher (scheduled, lease-based claim)                        |
 +----------+------------------------------------------+-----------------+
            | JDBC                                     | Kafka, acks=all
            v                                          v
      PostgreSQL 17                          <tenant>.readings topics
   platform.* tables, ts_<tenant>.*                    |
                                                       v
 Keycloak (8180) issues tokens               consumer/consumer.py
 Prometheus (9090) scrapes :8081, Grafana (3000) reads Prometheus
```

## Tech stack

| Component                       | Version                                          | Source              |
|---------------------------------|--------------------------------------------------|---------------------|
| Java, Spring Boot               | 17, 3.5.16 (Spring Kafka, Flyway, Hibernate managed) | pom.xml         |
| PostgreSQL                      | 17 server, JDBC driver 42.7.14                   | docker-compose.yml, pom.xml |
| Kafka                           | 3.9.0, KRaft, one broker in compose              | docker-compose.yml  |
| Keycloak                        | 26.8.2                                           | docker-compose.yml  |
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
| POST      | /api/v1/ingestion/readings                                                    | tenant-ingest                               |
| GET       | /api/v1/tenants/{tenantId}/ingestions[/{ingestionId}]                         | platform-admin, tenant-admin, tenant-ingest |

Contract upload is `multipart/form-data` with parts `name`, `spec` (OpenAPI), `mapping` (JSON) and optional
`ingestionPath` (default `/readings`). Ingestion listing takes `limit` (default 50, maximum 200).

## Configuration

Environment variables read by `src/main/resources/application.yml`:

| Variable                  | Default                                                                      |
|---------------------------|------------------------------------------------------------------------------|
| `SPRING_PROFILES_ACTIVE`  | `docker` (set in Dockerfile). Use `prod` in production.                      |
| `DB_URL`                  | `jdbc:postgresql://localhost:5433/sensor_platform`                           |
| `DB_USER`, `DB_PASSWORD`  | `sensor`, `sensor_dev_pw`                                                    |
| `DB_STATEMENT_TIMEOUT_MS` | `15000`. Applied to every connection, Flyway included.                       |
| `DB_LOCK_TIMEOUT_MS`      | `5000`. Applied to every connection, Flyway included.                        |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094`                                                             |
| `KEYCLOAK_ISSUER_URI`     | `http://localhost:8180/realms/sensor-platform`                               |
| `KEYCLOAK_JWK_SET_URI`    | `http://localhost:8180/realms/sensor-platform/protocol/openid-connect/certs` |
| `MANAGEMENT_PORT`         | `8081`. Serves `health`, `info`, `prometheus`. Keep internal.                |

Compose reads more variables from `.env.example` (`POSTGRES_*`, `KEYCLOAK_*`, `*_INGEST_CLIENT_SECRET`, `CONSUMER_*`).

Key `platform.*` properties:

| Property                                  | Default      | Meaning                                         |
|-------------------------------------------|--------------|-------------------------------------------------|
| `platform.ingestion.max-payload-bytes`    | 65536        | Maximum reading body size                       |
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

With `prod`, six settings have no defaults and startup fails if one is missing: `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`.

## Tests and local CI

| Command                          | Runs                                                        | Needs          |
|----------------------------------|-------------------------------------------------------------|----------------|
| `./mvnw verify`                  | Unit tests and `*IT` integration tests                      | JDK 17, Docker |
| `bash scripts/mvn.sh verify`     | Same build inside a JDK 17 container                        | Docker         |
| `bash scripts/ci-local.sh [job]` | `workflows`, `lint`, `consumer`, `build` or `docker` (all if omitted) | Docker |

Integration tests use Testcontainers for PostgreSQL and Kafka. The `docker` job builds both images, scans the app
image with Trivy (honours `.trivyignore`) and runs `promtool` on the Prometheus config and rules.

Pre-commit hooks (`pip install pre-commit && pre-commit install`): whitespace, YAML, JSON, gitleaks, ruff, spotless.

## Design notes

- **Tenants.** Shared tables use schema `platform` and a `tenant_id` column. Each tenant also gets schema `ts_<code>`.
- **Contracts.** Uploads are stored as versions. One version per tenant is active (unique index). The request
  schema is taken from the spec at the ingestion path. Unregistered devices get `DEVICE_NOT_REGISTERED`.
- **Mapping.** Target fields map to source paths, with optional `transforms`, `map` and `default`. Transforms:
  `trim`, `lowercase`, `uppercase`, `toNumber`, `toString`, `isoTimestamp`, `epochSeconds`, `epochMillis`.
- **Idempotency.** Keys are unique per tenant. The hash is SHA-256 of canonical JSON. A reused key with a different
  payload returns `409`. Retention: once the idempotency row is purged (7 days), a reused key is a new ingestion.
- **Outbox.** Rows are claimed with `FOR UPDATE SKIP LOCKED` and a lease. Kafka is called outside any transaction.
  Delivery is at-least-once, so consumers deduplicate on `event_id`. The producer uses `acks=all` and idempotence.
- **Topics.** `<tenant>.readings`, created at registration or before the first send. Message key is `device_id`.
- **Retention.** Hourly job, batches of 1000. Only PUBLISHED outbox rows are deleted. Ingestion records referenced by an idempotency row are kept.

## Known limitations

- No Postgres row-level security. Tenant isolation is enforced in the application.
- No per-device credentials. Devices are registry records; ingestion uses tenant client credentials.
- No rate limiting and no distributed tracing.
- Consumer deduplication uses a local sqlite file and is not shared between consumer instances.
- Compose runs one Kafka broker with replication factor 1 and Keycloak in `start-dev` mode.
- `.trivyignore` accepts CVE-2026-47884 and CVE-2026-47890 in spring-webmvc 6.2.19. The fix needs Spring Boot 4.
