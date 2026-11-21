# Operations

## Deployment requirements

- Run with `SPRING_PROFILES_ACTIVE=prod`. The variables without defaults in this profile are listed in the README
  (Configuration); startup fails naming any that is missing. Tenant-wide ingestion tokens are refused in this
  profile (`platform.security.tenant-ingest-credentials-enabled=false`), so every device needs its own credential.
  Tracing samples 10% (`TRACING_SAMPLING_PROBABILITY=0.1`) unless overridden.
- Database roles: the DBA creates the application role (`DB_USER`) before the first start:
  `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`, not an owner. `DB_MIGRATION_USER` should be
  a non-superuser role that owns the schema objects. `V3` fails if the application role does not exist, grants it the
  table privileges and `CREATE` on the database (tenant schemas `ts_*` are created at runtime). Flyway placeholder
  `app_role` is set from `DB_USER`.
- Redis: required by the app (rate limiting) and the consumer (dedup). Use a password and keep it off public
  networks. Redis health is not part of `/actuator/health` by design. Compose runs `redis:7.4.12-alpine` with AOF,
  `requirepass`, `maxmemory 96mb`, `maxmemory-policy volatile-ttl`, `mem_limit` 128m and no host port.
  Sizing: a dedup key costs about 150 B (measured 153) and lives `DEDUP_TTL_SECONDS` (7 days), so about 90 MB per
  sustained event/s; 96 MB holds about 640k keys. Raise `maxmemory` and `mem_limit` together, keeping `maxmemory`
  about 25% below `mem_limit`. Under pressure `volatile-ttl` evicts rate limit keys first, then the oldest dedup keys
  (those events can be processed again if redelivered).
- Tracing: compose adds `otel-collector` (no host ports; OTLP on 4317 and 4318 inside the network) and `tempo`
  (`127.0.0.1:${TEMPO_HOST_PORT:-3200}`, 24 h retention, volume `tempo-data`). Neither has a healthcheck, and the app
  does not wait for them: a missing collector drops spans, not requests. Grafana has a Tempo datasource. Outside
  compose, set `OTEL_EXPORTER_OTLP_ENDPOINT` and `TRACING_EXPORT_ENABLED=true` on the app, and
  `OTEL_EXPORTER_OTLP_ENDPOINT` on the consumer (it exports only when set).
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
  with them `manage-realm`. The realm user profile makes `tenant_id` and `device_id` admin-only, and
  `unmanagedAttributePolicy` is `ADMIN_EDIT` realm-wide. A device token is not accepted by the account or admin API.
  The realm export already contains all of it; an existing realm needs the same client and scope added by hand
  because the import only runs on an empty realm. The provisioner client has no API audience and no API role, so its
  token is useless against the platform API, but treat `PROVISIONER_CLIENT_SECRET` as realm-admin-equivalent: keep it
  in a secret store, rotate it, and restrict network access to the Keycloak admin API to the app.
- Device token lifetime: after a credential rotation or disable, access tokens already issued stay valid until they
  expire (`accessTokenLifespan` 900 s in the demo realm). Deactivating the device in the platform takes effect at
  once.
- Secrets: `DB_PASSWORD`, `DB_APP_PASSWORD`, `REDIS_PASSWORD`, client secrets, `PROVISIONER_CLIENT_SECRET`, `KEYCLOAK_ADMIN_PASSWORD`, `GRAFANA_ADMIN_PASSWORD` and
  `DEMO_USER_PASSWORD` come from a secret store, not from a committed `.env`. Compose refuses to start when a
  required variable is unset (`:?` syntax in `docker-compose.yml`).
- The image runs as user `app`. JVM options go in `JAVA_OPTS`. The Docker healthcheck reads `/actuator/health`
  on port 8081.

## Database migrations

- Flyway runs on startup against schema `platform` (`spring.flyway.*` in `application.yml`) as `DB_MIGRATION_USER`.
  `V1` creates the base schema, `V2` adds retention indexes, `V3` enables row-level security and grants, `V4` adds
  `devices.credential_client_id`, `V5` adds `outbox_events.trace_parent`.
- Application connections have `statement_timeout` 15 s and `lock_timeout` 5 s (`DB_STATEMENT_TIMEOUT_MS`,
  `DB_LOCK_TIMEOUT_MS`). Flyway connections do not get these. `V2` sets `SET LOCAL statement_timeout = 0` itself.
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
| RateLimiterDegraded    | `ratelimit_errors_total` rate above 0 for 2 minutes                     | Limits are not enforced. See "Rate limiter degraded". |

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

### Rate limiter degraded

- Alert `RateLimiterDegraded`: Redis commands from the limiter are failing. Ingestion continues but is not limited
  (fail open), so a flooding tenant or device is not stopped until this clears.
- `ratelimit_errors_total` counts real Redis failures; `ratelimit_skipped_total` counts checks skipped during the
  2 s cooldown or before the first connect. The app logs `Rate limiter store unavailable` at WARN, at most every 30 s,
  with the cause.
- Check Redis: `docker compose ps redis`, then `docker compose exec redis redis-cli ping` (the container has
  `REDISCLI_AUTH`). A `NOAUTH` or authentication error in the app log means `REDIS_PASSWORD` on the app differs from
  the server's `requirepass`.
- Check memory: `docker compose exec redis redis-cli info memory` and `info stats` (`evicted_keys`). `maxmemory` is 96mb.
- The limiter probes Redis again after each cooldown. The alert clears about 5 minutes after the last error
  (rate window) plus the 2 minute `for`.
- `ingestion_rejections_total{reason="RATE_LIMITED"}` counts rejected requests. Limits are per tenant code and
  device id; adjust with `platform.rate-limit.*` (changes apply after the bucket key expires).

### Consumer stuck retrying

- Symptom: `docker compose logs consumer` repeats `processing failed; offset not committed, will retry` about every
  3 s with the same `topic`, `partition` and `offset`, and the consumer group lag grows:
  `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group sensor-readings-consumer`.
- Cause is Redis: down, unreachable, or a wrong `REDIS_PASSWORD` on the consumer (the `error` field shows the
  exception). The consumer commits nothing while Redis fails; it resumes from the same offset without loss once Redis
  answers, with no restart.
- Check with `docker compose ps redis` and `docker compose exec redis redis-cli ping`. If the password was changed,
  update `REDIS_PASSWORD` for the app and the consumer, then `docker compose up -d redis app consumer`.
- Records with an `event_id` that is not a UUID or with missing fields are logged as `invalid message` and skipped,
  not retried.
- Dedup keys are `dedup:<event_id>`. A duplicate is logged as `duplicate event skipped`.

### Rejected requests

- `ingestion_rejections_total{reason="..."}` counts rejections by error code, for example `PAYLOAD_VALIDATION_FAILED`,
  `DEVICE_NOT_REGISTERED`, `DEVICE_MISMATCH`, `DEVICE_CREDENTIAL_REQUIRED`, `RATE_LIMITED`, `NO_ACTIVE_CONTRACT`, `TRANSFORMATION_FAILED`, `IDEMPOTENCY_KEY_CONFLICT`.
- `ingestion_validation_failures_total` counts payloads that failed contract validation.
- Metrics carry no tenant label. Find the tenant from the client's token, or from the `tenantId` log field
  (the response carries `errorCode`, not the tenant).

### Logs

- `docker compose logs -f app` and `docker compose logs -f consumer`.
- With profile `docker`, each application log line is one JSON object. Ingestion lines carry `tenantId`,
  `ingestionId` and `eventId` (MDC keys), plus `traceId` and `spanId`. The consumer also writes single-line JSON
  with `trace_id` and `span_id`. To follow one request, take the `traceId` and open it in Grafana (Tempo) or run
  `curl http://localhost:3200/api/traces/<traceId>`. Traces expire after 24 h in compose.
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
- Flyway migrations are forward-only. There is no down migration. `V2`, `V4` and `V5` only add indexes or nullable
  columns, so rolling back the application needs no schema change. `V3` enables row-level security: an image from
  before `V3` sets no tenant scope, so it must connect as the owner role (tables do not use `FORCE ROW LEVEL
  SECURITY`, so the owner is not filtered). Test the rollback in staging first.

## Upgrading an existing local stack

For a compose stack created before row-level security, device credentials, rate limiting, tracing and shared dedup.

1. Add to `.env` (see `.env.example`): `DB_APP_PASSWORD`, `PROVISIONER_CLIENT_SECRET`, `REDIS_PASSWORD`.
2. Create the application role before starting the new app version (an existing volume skips
   `infra/postgres/init/10-app-role.sh`; a fresh volume gets the role from it):
   ```
   docker compose exec postgres psql -U sensor -d sensor_platform -c "CREATE ROLE sensor_app LOGIN PASSWORD '<DB_APP_PASSWORD>' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;"
   ```
3. `docker compose up -d --force-recreate keycloak` so the realm is imported again with the provisioner client and the
   `device-identity` scope.
4. `docker compose up -d --build`. Flyway applies `V3` to `V5`. Scale the consumer with `--scale consumer=N` if wanted.
5. Issue credentials for existing devices: `POST /api/v1/tenants/{tenantId}/devices/{deviceId}/credentials`.
6. Optional: `docker volume rm sensor-platform_consumer-data`. The old sqlite dedup file is no longer used; dedup state
   starts empty in Redis.
