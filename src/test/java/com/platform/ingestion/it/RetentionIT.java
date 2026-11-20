package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.ingestion.observability.PlatformMetrics;
import com.platform.ingestion.retention.RetentionJob;
import com.platform.ingestion.retention.RetentionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** Retention purge against a real database, with data created through the API and then aged. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionIT extends AbstractIntegrationTest {

    private static final String TENANT = "tenant-retention";

    @Autowired
    private PlatformMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    private RetentionJob job(int batchSize, int maxBatches) {
        return new RetentionJob(
                jdbc,
                tx,
                metrics,
                Clock.systemUTC(),
                new RetentionProperties(
                        true,
                        Duration.ofHours(1),
                        Duration.ofDays(7),
                        Duration.ofDays(7),
                        Duration.ofDays(90),
                        batchSize,
                        maxBatches));
    }

    @BeforeAll
    void setUpTenant() throws Exception {
        registerTenant(TENANT);
        registerDevice(TENANT, "MACHINE-001");
        MvcResult upload = uploadContract(
                        TENANT,
                        tenantAdmin(TENANT),
                        "retention-readings",
                        demoFile("tenant-alpha/openapi.yaml"),
                        demoFile("tenant-alpha/mapping.json"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode created = body(upload);
        activate(
                        TENANT,
                        tenantAdmin(TENANT),
                        created.get("contractId").asText(),
                        created.get("version").asInt())
                .andExpect(status().isOk());
    }

    private static String payload() {
        return new String(demoFile("payloads/alpha-valid.json"), StandardCharsets.UTF_8);
    }

    private UUID ingestAccepted(String key) throws Exception {
        MvcResult r = ingest(tenantIngest(TENANT), key, payload())
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(body(r).get("eventId").asText());
    }

    /**
     * Ages one ingestion: its record, its idempotency key and its outbox row (marked PUBLISHED) are moved into the
     * past by the given number of days.
     */
    private void age(UUID eventId, int recordDays, int idempotencyDays, int outboxDays) {
        jdbc.update(
                "UPDATE platform.ingestion_records SET received_at = now() - make_interval(days => ?) "
                        + "WHERE event_id = ?",
                recordDays,
                eventId);
        jdbc.update(
                "UPDATE platform.idempotency_records SET created_at = now() - make_interval(days => ?) "
                        + "WHERE ingestion_id = (SELECT id FROM platform.ingestion_records WHERE event_id = ?)",
                idempotencyDays,
                eventId);
        jdbc.update(
                "UPDATE platform.outbox_events SET status = 'PUBLISHED', "
                        + "published_at = now() - make_interval(days => ?) WHERE event_id = ?",
                outboxDays,
                eventId);
    }

    private boolean exists(String table, UUID eventId) {
        String sql =
                switch (table) {
                    case "ingestion_records" -> "SELECT count(*) FROM platform.ingestion_records WHERE event_id = ?";
                    case "outbox_events" -> "SELECT count(*) FROM platform.outbox_events WHERE event_id = ?";
                    default -> "SELECT count(*) FROM platform.idempotency_records WHERE ingestion_id = "
                            + "(SELECT id FROM platform.ingestion_records WHERE event_id = ?)";
                };
        Integer count = jdbc.queryForObject(sql, Integer.class, eventId);
        return count != null && count > 0;
    }

    private double deleted(String table) {
        return registry.get("retention.deleted").tag("table", table).counter().count();
    }

    @Test
    void purgesExpiredRowsPerTableAndNeverTouchesOpenOutboxWork() throws Exception {
        drainOutbox();
        UUID expired = ingestAccepted(uniqueKey());
        UUID keyExpiredOnly = ingestAccepted(uniqueKey());
        UUID fresh = ingestAccepted(uniqueKey());
        UUID stillReferenced = ingestAccepted(uniqueKey());
        age(expired, 100, 100, 100);
        age(keyExpiredOnly, 10, 10, 10);
        age(fresh, 0, 0, 0);
        // old record whose idempotency key is still inside its window must stay (foreign key and key reuse)
        age(stillReferenced, 100, 1, 1);

        // old unfinished outbox work must survive however old it is
        UUID pending = newOutboxRow(TENANT, "MACHINE-001", Instant.now().minus(Duration.ofDays(100)));
        UUID failed = newOutboxRow(TENANT, "MACHINE-001", Instant.now().minus(Duration.ofDays(100)));
        jdbc.update("UPDATE platform.outbox_events SET status = 'FAILED', attempts = 50 WHERE event_id = ?", failed);

        double outboxBefore = deleted("outbox_events");
        double idempotencyBefore = deleted("idempotency_records");
        double ingestionBefore = deleted("ingestion_records");

        job(1000, 10).purge();

        assertThat(exists("outbox_events", expired)).isFalse();
        assertThat(exists("idempotency_records", expired)).isFalse();
        assertThat(exists("ingestion_records", expired)).isFalse();

        assertThat(exists("outbox_events", keyExpiredOnly)).isFalse();
        assertThat(exists("idempotency_records", keyExpiredOnly)).isFalse();
        assertThat(exists("ingestion_records", keyExpiredOnly)).isTrue();

        assertThat(exists("outbox_events", fresh)).isTrue();
        assertThat(exists("idempotency_records", fresh)).isTrue();
        assertThat(exists("ingestion_records", fresh)).isTrue();

        assertThat(exists("ingestion_records", stillReferenced)).isTrue();
        assertThat(exists("idempotency_records", stillReferenced)).isTrue();

        assertThat(exists("outbox_events", pending)).isTrue();
        assertThat(exists("outbox_events", failed)).isTrue();

        // expired + keyExpiredOnly outbox rows, expired + keyExpiredOnly keys, expired record
        assertThat(deleted("outbox_events") - outboxBefore).isEqualTo(2);
        assertThat(deleted("idempotency_records") - idempotencyBefore).isEqualTo(2);
        assertThat(deleted("ingestion_records") - ingestionBefore).isEqualTo(1);
    }

    @Test
    void deletesInBoundedBatchesAcrossRuns() throws Exception {
        drainOutbox();
        List<UUID> old = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID eventId = newOutboxRow(TENANT, "MACHINE-001", Instant.now().minus(Duration.ofDays(20)));
            jdbc.update(
                    "UPDATE platform.outbox_events SET status = 'PUBLISHED', published_at = now() - interval '20 days' "
                            + "WHERE event_id = ?",
                    eventId);
            old.add(eventId);
        }

        // batch size 2, two batches per run: at most 4 rows go in the first run
        job(2, 2).purge();
        assertThat(old.stream().filter(e -> exists("outbox_events", e)).count()).isEqualTo(1);

        job(2, 2).purge();
        assertThat(old.stream().filter(e -> exists("outbox_events", e)).count()).isZero();
    }

    @Test
    void reusedIdempotencyKeyIsProcessedAsNewOnceItsRecordWasPurged() throws Exception {
        drainOutbox();
        String key = uniqueKey();
        UUID first = ingestAccepted(key);

        ingest(tenantIngest(TENANT), key, payload())
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        age(first, 10, 10, 10);
        job(1000, 10).purge();

        UUID second = ingestAccepted(key);
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.idempotency_records WHERE tenant_id = "
                                + "(SELECT id FROM platform.tenants WHERE tenant_code = ?) AND idempotency_key = ?",
                        Integer.class,
                        TENANT,
                        key))
                .isEqualTo(1);
    }
}
