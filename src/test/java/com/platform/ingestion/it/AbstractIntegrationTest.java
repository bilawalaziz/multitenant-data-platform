package com.platform.ingestion.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.messaging.OutboxPublisher;
import com.platform.ingestion.persistence.OutboxEvent;
import com.platform.ingestion.persistence.OutboxRepository;
import com.platform.ingestion.tenant.TenantScope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base: one PostgreSQL and one Kafka container for the whole test JVM (singleton pattern) and one cached
 * Spring context. The outbox scheduler is disabled; tests drive {@link OutboxPublisher#publishDue()} directly.
 */
@SpringBootTest(
        properties = {
            "platform.outbox.enabled=false",
            "platform.retention.enabled=false",
            // Starting Redis for every test class buys nothing; RateLimitIT opts back in.
            "platform.rate-limit.enabled=false",
            "platform.kafka.admin-timeout-ms=10000"
        })
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TestSupportConfig.class)
public abstract class AbstractIntegrationTest {

    static final String APP_ROLE = "sensor_app";
    static final String APP_PASSWORD = "sensor_app_test_pw";

    // No @ServiceConnection: the application connects as the restricted role and Flyway as the container superuser,
    // which a single connection-details bean could not express.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:17-alpine"))
            .withInitScript("db/create-app-role.sql");

    @ServiceConnection
    static final KafkaContainer KAFKA = new SingleNodeKafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void databaseRoles(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_role", () -> APP_ROLE);
    }

    protected static final String PLATFORM_ADMIN = "platform-admin";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    /** Superuser connection: sees and changes raw state regardless of row-level security. */
    @Autowired
    @Qualifier("ownerJdbc")
    protected JdbcTemplate jdbc;

    /** The application's own connection pool, subject to row-level security. */
    @Autowired
    protected JdbcTemplate appJdbc;

    @Autowired
    protected OutboxPublisher outboxPublisher;

    @Autowired
    protected TestSupportConfig.TogglingEventPublisher toggling;

    @Autowired
    protected OutboxRepository outboxRepository;

    @Autowired
    protected TransactionTemplate tx;

    private final List<UUID> outboxFixtures = new ArrayList<>();

    /** Restores the publisher and retires outbox rows created by {@link #newOutboxRow} so tests stay independent. */
    @AfterEach
    void resetOutboxFixtures() {
        toggling.setSender(null);
        toggling.setFailing(false);
        for (UUID eventId : outboxFixtures) {
            jdbc.update(
                    "UPDATE platform.outbox_events SET status='PUBLISHED', published_at=now() "
                            + "WHERE event_id = ? AND status <> 'PUBLISHED'",
                    eventId);
        }
        outboxFixtures.clear();
    }

    /** Inserts a PENDING outbox row on the tenant's topic (registering the tenant if needed); returns its event id. */
    protected UUID newOutboxRow(String tenant, String device, Instant createdAt) throws Exception {
        registerTenant(tenant);
        UUID tenantId = jdbc.queryForObject("SELECT id FROM platform.tenants WHERE tenant_code=?", UUID.class, tenant);
        UUID eventId = UUID.randomUUID();
        try (TenantScope scope = TenantScope.tenant(tenantId)) {
            tx.executeWithoutResult(s -> outboxRepository.save(new OutboxEvent(
                    UUID.randomUUID(),
                    tenantId,
                    eventId,
                    tenant + ".readings",
                    device,
                    mapper.createObjectNode()
                            .put("tenant_id", tenant)
                            .put("schema_version", "1.0")
                            .put("device_id", device),
                    createdAt)));
        }
        outboxFixtures.add(eventId);
        return eventId;
    }

    /** Spring Kafka properties with the producer max.block.ms the publisher takes into account. */
    protected static KafkaProperties springKafka(int maxBlockMs) {
        KafkaProperties kafka = new KafkaProperties();
        kafka.getProducer().getProperties().put("max.block.ms", String.valueOf(maxBlockMs));
        return kafka;
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static String tenantAdmin(String tenant) {
        return "tenant-admin." + tenant;
    }

    protected static String tenantIngest(String tenant) {
        return "tenant-ingest." + tenant;
    }

    protected static byte[] demoFile(String relative) {
        try {
            return Files.readAllBytes(Path.of("demo").resolve(relative));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected static String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    /** Registers a tenant (idempotent: an existing tenant is fine). */
    protected void registerTenant(String code) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", bearer(PLATFORM_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + code + "\",\"displayName\":\"" + code + "\"}"))
                .andReturn();
        int status = r.getResponse().getStatus();
        if (status != 201 && status != 409) {
            throw new IllegalStateException("Tenant registration failed with " + status + ": "
                    + r.getResponse().getContentAsString());
        }
    }

    protected void registerDevice(String tenant, String deviceId) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/tenants/" + tenant + "/devices")
                        .header("Authorization", bearer(tenantAdmin(tenant)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"description\":\"test device\"}"))
                .andReturn();
        int status = r.getResponse().getStatus();
        if (status != 201 && status != 409) {
            throw new IllegalStateException("Device registration failed with " + status);
        }
    }

    protected ResultActions uploadContract(String tenant, String token, String name, byte[] spec, byte[] mapping)
            throws Exception {
        MockHttpServletRequestBuilder request = multipart("/api/v1/tenants/" + tenant + "/contracts")
                .file(new MockMultipartFile("spec", "openapi.yaml", "application/x-yaml", spec))
                .file(new MockMultipartFile("mapping", "mapping.json", "application/json", mapping))
                .param("name", name)
                .header("Authorization", bearer(token));
        return mvc.perform(request);
    }

    protected ResultActions activate(String tenant, String token, String contractId, int version) throws Exception {
        return mvc.perform(
                post("/api/v1/tenants/" + tenant + "/contracts/" + contractId + "/versions/" + version + "/activate")
                        .header("Authorization", bearer(token)));
    }

    protected ResultActions ingest(String token, String key, String payload) throws Exception {
        return mvc.perform(post("/api/v1/ingestion/readings")
                .header("Authorization", bearer(token))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    protected ResultActions getAs(String token, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer(token)));
    }

    /** Uploads and activates a contract with the exact demo files; returns the contract id. */
    protected String uploadAndActivateDemo(String tenant, String contractName) throws Exception {
        MvcResult upload = uploadContract(
                        tenant,
                        tenantAdmin(tenant),
                        contractName,
                        demoFile(tenant + "/openapi.yaml"),
                        demoFile(tenant + "/mapping.json"))
                .andReturn();
        if (upload.getResponse().getStatus() != 201) {
            throw new IllegalStateException(
                    "Upload failed: " + upload.getResponse().getContentAsString());
        }
        JsonNode created = body(upload);
        MvcResult activated = activate(
                        tenant,
                        tenantAdmin(tenant),
                        created.get("contractId").asText(),
                        created.get("version").asInt())
                .andReturn();
        if (activated.getResponse().getStatus() != 200) {
            throw new IllegalStateException(
                    "Activation failed: " + activated.getResponse().getContentAsString());
        }
        return created.get("contractId").asText();
    }

    /** Publishes everything that is due so Kafka-dependent assertions start from a clean outbox. */
    protected void drainOutbox() {
        toggling.setFailing(false);
        for (int i = 0; i < 20; i++) {
            if (outboxPublisher.publishDue() == 0) {
                return;
            }
        }
    }

    @BeforeAll
    static void ensureContainersRunning() {
        if (!POSTGRES.isRunning() || !KAFKA.isRunning()) {
            throw new IllegalStateException("Test containers are not running");
        }
    }
}
