# Operations

## Deployment requirements

- Run with `SPRING_PROFILES_ACTIVE=prod`. These variables have no defaults in this profile: `DB_URL`, `DB_USER`,
  `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`. Startup fails naming
  any that is missing.
- With `prod` the provisioner settings are also required: `KEYCLOAK_ADMIN_URL`, `PROVISIONER_CLIENT_ID`,
  `PROVISIONER_CLIENT_SECRET`. Tenant-wide ingestion tokens are refused in this profile
  (`platform.security.tenant-ingest-credentials-enabled=false`), so every device needs its own credential.
- Setting `SPRING_PROFILES_ACTIVE=prod` replaces the image default `docker`. Both profiles log JSON (logstash
  format) to the console.
- Management port `8081` serves `health`, `info` and `prometheus`. Keep it on the internal network. Compose binds it
  to `127.0.0.1` only. Port `8080` does not serve actuator endpoints.
- Alerts need Alertmanager. `infra/prometheus/prometheus.yml` has `rule_files` but no `alerting` section, so rules
  are evaluated and nothing is delivered until Alertmanager is configured.
- Kafka: compose runs one broker and sets internal topic replication to 1. For production, run at least three
  brokers, set `platform.kafka.topic-replication-factor` to match, and set `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`,
  `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR` and `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR` to suit the cluster.
- Keycloak: compose runs `start-dev` and imports `infra/keycloak/sensor-platform-realm.json`, which creates demo
  users and clients. Production needs Keycloak in production mode (`start`) behind TLS with a fixed hostname, and
  `KEYCLOAK_ISSUER_URI` and `KEYCLOAK_JWK_SET_URI` must point at it.
- Device credentials: the app authenticates to the Keycloak admin API as client `sensor-platform-provisioner`. Its
  service account holds the realm-management roles `manage-clients` and `manage-users` and the `kc-admin-api` client
  scope (Keycloak 26.8 requires the admin-API audience on every admin token); it does not hold `manage-realm`. Each
  role was found necessary by removing it: without `manage-clients` client creation is refused, without
  `manage-users` the service account's attributes and roles cannot be set. Device clients get their `tenant_id`,
  `device_id` and the API audience from the shared client scope `device-identity` (defined in the realm export, not a
  realm default) through user-attribute mappers on the service account, which avoids per-client protocol mappers and
  with them `manage-realm`. Those attributes are admin-only in the realm's user profile (unmanaged attributes are
  `ADMIN_EDIT`), and a device token is not accepted by the account or admin API. The realm export already contains
  all of it; an existing realm needs the same client and scope added by hand because the import only runs on an
  empty realm. The provisioner client has no API audience and no API role, so its token is useless against the
  platform API.
- Secrets: `DB_PASSWORD`, client secrets, `PROVISIONER_CLIENT_SECRET`, `KEYCLOAK_ADMIN_PASSWORD`, `GRAFANA_ADMIN_PASSWORD` and
  `DEMO_USER_PASSWORD` come from a secret store, not from a committed `.env`. Compose refuses to start when a
  required variable is unset (`:?` syntax in `docker-compose.yml`).
- The image runs as user `app`. JVM options go in `JAVA_OPTS`. The Docker healthcheck reads `/actuator/health`
  on port 8081.

## Database migrations

- Flyway runs on startup against schema `platform` (`spring.flyway.*` in `application.yml`). `V1` creates the
  base schema, `V2` adds retention indexes.
- Every connection, Flyway included, has `statement_timeout` 15 s and `lock_timeout` 5 s
  (`DB_STATEMENT_TIMEOUT_MS`, `DB_LOCK_TIMEOUT_MS`). A migration that needs more sets `SET LOCAL` itself, as `V2`
  does with `SET LOCAL statement_timeout = 0`.
- `V2` builds its indexes without `CONCURRENTLY`. On a database with large tables, that build blocks writes for its
  duration. Run it in a maintenance window.
- For a future index on a large live table, use `CREATE INDEX CONCURRENTLY` in a migration that Flyway runs outside
  a transaction (`executeInTransaction=false`, as the `V2` comment describes).
- Migrations are forward-only. Never edit an applied migration; Flyway rejects changed checksums at startup.

## Alerts

Rules are in `infra/prometheus/alerts.yml`.

| Alert                  | Fires when                                                              | First action |
|------------------------|-------------------------------------------------------------------------|--------------|
| AppDown                | `up{job="sensor-ingestion-platform"} == 0` for 1 minute                 | Check the `app` container with `docker compose ps app` and `docker compose logs app`. |
| AppTargetMissing       | The job has no `up` series for 5 minutes                                | Check the `app:8081` target in `prometheus.yml` and Prometheus logs. |
| OutboxBacklog          | More than 1000 pending outbox rows for 10 minutes                       | Check KafkaPublishFailures. Read `GET /api/v1/admin/outbox/stats`. |
| OutboxFailedEvents     | `outbox_failed_events` above 0 for 1 minute                             | List `status=FAILED` rows, read `lastError`, fix the cause, then requeue. |
| KafkaPublishFailures   | Publish failures above 0.1 per second for 5 minutes                     | Check `docker compose ps kafka` and broker logs. Read `lastError` on outbox rows. |
| HighRejectionRate      | Over 20% of requests rejected for 10 minutes, with traffic above 0.05/s | Group `ingestion_rejections_total` by `reason` to find the tenant cause. |
| HighIngestionLatency   | p95 of `ingestion_processing_seconds` above 0.5 s for 10 minutes        | Check PostgreSQL locks and connection pool use (pool size is 10). |

## Troubleshooting

### Outbox backlog or FAILED rows

- `GET /api/v1/admin/outbox/stats` returns `pending`, `published`, `failed` and `oldestPendingAgeSeconds`.
- `GET /api/v1/admin/outbox?status=FAILED&limit=100` lists rows with `id`, `attempts` and `lastError`. Maximum limit 500.
- `POST /api/v1/admin/outbox/{id}/requeue` sets the row to `PENDING` with `attempts` 0. It returns `409` for a
  `PUBLISHED` row. Requeue only after the cause is fixed.
- A row becomes `FAILED` after `platform.outbox.max-attempts` (50) failed attempts. Retry delay doubles from
  1 s up to 30 s.

### Kafka unavailable

- Ingestion continues. The reading, idempotency row and outbox row commit in PostgreSQL and the API returns `202`.
  The outbox row stays `PENDING`, with `attempts` and `lastError` updated.
- A publish cycle does not hang. It gives up within `platform.kafka.send-timeout-ms` (12 s).
- Pending rows publish on the next cycle after Kafka recovers. Confirm with `outbox_publish_total{result="success"}`
  rising and `outbox_pending_events` falling.
- A failed topic creation at tenant registration does not fail the request. The publisher creates the topic later.
- List topics: `docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list`.
- Read a topic: `docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic tenant-alpha.readings --from-beginning`.

### Rejected requests

- `ingestion_rejections_total{reason="..."}` counts rejections by error code, for example `PAYLOAD_VALIDATION_FAILED`,
  `DEVICE_NOT_REGISTERED`, `DEVICE_MISMATCH`, `DEVICE_CREDENTIAL_REQUIRED`, `NO_ACTIVE_CONTRACT`, `TRANSFORMATION_FAILED`, `IDEMPOTENCY_KEY_CONFLICT`.
- `ingestion_validation_failures_total` counts payloads that failed contract validation.
- Metrics carry no tenant label. Find the tenant from the client's token, or from the `code` field in the response.

### Logs

- `docker compose logs -f app` and `docker compose logs -f consumer`.
- With profile `docker`, each application log line is one JSON object. Ingestion lines carry `tenantId`,
  `ingestionId` and `eventId` (MDC keys). The consumer also writes single-line JSON.
- The retention job logs `Retention deleted ...` at INFO when it deletes rows and `Retention run failed` at ERROR.

## Retention

- The job starts 60 seconds after startup and then runs every hour (`platform.retention.fixed-delay`).
- It deletes PUBLISHED outbox rows older than 7 days, idempotency rows older than 7 days, and ingestion records
  older than 90 days that no idempotency row references.
- Idempotency rows are deleted first, so their ingestion records can go in the same run.
- Each table is deleted in batches of 1000, at most 100 batches per table per run. A large backlog drains over
  several runs. Counts appear in `retention_deleted_total{table}`.
- `PENDING` and `FAILED` outbox rows are never deleted.
- The application refuses to start if `platform.retention.idempotency` is longer than `ingestion-records`.

## Rollback

- Roll back by redeploying the previous image tag. Compose builds `sensor-ingestion-platform:local`. Use a
  versioned tag in production so the previous image stays available.
- Flyway migrations are forward-only. There is no down migration. `V2` only adds indexes, so rolling back the
  application does not require a schema change. Test the rollback in staging first.
