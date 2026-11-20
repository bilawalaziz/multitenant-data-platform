package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.ingestion.security.DeviceBindingPolicy;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceCredentialsIT extends AbstractIntegrationTest {

    private static final String TENANT = "tenant-devcreds";

    @Autowired
    private TestSupportConfig.FakeDeviceCredentialProvisioner provisioner;

    @Autowired
    private DeviceBindingPolicy bindingPolicy;

    private UUID tenantId;

    @BeforeAll
    void setUpTenant() throws Exception {
        registerTenant(TENANT);
        tenantId = jdbc.queryForObject("SELECT id FROM platform.tenants WHERE tenant_code=?", UUID.class, TENANT);
        JsonNode created = body(uploadContract(
                        TENANT,
                        tenantAdmin(TENANT),
                        "devcreds-readings",
                        demoFile("tenant-alpha/openapi.yaml"),
                        demoFile("tenant-alpha/mapping.json"))
                .andExpect(status().isCreated())
                .andReturn());
        activate(
                        TENANT,
                        tenantAdmin(TENANT),
                        created.get("contractId").asText(),
                        created.get("version").asInt())
                .andExpect(status().isOk());
    }

    @AfterEach
    void resetFake() {
        provisioner.reset();
        ReflectionTestUtils.setField(bindingPolicy, "tenantWideEnabled", true);
    }

    @AfterAll
    void publishLeftoverEvents() {
        drainOutbox();
    }

    // The demo contract only accepts MACHINE-nnn device ids.
    private final AtomicInteger deviceSeq = new AtomicInteger(100);

    private String uniqueDevice() {
        return "MACHINE-" + deviceSeq.getAndIncrement();
    }

    private JsonNode register(String deviceId) throws Exception {
        return body(mvc.perform(post("/api/v1/tenants/" + TENANT + "/devices")
                        .header("Authorization", bearer(tenantAdmin(TENANT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"description\":\"it\"}"))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private String deviceToken(String deviceId) {
        return "tenant-ingest." + TENANT + "." + deviceId;
    }

    private String payloadFor(String deviceId) throws Exception {
        ObjectNode node =
                (ObjectNode) mapper.readTree(new String(demoFile("payloads/alpha-valid.json"), StandardCharsets.UTF_8));
        node.put("deviceId", deviceId);
        return mapper.writeValueAsString(node);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int storedReadings(String key) {
        return count("SELECT count(*) FROM platform.ingestion_records WHERE idempotency_key = ?", key);
    }

    @Test
    void registrationReturnsTheSecretOnceAndTheListNeverShowsIt() throws Exception {
        String device = uniqueDevice();

        JsonNode registered = register(device);

        assertThat(registered.get("clientId").asText()).startsWith("device-" + TENANT + "-");
        assertThat(registered.get("clientSecret").asText()).isNotBlank();
        assertThat(provisioner.clients).containsKey(registered.get("clientId").asText());
        assertThat(jdbc.queryForObject(
                        "SELECT credential_client_id FROM platform.devices WHERE device_id=?", String.class, device))
                .isEqualTo(registered.get("clientId").asText());
        String list = getAs(tenantAdmin(TENANT), "/api/v1/tenants/" + TENANT + "/devices")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(list).contains(registered.get("clientId").asText());
        assertThat(list)
                .doesNotContain("clientSecret")
                .doesNotContain(registered.get("clientSecret").asText());
    }

    @Test
    void deviceTokenIsAcceptedForItsOwnDeviceAndRejectedForAnotherWithNothingStored() throws Exception {
        String own = uniqueDevice();
        String other = uniqueDevice();
        register(own);
        register(other);

        ingest(deviceToken(own), uniqueKey(), payloadFor(own)).andExpect(status().isAccepted());

        String key = uniqueKey();
        ingest(deviceToken(own), key, payloadFor(other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("DEVICE_MISMATCH"));
        assertThat(storedReadings(key)).isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_records WHERE idempotency_key = ?", key))
                .isZero();
    }

    @Test
    void tenantWideTokenIsRefusedOnlyWhileTheFlagIsOff() throws Exception {
        String device = uniqueDevice();
        register(device);

        ingest(tenantIngest(TENANT), uniqueKey(), payloadFor(device)).andExpect(status().isAccepted());

        ReflectionTestUtils.setField(bindingPolicy, "tenantWideEnabled", false);
        String key = uniqueKey();
        ingest(tenantIngest(TENANT), key, payloadFor(device))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("DEVICE_CREDENTIAL_REQUIRED"));
        assertThat(storedReadings(key)).isZero();
        // The per-device credential keeps working with the flag off.
        ingest(deviceToken(device), uniqueKey(), payloadFor(device)).andExpect(status().isAccepted());
    }

    @Test
    void deactivatedDeviceIsRejectedAtOnceAndItsClientDisabled() throws Exception {
        String device = uniqueDevice();
        String clientId = register(device).get("clientId").asText();
        ingest(deviceToken(device), uniqueKey(), payloadFor(device)).andExpect(status().isAccepted());

        mvc.perform(post("/api/v1/tenants/" + TENANT + "/devices/" + device + "/deactivate")
                        .header("Authorization", bearer(tenantAdmin(TENANT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        assertThat(provisioner.clients.get(clientId).enabled()).isFalse();
        String key = uniqueKey();
        ingest(deviceToken(device), key, payloadFor(device))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DEVICE_NOT_REGISTERED"));
        assertThat(storedReadings(key)).isZero();
    }

    @Test
    void unreachableIdentityProviderFailsRegistrationWithoutLeavingADevice() throws Exception {
        String device = uniqueDevice();
        provisioner.setUnreachable(true);

        mvc.perform(post("/api/v1/tenants/" + TENANT + "/devices")
                        .header("Authorization", bearer(tenantAdmin(TENANT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + device + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("CREDENTIAL_PROVISIONING_UNAVAILABLE"));

        assertThat(count("SELECT count(*) FROM platform.devices WHERE device_id = ?", device))
                .isZero();
    }

    @Test
    void failedInsertRemovesTheClientThatWasJustCreated() throws Exception {
        String device = uniqueDevice();
        // A concurrent registration wins the unique constraint between the client creation and the insert.
        provisioner.setAfterCreate(() -> jdbc.update(
                "INSERT INTO platform.devices (id, tenant_id, device_id, status, created_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?)",
                UUID.randomUUID(),
                tenantId,
                device,
                Timestamp.from(Instant.now())));

        mvc.perform(post("/api/v1/tenants/" + TENANT + "/devices")
                        .header("Authorization", bearer(tenantAdmin(TENANT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + device + "\"}"))
                .andExpect(status().isConflict());

        assertThat(provisioner.deleted).hasSize(1);
        assertThat(provisioner.clients).doesNotContainKey(provisioner.deleted.get(0));
        assertThat(count("SELECT count(*) FROM platform.devices WHERE device_id = ?", device))
                .isEqualTo(1);
    }

    @Test
    void replayWithAnotherDevicesTokenDoesNotRevealTheStoredResult() throws Exception {
        String owner = uniqueDevice();
        String intruder = uniqueDevice();
        register(owner);
        register(intruder);
        String key = uniqueKey();
        String payload = payloadFor(owner);
        ingest(deviceToken(owner), key, payload).andExpect(status().isAccepted());

        ingest(deviceToken(intruder), key, payload)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("DEVICE_MISMATCH"));

        ingest(deviceToken(owner), key, payload)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void deviceRegisteredBeforeCredentialsExistGetsOneOnRequestAndThenRotates() throws Exception {
        String device = uniqueDevice();
        jdbc.update(
                "INSERT INTO platform.devices (id, tenant_id, device_id, status, created_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', now())",
                UUID.randomUUID(),
                tenantId,
                device);

        JsonNode first = issue(device);
        JsonNode second = issue(device);

        assertThat(second.get("clientId").asText())
                .isEqualTo(first.get("clientId").asText());
        assertThat(second.get("clientSecret").asText())
                .isNotEqualTo(first.get("clientSecret").asText());
        assertThat(provisioner.clients.get(first.get("clientId").asText()).secret())
                .isEqualTo(second.get("clientSecret").asText());
        assertThat(jdbc.queryForObject(
                        "SELECT credential_client_id FROM platform.devices WHERE device_id=?", String.class, device))
                .isEqualTo(first.get("clientId").asText());
    }

    @Test
    void credentialEndpointsAreLimitedToAdminsOfTheTenant() throws Exception {
        String device = uniqueDevice();
        register(device);
        String path = "/api/v1/tenants/" + TENANT + "/devices/" + device;

        for (String token : new String[] {tenantIngest(TENANT), tenantAdmin("tenant-other")}) {
            mvc.perform(post(path + "/credentials").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path + "/deactivate").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post(path + "/credentials").header("Authorization", bearer(PLATFORM_ADMIN)))
                .andExpect(status().isOk());
    }

    private JsonNode issue(String device) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/tenants/" + TENANT + "/devices/" + device + "/credentials")
                        .header("Authorization", bearer(tenantAdmin(TENANT))))
                .andExpect(status().isOk())
                .andReturn();
        return body(r);
    }
}
