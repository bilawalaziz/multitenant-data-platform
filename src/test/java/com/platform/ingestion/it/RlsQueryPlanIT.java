package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.ingestion.tenant.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Row-level security must not cost the hot queries their indexes. The policy is
 * {@code tenant_id = current_tenant() OR rls_system()}; these tests read the plans the planner picks for the
 * application role on realistically sized, analysed tables. Planner settings are deliberately left alone: forcing a
 * plan would prove nothing about what production chooses.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RlsQueryPlanIT extends AbstractIntegrationTest {

    private static final String BIG = "tenant-plan-big";
    private static final String MID = "tenant-plan-mid";
    private static final String SMALL = "tenant-plan-small";

    private static final int BIG_ROWS = 3500;
    private static final int MID_ROWS = 1000;
    private static final int SMALL_ROWS = 500;
    private static final int OUTBOX_ROWS = 6000;
    private static final int OUTBOX_PENDING = 300;

    private static final String LIST_SQL =
            "SELECT * FROM platform.ingestion_records WHERE tenant_id = ? " + "ORDER BY received_at DESC LIMIT 50";
    private static final String LIST_BY_DEVICE_SQL = "SELECT * FROM platform.ingestion_records "
            + "WHERE tenant_id = ? AND device_id = ? ORDER BY received_at DESC LIMIT 50";
    // Same statement as OutboxRepository.claimDue; EXPLAIN of a locking select is accepted by PostgreSQL.
    private static final String CLAIM_SQL = "SELECT * FROM platform.outbox_events "
            + "WHERE status = 'PENDING' AND next_attempt_at <= ? "
            + "ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED";

    private static final String INDEX_TENANT_RECEIVED = "idx_ingestion_records_tenant_received";
    private static final String INDEX_OUTBOX_STATUS = "idx_outbox_status_next_attempt";

    private final List<UUID> tenantIds = new ArrayList<>();
    private UUID big;
    private UUID small;

    @BeforeAll
    void seed() throws Exception {
        for (String code : List.of(BIG, MID, SMALL)) {
            registerTenant(code);
            tenantIds.add(
                    jdbc.queryForObject("SELECT id FROM platform.tenants WHERE tenant_code = ?", UUID.class, code));
        }
        big = tenantIds.get(0);
        small = tenantIds.get(2);
        int[] rows = {BIG_ROWS, MID_ROWS, SMALL_ROWS};
        for (int i = 0; i < tenantIds.size(); i++) {
            seedIngestion(tenantIds.get(i), rows[i]);
        }
        seedOutbox(big);
        jdbc.execute("ANALYZE platform.ingestion_records");
        jdbc.execute("ANALYZE platform.outbox_events");
    }

    /** Owner inserts, so the fixture does not depend on the policies under test. */
    private void seedIngestion(UUID tenantId, int rows) {
        UUID contractId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform.contracts (id, tenant_id, name, created_at) VALUES (?, ?, 'plan', now())",
                contractId,
                tenantId);
        jdbc.update(
                "INSERT INTO platform.contract_versions (id, contract_id, tenant_id, version, spec_format, "
                        + "spec_content, checksum_sha256, ingestion_path, request_schema, status, created_at) "
                        + "VALUES (?, ?, ?, 1, 'JSON', '{}', repeat('0', 64), '/plan', '{}'::jsonb, 'INACTIVE', now())",
                versionId,
                contractId,
                tenantId);
        jdbc.update(
                "INSERT INTO platform.ingestion_records (id, tenant_id, event_id, idempotency_key, device_id, "
                        + "contract_version_id, raw_payload, canonical_event, status, received_at) "
                        + "SELECT gen_random_uuid(), ?, gen_random_uuid(), 'plan-' || g, 'DEV-' || (g % 10), ?, "
                        + "jsonb_build_object('temperature', g, 'note', repeat('x', 100)), "
                        + "jsonb_build_object('device_id', 'DEV-' || (g % 10), 'value', g), "
                        + "'ACCEPTED', now() - (g || ' minutes')::interval "
                        + "FROM generate_series(1, ?) g",
                tenantId, versionId, rows);
    }

    private void seedOutbox(UUID tenantId) {
        jdbc.update(
                "INSERT INTO platform.outbox_events (id, tenant_id, event_id, topic, message_key, payload, status, "
                        + "attempts, next_attempt_at, created_at, published_at) "
                        + "SELECT gen_random_uuid(), ?, gen_random_uuid(), 'plan.readings', 'DEV-' || (g % 10), "
                        + "jsonb_build_object('value', g, 'note', repeat('x', 100)), "
                        + "CASE WHEN g <= ? THEN 'PENDING' ELSE 'PUBLISHED' END, 0, "
                        + "now() - interval '1 hour', now() - (g || ' seconds')::interval, "
                        + "CASE WHEN g <= ? THEN NULL ELSE now() END "
                        + "FROM generate_series(1, ?) g",
                tenantId, OUTBOX_PENDING, OUTBOX_PENDING, OUTBOX_ROWS);
    }

    @AfterAll
    void cleanUp() {
        for (UUID id : tenantIds) {
            jdbc.update("DELETE FROM platform.outbox_events WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM platform.ingestion_records WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM platform.contract_mappings WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM platform.contract_versions WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM platform.contracts WHERE tenant_id = ?", id);
        }
        jdbc.execute("ANALYZE platform.ingestion_records");
        jdbc.execute("ANALYZE platform.outbox_events");
    }

    @Test
    void seededVolumeIsAsPlanned() {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.ingestion_records WHERE tenant_id IN (?, ?, ?)",
                        Integer.class,
                        tenantIds.toArray()))
                .isEqualTo(BIG_ROWS + MID_ROWS + SMALL_ROWS);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_events WHERE status = 'PENDING' AND tenant_id = ?",
                        Integer.class,
                        big))
                .isEqualTo(OUTBOX_PENDING);
    }

    @Test
    void tenantListUsesTheTenantIndexUnderRlsForSkewedAndSmallTenants() throws Exception {
        for (UUID tenant : List.of(big, small)) {
            JsonNode plan;
            try (TenantScope scope = TenantScope.tenant(tenant)) {
                plan = explain(LIST_SQL, tenant);
            }
            assertUsesIndexWithoutSeqScan(plan, "ingestion_records", INDEX_TENANT_RECEIVED);
        }
    }

    @Test
    void deviceFilteredListUsesTheTenantIndexUnderRls() throws Exception {
        JsonNode plan;
        try (TenantScope scope = TenantScope.tenant(big)) {
            plan = explain(LIST_BY_DEVICE_SQL, big, "DEV-3");
        }
        assertUsesIndexWithoutSeqScan(plan, "ingestion_records", INDEX_TENANT_RECEIVED);
    }

    @Test
    void outboxClaimUsesTheStatusIndexUnderSystemScope() throws Exception {
        JsonNode plan;
        try (TenantScope scope = TenantScope.system()) {
            plan = explain(CLAIM_SQL, Timestamp.from(Instant.now()));
        }
        assertUsesIndexWithoutSeqScan(plan, "outbox_events", INDEX_OUTBOX_STATUS);
    }

    private JsonNode explain(String sql, Object... args) throws Exception {
        String json = appJdbc.queryForObject("EXPLAIN (FORMAT JSON) " + sql, String.class, args);
        return mapper.readTree(json).get(0).get("Plan");
    }

    private static void assertUsesIndexWithoutSeqScan(JsonNode plan, String relation, String index) {
        List<String> nodes = new ArrayList<>();
        collect(plan, nodes);
        String summary = String.join("\n", nodes);
        assertThat(nodes).as("plan:\n%s", summary).anyMatch(n -> n.contains("index=" + index));
        assertThat(nodes)
                .as("plan:\n%s", summary)
                .noneMatch(n -> n.startsWith("Seq Scan") && n.contains("relation=" + relation));
    }

    /** One line per plan node: type, relation and index, so a failure prints the whole plan. */
    private static void collect(JsonNode node, List<String> out) {
        out.add(node.get("Node Type").asText()
                + " relation=" + node.path("Relation Name").asText("-")
                + " index=" + node.path("Index Name").asText("-"));
        for (JsonNode child : node.path("Plans")) {
            collect(child, out);
        }
    }
}
