package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionFlowIT extends AbstractIntegrationTest {

    private static final String ALPHA = "tenant-alpha";
    private static final String BETA = "tenant-beta";

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private Tracer tracer;

    @Autowired
    private TestSupportConfig.CapturedSpans capturedSpans;

    private String alphaContractId;
    private String betaContractId;

    @BeforeAll
    void setUpTenants() throws Exception {
        registerTenant(ALPHA);
        registerTenant(BETA);
        registerDevice(ALPHA, "MACHINE-001");
        registerDevice(BETA, "MACHINE-002");
        // The exact demo files must upload, activate and transform.
        alphaContractId = uploadAndActivateDemo(ALPHA, "alpha-readings");
        betaContractId = uploadAndActivateDemo(BETA, "beta-readings");
    }

    private static String demoPayload(String name) {
        return new String(demoFile("payloads/" + name), StandardCharsets.UTF_8);
    }

    private JsonNode accepted(String token, String payload) throws Exception {
        MvcResult r = ingest(token, uniqueKey(), payload)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();
        return body(r);
    }

    private List<ConsumerRecord<String, String>> consume(String topic, int expectedAtLeast, String eventId) {
        return consumeUntil(topic, r -> r.value().contains(eventId));
    }

    private List<ConsumerRecord<String, String>> consumeUntil(
            String topic, Predicate<ConsumerRecord<String, String>> stop) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(found::add);
                if (found.stream().anyMatch(stop)) {
                    break;
                }
            }
        }
        return found;
    }

    private ConsumerRecord<String, String> recordFor(String topic, String eventId) {
        List<ConsumerRecord<String, String>> records = consume(topic, 1, eventId);
        return records.stream()
                .filter(r -> r.value().contains(eventId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("event " + eventId + " not found on " + topic));
    }

    @Test
    void twoTenantsWithDifferentShapesProduceTheSameCanonicalStructureOnTheirOwnTopics() throws Exception {
        drainOutbox();
        JsonNode a = accepted(tenantIngest(ALPHA), demoPayload("alpha-valid.json"));
        JsonNode b = accepted(tenantIngest(BETA), demoPayload("beta-valid.json"));
        assertThat(a.get("tenantId").asText()).isEqualTo(ALPHA);
        assertThat(b.get("tenantId").asText()).isEqualTo(BETA);
        assertThat(a.get("contractId").asText()).isEqualTo(alphaContractId);
        assertThat(b.get("contractId").asText()).isEqualTo(betaContractId);

        assertThat(outboxPublisher.publishDue()).isGreaterThanOrEqualTo(2);

        ConsumerRecord<String, String> alphaRecord =
                recordFor("tenant-alpha.readings", a.get("eventId").asText());
        ConsumerRecord<String, String> betaRecord =
                recordFor("tenant-beta.readings", b.get("eventId").asText());

        assertThat(alphaRecord.key()).isEqualTo("MACHINE-001");
        assertThat(betaRecord.key()).isEqualTo("MACHINE-002");
        assertThat(new String(alphaRecord.headers().lastHeader("tenant_id").value()))
                .isEqualTo(ALPHA);
        assertThat(new String(alphaRecord.headers().lastHeader("event_id").value()))
                .isEqualTo(a.get("eventId").asText());
        assertThat(new String(alphaRecord.headers().lastHeader("schema_version").value()))
                .isEqualTo("1.0");
        assertThat(new String(betaRecord.headers().lastHeader("tenant_id").value()))
                .isEqualTo(BETA);

        JsonNode alphaEvent = mapper.readTree(alphaRecord.value());
        JsonNode betaEvent = mapper.readTree(betaRecord.value());
        assertThat(fieldNames(alphaEvent))
                .isEqualTo(fieldNames(betaEvent))
                .containsExactlyInAnyOrder(
                        "event_id",
                        "schema_version",
                        "tenant_id",
                        "ingestion_id",
                        "contract_id",
                        "contract_version",
                        "device_id",
                        "sensor_id",
                        "value",
                        "unit",
                        "event_timestamp",
                        "received_at",
                        "metadata");
        assertThat(alphaEvent.get("tenant_id").asText()).isEqualTo(ALPHA);
        assertThat(alphaEvent.get("ingestion_id").asText())
                .isEqualTo(a.get("ingestionId").asText());
        assertThat(alphaEvent.get("value").decimalValue()).isEqualByComparingTo("92.5");
        assertThat(alphaEvent.get("unit").asText()).isEqualTo("CELSIUS");
        assertThat(alphaEvent.get("event_timestamp").asText()).isEqualTo("2026-11-17T14:30:00Z");
        assertThat(alphaEvent.at("/metadata/site").asText()).isEqualTo("plant-1");
        assertThat(betaEvent.get("value").decimalValue()).isEqualByComparingTo("198.5");
        assertThat(betaEvent.get("unit").asText()).isEqualTo("FAHRENHEIT");
        assertThat(betaEvent.at("/metadata/firmware").asText()).isEqualTo("2.4.1");
        assertThat(betaEvent.at("/metadata/battery_pct").decimalValue()).isEqualByComparingTo("87");

        // published rows are marked, and ingestion detail exposes the canonical event
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM platform.outbox_events WHERE event_id = ?::uuid",
                        String.class,
                        a.get("eventId").asText()))
                .isEqualTo("PUBLISHED");
        getAs(
                        tenantIngest(ALPHA),
                        "/api/v1/tenants/" + ALPHA + "/ingestions/"
                                + a.get("ingestionId").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalEvent.device_id").value("MACHINE-001"))
                .andExpect(jsonPath("$.rawPayload.deviceId").value("MACHINE-001"));
        getAs(tenantAdmin(ALPHA), "/api/v1/tenants/" + ALPHA + "/ingestions?limit=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ingestionId").exists());
    }

    private static List<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(((Iterable<String>) node::fieldNames).spliterator(), false)
                .sorted()
                .collect(Collectors.toList());
    }

    @Test
    void invalidPayloadsReturnStructuredValidationErrors() throws Exception {
        ingest(tenantIngest(ALPHA), uniqueKey(), demoPayload("alpha-invalid.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_VALIDATION_FAILED"))
                .andExpect(
                        jsonPath("$.errors[?(@.field=='temperature')].message").value("Expected numeric value"))
                .andExpect(jsonPath("$.errors[?(@.field=='sensorId')].message").value("Missing required field"))
                .andExpect(jsonPath("$.errors[?(@.field=='unit')]").exists());
        ingest(tenantIngest(BETA), uniqueKey(), demoPayload("beta-invalid.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='reading')].message").value("Expected numeric value"))
                .andExpect(jsonPath("$.errors[?(@.field=='timestamp')]").exists());
        // nothing is persisted for rejected requests
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.ingestion_records WHERE raw_payload->>'temperature' = 'hot'",
                        Integer.class))
                .isZero();
    }

    @Test
    void unknownDeviceAndMissingContractAreUnprocessable() throws Exception {
        ingest(tenantIngest(ALPHA), uniqueKey(), demoPayload("alpha-unknown-device.json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DEVICE_NOT_REGISTERED"));
        // a device of the other tenant is not registered for this one either
        ingest(tenantIngest(ALPHA), uniqueKey(), demoPayload("alpha-valid.json").replace("MACHINE-001", "MACHINE-002"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DEVICE_NOT_REGISTERED"));

        registerTenant("tenant-empty");
        ingest(tenantIngest("tenant-empty"), uniqueKey(), demoPayload("alpha-valid.json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NO_ACTIVE_CONTRACT"));
        ingest(tenantIngest("tenant-ghost"), uniqueKey(), demoPayload("alpha-valid.json"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_REGISTERED"));
    }

    @Test
    void malformedAndMissingHeaderRequests() throws Exception {
        for (String body : new String[] {"{ not json", "{\"a\":1} trailing", ""}) {
            ingest(tenantIngest(ALPHA), uniqueKey(), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        }
        mvc.perform(post("/api/v1/ingestion/readings")
                        .header("Authorization", bearer(tenantIngest(ALPHA)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demoPayload("alpha-valid.json")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_IDEMPOTENCY_KEY"));
        ingest(tenantIngest(ALPHA), "bad key!", demoPayload("alpha-valid.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        ingest(tenantIngest(ALPHA), uniqueKey(), demoPayload("alpha-valid.json").replace("92.5", "1e999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Numeric values must be finite"));
        String huge = "{\"pad\":\"" + "x".repeat(70 * 1024) + "\"}";
        ingest(tenantIngest(ALPHA), uniqueKey(), huge)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void idempotencyKeyReplayConflictAndTenantScope() throws Exception {
        String key = uniqueKey();
        String payload = demoPayload("alpha-valid.json");
        JsonNode first = body(ingest(tenantIngest(ALPHA), key, payload)
                .andExpect(status().isAccepted())
                .andReturn());

        // same content, different formatting and key order: still the same request
        String reordered = mapper.writeValueAsString(mapper.readTree(payload).deepCopy());
        MvcResult replay = ingest(tenantIngest(ALPHA), key, reordered)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andReturn();
        JsonNode second = body(replay);
        assertThat(second.get("ingestionId").asText())
                .isEqualTo(first.get("ingestionId").asText());
        assertThat(second.get("eventId").asText())
                .isEqualTo(first.get("eventId").asText());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.ingestion_records WHERE idempotency_key = ?",
                        Integer.class,
                        key))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.outbox_events WHERE event_id = ?::uuid",
                        Integer.class,
                        first.get("eventId").asText()))
                .isEqualTo(1);

        String conflictKey = uniqueKey();
        ingest(tenantIngest(ALPHA), conflictKey, demoPayload("alpha-valid.json"))
                .andExpect(status().isAccepted());
        ingest(tenantIngest(ALPHA), conflictKey, demoPayload("alpha-valid.json").replace("92.5", "93.5"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.ingestion_records WHERE idempotency_key = ?",
                        Integer.class,
                        conflictKey))
                .isEqualTo(1);

        String sharedKey = uniqueKey();
        ingest(tenantIngest(ALPHA), sharedKey, demoPayload("alpha-valid.json")).andExpect(status().isAccepted());
        ingest(tenantIngest(BETA), sharedKey, demoPayload("beta-valid.json")).andExpect(status().isAccepted());
    }

    @Test
    void tenantIsolation() throws Exception {
        JsonNode alpha = accepted(tenantIngest(ALPHA), demoPayload("alpha-valid.json"));
        JsonNode beta = accepted(tenantIngest(BETA), demoPayload("beta-valid.json"));
        String betaIngestionId = beta.get("ingestionId").asText();

        for (String token : List.of(tenantAdmin(ALPHA), tenantIngest(ALPHA))) {
            getAs(token, "/api/v1/tenants/" + BETA + "/ingestions")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
            getAs(token, "/api/v1/tenants/" + BETA + "/ingestions/" + betaIngestionId)
                    .andExpect(status().isForbidden());
        }
        getAs(tenantAdmin(ALPHA), "/api/v1/tenants/" + BETA).andExpect(status().isForbidden());
        getAs(tenantAdmin(ALPHA), "/api/v1/tenants/" + BETA + "/devices").andExpect(status().isForbidden());
        getAs(tenantAdmin(ALPHA), "/api/v1/tenants/" + BETA + "/contracts").andExpect(status().isForbidden());
        getAs(tenantAdmin(ALPHA), "/api/v1/tenants/" + BETA + "/settings").andExpect(status().isForbidden());

        // the own tenant path with another tenant's ingestion id gives 404, never the data
        getAs(tenantAdmin(ALPHA), "/api/v1/tenants/" + ALPHA + "/ingestions/" + betaIngestionId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
        getAs(PLATFORM_ADMIN, "/api/v1/tenants/" + ALPHA + "/ingestions/" + betaIngestionId)
                .andExpect(status().isNotFound());

        // own lists only contain own data
        String alphaDevices = getAs(tenantAdmin(ALPHA), "/api/v1/tenants/" + ALPHA + "/devices")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(alphaDevices).contains("MACHINE-001").doesNotContain("MACHINE-002");
        String alphaContracts = getAs(tenantAdmin(ALPHA), "/api/v1/tenants/" + ALPHA + "/contracts")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(alphaContracts).contains("alpha-readings").doesNotContain("beta-readings");
        String alphaIngestions = getAs(tenantIngest(ALPHA), "/api/v1/tenants/" + ALPHA + "/ingestions?limit=200")
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(alphaIngestions).contains(alpha.get("ingestionId").asText()).doesNotContain(betaIngestionId);
        // alpha cannot get beta's contract by id through its own path
        getAs(tenantAdmin(ALPHA), "/api/v1/tenants/" + ALPHA + "/contracts/" + betaContractId)
                .andExpect(status().isNotFound());

        // platform admin may read any tenant
        getAs(PLATFORM_ADMIN, "/api/v1/tenants/" + BETA + "/ingestions/" + betaIngestionId)
                .andExpect(status().isOk());
        // roles: ingest may neither register devices nor manage contracts; tenant admin cannot ingest
        mvc.perform(post("/api/v1/tenants/" + ALPHA + "/devices")
                        .header("Authorization", bearer(tenantIngest(ALPHA)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"MACHINE-777\"}"))
                .andExpect(status().isForbidden());
        getAs(tenantIngest(ALPHA), "/api/v1/tenants/" + ALPHA + "/contracts").andExpect(status().isForbidden());
        ingest(tenantAdmin(ALPHA), uniqueKey(), demoPayload("alpha-valid.json")).andExpect(status().isForbidden());
        getAs(tenantAdmin(ALPHA), "/api/v1/admin/tenants").andExpect(status().isForbidden());
        getAs(tenantAdmin(ALPHA), "/api/v1/admin/outbox").andExpect(status().isForbidden());
        getAs("no-role", "/api/v1/tenants/" + ALPHA + "/devices").andExpect(status().isForbidden());
    }

    @Test
    void tenantHeaderMismatchIsRejected() throws Exception {
        mvc.perform(post("/api/v1/ingestion/readings")
                        .header("Authorization", bearer(tenantIngest(ALPHA)))
                        .header("Idempotency-Key", uniqueKey())
                        .header("X-Tenant-ID", BETA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demoPayload("alpha-valid.json")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MISMATCH"));
        mvc.perform(get("/api/v1/tenants/" + ALPHA + "/devices")
                        .header("Authorization", bearer(tenantAdmin(ALPHA)))
                        .header("X-Tenant-ID", BETA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MISMATCH"));
        mvc.perform(post("/api/v1/ingestion/readings")
                        .header("Authorization", bearer(tenantIngest(ALPHA)))
                        .header("Idempotency-Key", uniqueKey())
                        .header("X-Tenant-ID", ALPHA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demoPayload("alpha-valid.json")))
                .andExpect(status().isAccepted());
    }

    @Test
    void unauthenticatedAndBadRequestsGetTheStandardErrorBody() throws Exception {
        mvc.perform(post("/api/v1/ingestion/readings")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.errors").isArray());
        mvc.perform(get("/api/v1/tenants/" + ALPHA + "/devices").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/admin/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());

        getAs(PLATFORM_ADMIN, "/api/v1/does-not-exist")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
        getAs(PLATFORM_ADMIN, "/api/v1/tenants/" + ALPHA + "/ingestions/not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        getAs(PLATFORM_ADMIN, "/api/v1/tenants/tenant-nobody").andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", bearer(PLATFORM_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void outboxRetriesAfterFailureRecoversAndFailsAfterMaxAttempts() throws Exception {
        drainOutbox();
        toggling.setFailing(true);
        try {
            JsonNode accepted = accepted(tenantIngest(ALPHA), demoPayload("alpha-valid.json"));
            String eventId = accepted.get("eventId").asText();

            assertThat(outboxPublisher.publishDue()).isZero();
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, attempts, last_error, next_attempt_at > now() AS backed_off "
                            + "FROM platform.outbox_events WHERE event_id = ?::uuid",
                    eventId);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(((Number) row.get("attempts")).intValue()).isGreaterThan(0);
            assertThat((String) row.get("last_error")).contains("simulated broker outage");
            assertThat(row.get("backed_off")).isEqualTo(true);

            // recovery: broker back, backoff elapsed
            toggling.setFailing(false);
            jdbc.update("UPDATE platform.outbox_events SET next_attempt_at = now() WHERE event_id = ?::uuid", eventId);
            assertThat(outboxPublisher.publishDue()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM platform.outbox_events WHERE event_id = ?::uuid",
                            String.class,
                            eventId))
                    .isEqualTo("PUBLISHED");
            assertThat(recordFor("tenant-alpha.readings", eventId).key()).isEqualTo("MACHINE-001");

            // after the maximum number of attempts the row is FAILED instead of backing off again
            toggling.setFailing(true);
            String exhaustedId = accepted(tenantIngest(ALPHA), demoPayload("alpha-valid.json"))
                    .get("eventId")
                    .asText();
            jdbc.update("UPDATE platform.outbox_events SET attempts = 49 WHERE event_id = ?::uuid", exhaustedId);
            outboxPublisher.publishDue();
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM platform.outbox_events WHERE event_id = ?::uuid",
                            String.class,
                            exhaustedId))
                    .isEqualTo("FAILED");
        } finally {
            toggling.setFailing(false);
        }
    }

    @Test
    void outboxAdminEndpointsListStatsAndRequeue() throws Exception {
        drainOutbox();
        JsonNode accepted = accepted(tenantIngest(ALPHA), demoPayload("alpha-valid.json"));
        String eventId = accepted.get("eventId").asText();
        jdbc.update(
                "UPDATE platform.outbox_events SET status='FAILED', attempts=50, last_error='boom' "
                        + "WHERE event_id = ?::uuid",
                eventId);

        MvcResult failed = getAs(PLATFORM_ADMIN, "/api/v1/admin/outbox?status=FAILED")
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = body(failed);
        JsonNode ours = null;
        for (JsonNode n : rows) {
            if (n.get("eventId").asText().equals(eventId)) {
                ours = n;
            }
        }
        assertThat(ours).isNotNull();
        assertThat(ours.get("attempts").asInt()).isEqualTo(50);
        assertThat(ours.get("lastError").asText()).isEqualTo("boom");
        getAs(PLATFORM_ADMIN, "/api/v1/admin/outbox/stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(greaterThanOrEqualTo(1)));

        mvc.perform(post("/api/v1/admin/outbox/" + ours.get("id").asText() + "/requeue")
                        .header("Authorization", bearer(PLATFORM_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
        assertThat(outboxPublisher.publishDue()).isGreaterThanOrEqualTo(1);
        mvc.perform(post("/api/v1/admin/outbox/" + ours.get("id").asText() + "/requeue")
                        .header("Authorization", bearer(PLATFORM_ADMIN)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/outbox/" + UUID.randomUUID() + "/requeue")
                        .header("Authorization", bearer(PLATFORM_ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void apiDocsAreReachableAndIngestionMetricsAreRecorded() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists());

        accepted(tenantIngest(ALPHA), demoPayload("alpha-valid.json"));
        ingest(tenantIngest(ALPHA), uniqueKey(), demoPayload("alpha-invalid.json"))
                .andExpect(status().isBadRequest());
        assertThat(registry.get("ingestion.requests")
                        .tag("outcome", "accepted")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
        assertThat(registry.get("ingestion.rejections")
                        .tag("reason", "PAYLOAD_VALIDATION_FAILED")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    private static Matcher traceparentOf(ConsumerRecord<String, String> record) {
        assertThat(record.headers().headers("traceparent"))
                .as("exactly one traceparent header")
                .hasSize(1);
        String value = new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
        Matcher m = TRACEPARENT.matcher(value);
        assertThat(m.matches()).as("valid W3C traceparent: " + value).isTrue();
        return m;
    }

    private ConsumerRecord<String, String> recordByEventHeader(String topic, String eventId) {
        Predicate<ConsumerRecord<String, String>> match = r -> r.headers().lastHeader("event_id") != null
                && eventId.equals(new String(r.headers().lastHeader("event_id").value(), StandardCharsets.UTF_8));
        return consumeUntil(topic, match).stream()
                .filter(match)
                .findFirst()
                .orElseThrow(() -> new AssertionError("event " + eventId + " not found on " + topic));
    }

    private SpanData publishSpan(String eventId) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                        capturedSpans.ended().stream().filter(s -> isPublishSpanOf(s, eventId)))
                .hasSize(1));
        return capturedSpans.ended().stream()
                .filter(s -> isPublishSpanOf(s, eventId))
                .findFirst()
                .orElseThrow();
    }

    private static boolean isPublishSpanOf(SpanData span, String eventId) {
        return span.getName().equals("outbox.publish")
                && eventId.equals(span.getAttributes().get(AttributeKey.stringKey("event.id")));
    }

    private String storedTraceParent(String eventId) {
        return jdbc.queryForObject(
                "SELECT trace_parent FROM platform.outbox_events WHERE event_id = ?::uuid", String.class, eventId);
    }

    private JsonNode ingestWithTraceparent(String traceparent) throws Exception {
        HttpHeaders extra = new HttpHeaders();
        if (traceparent != null) {
            extra.add("traceparent", traceparent);
        }
        MvcResult r = mvc.perform(post("/api/v1/ingestion/readings")
                        .header("Authorization", bearer(tenantIngest(ALPHA)))
                        .header("Idempotency-Key", uniqueKey())
                        .headers(extra)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demoPayload("alpha-valid.json")))
                .andExpect(status().isAccepted())
                .andReturn();
        return body(r);
    }

    /** Publishes on a different thread, inside an unrelated span, as the scheduler would long after the request. */
    private void publishLaterFromAnotherThreadInsideUnrelatedSpan(Span unrelated) throws Exception {
        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        try {
            scheduler
                    .submit(() -> {
                        try (Tracer.SpanInScope ignored = tracer.withSpan(unrelated)) {
                            outboxPublisher.publishDue();
                        }
                    })
                    .get();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void incomingTraceparentReachesTheKafkaRecordThroughTheOutbox() throws Exception {
        drainOutbox();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String incomingSpanId = "00f067aa0ba902b7";
        JsonNode accepted = ingestWithTraceparent("00-" + traceId + "-" + incomingSpanId + "-01");
        String eventId = accepted.get("eventId").asText();

        // the row, not the thread that handled the request, carries the context until the publisher gets to it
        Matcher stored = TRACEPARENT.matcher(storedTraceParent(eventId));
        assertThat(stored.matches()).isTrue();
        assertThat(stored.group(1)).isEqualTo(traceId);

        Span unrelated = tracer.nextSpan().name("unrelated-scheduler-span").start();
        publishLaterFromAnotherThreadInsideUnrelatedSpan(unrelated);
        unrelated.end();

        Matcher header = traceparentOf(recordFor("tenant-alpha.readings", eventId));
        assertThat(header.group(1))
                .isEqualTo(traceId)
                .isNotEqualTo(unrelated.context().traceId());
        // the publish span is a new child span, not the incoming span and not the request's own span
        assertThat(header.group(2)).isNotEqualTo(incomingSpanId).isNotEqualTo(stored.group(2));
        SpanData span = publishSpan(eventId);
        assertThat(span.getTraceId()).isEqualTo(traceId);
        assertThat(span.getParentSpanId()).isEqualTo(stored.group(2));
        assertThat(span.getSpanId()).isEqualTo(header.group(2));
    }

    @Test
    void withoutAnIncomingTraceparentANewTraceIsStartedAndPropagated() throws Exception {
        drainOutbox();
        String eventId = ingestWithTraceparent(null).get("eventId").asText();
        Matcher stored = TRACEPARENT.matcher(storedTraceParent(eventId));
        assertThat(stored.matches()).isTrue();

        assertThat(outboxPublisher.publishDue()).isGreaterThanOrEqualTo(1);

        Matcher header = traceparentOf(recordFor("tenant-alpha.readings", eventId));
        assertThat(header.group(1)).isNotEqualTo("0".repeat(32)).isEqualTo(stored.group(1));
        assertThat(header.group(2)).isNotEqualTo("0".repeat(16)).isNotEqualTo(stored.group(2));
        assertThat(publishSpan(eventId).getParentSpanId()).isEqualTo(stored.group(2));
        // a different request starts a different trace
        String other = ingestWithTraceparent(null).get("eventId").asText();
        assertThat(storedTraceParent(other)).doesNotContain(stored.group(1));
    }

    @Test
    void aRowWithoutStoredContextStartsARootPublishTrace() throws Exception {
        drainOutbox();
        UUID eventId = newOutboxRow(ALPHA, "MACHINE-001", Instant.now().minusSeconds(5));
        Span unrelated = tracer.nextSpan().name("unrelated-scheduler-span").start();
        publishLaterFromAnotherThreadInsideUnrelatedSpan(unrelated);
        unrelated.end();

        Matcher header = traceparentOf(recordByEventHeader("tenant-alpha.readings", eventId.toString()));
        assertThat(header.group(1)).isNotEqualTo(unrelated.context().traceId());
        SpanData span = publishSpan(eventId.toString());
        assertThat(span.getParentSpanContext().isValid()).isFalse();
        assertThat(span.getTraceId()).isEqualTo(header.group(1));
    }
}
