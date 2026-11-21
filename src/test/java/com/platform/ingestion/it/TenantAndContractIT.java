package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class TenantAndContractIT extends AbstractIntegrationTest {

    private static final String SPEC =
            """
            openapi: 3.0.3
            info: {title: T, version: '1'}
            paths:
              /readings:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Reading'
            components:
              schemas:
                Reading:
                  type: object
                  required: [d, s, v, u, t]
                  properties:
                    d: {type: string}
                    s: {type: string}
                    v: {type: number}
                    u: {type: string}
                    t: {type: string, format: date-time}
            """;

    private static final String MAPPING =
            """
            {"device_id":"$.d","sensor_id":"$.s","value":"$.v","unit":"$.u","event_timestamp":"$.t"}""";

    @Test
    void flywayMigratesPlatformSchema() {
        Integer tables = jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables WHERE table_schema = 'platform'
                AND table_name IN ('tenants','devices','contracts','contract_versions','contract_mappings',
                                   'ingestion_records','idempotency_records','outbox_events')""",
                Integer.class);
        assertThat(tables).isEqualTo(8);
        Integer partial = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname='platform' "
                        + "AND indexname='uq_contract_versions_one_active_per_tenant'",
                Integer.class);
        assertThat(partial).isEqualTo(1);
    }

    @Test
    void registrationCreatesTenantSchemaSettingsAndTopic() throws Exception {
        mvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", bearer(PLATFORM_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-schema\",\"displayName\":\"Schema Tenant\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("tenant-schema"))
                .andExpect(jsonPath("$.schemaName").value("ts_tenant_schema"))
                .andExpect(jsonPath("$.kafkaTopic").value("tenant-schema.readings"));

        assertThat(setting("tenant-schema", "data_retention_days")).isEqualTo("30");

        mvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", bearer(PLATFORM_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-schema\",\"displayName\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        mvc.perform(put("/api/v1/tenants/tenant-schema/settings")
                        .header("Authorization", bearer(tenantAdmin("tenant-schema")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"data_retention_days\":\"90\",\"alert_email\":\"ops@example.com\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.data_retention_days").value("90"));
        mvc.perform(get("/api/v1/tenants/tenant-schema/settings").header("Authorization", bearer(PLATFORM_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.alert_email").value("ops@example.com"))
                .andExpect(jsonPath("$.schemaName").value("ts_tenant_schema"));
        assertThat(setting("tenant-schema", "alert_email")).isEqualTo("ops@example.com");

        mvc.perform(put("/api/v1/tenants/tenant-schema/settings")
                        .header("Authorization", bearer(tenantAdmin("tenant-schema")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"bad key; drop\":\"x\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    private String setting(String tenantCode, String key) {
        return jdbc.queryForObject(
                "SELECT s.setting_value FROM platform.tenant_settings s JOIN platform.tenants t ON t.id = s.tenant_id "
                        + "WHERE t.tenant_code = ? AND s.setting_key = ?",
                String.class,
                tenantCode,
                key);
    }

    @Test
    void registrationValidatesTenantCodeAndRoles() throws Exception {
        mvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", bearer(PLATFORM_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"X; DROP SCHEMA platform\",\"displayName\":\"evil\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("tenantId"));
        mvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", bearer(tenantAdmin("tenant-alpha")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-new\",\"displayName\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", bearer(PLATFORM_ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void contractVersioningActivationAndReactivation() throws Exception {
        registerTenant("tenant-contracts");
        String admin = tenantAdmin("tenant-contracts");

        MvcResult v1 = uploadContract(
                        "tenant-contracts",
                        admin,
                        "sensor-readings",
                        SPEC.getBytes(StandardCharsets.UTF_8),
                        MAPPING.getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String contractId = body(v1).get("contractId").asText();
        String checksum = body(v1).get("checksumSha256").asText();
        assertThat(checksum).hasSize(64);

        // Same content again: new immutable version 2 of the same contract name, same checksum.
        uploadContract(
                        "tenant-contracts",
                        admin,
                        "sensor-readings",
                        SPEC.getBytes(StandardCharsets.UTF_8),
                        MAPPING.getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contractId").value(contractId))
                .andExpect(jsonPath("$.checksumSha256").value(checksum));

        // No active contract yet
        getAs(admin, "/api/v1/tenants/tenant-contracts/contracts/active").andExpect(status().isNotFound());

        activate("tenant-contracts", admin, contractId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        getAs(admin, "/api/v1/tenants/tenant-contracts/contracts/active")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.contractId").value(contractId));

        activate("tenant-contracts", admin, contractId, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(statuses(contractId)).containsExactly("ACTIVE", "INACTIVE"); // newest first

        // Re-activating the older version switches back; still exactly one ACTIVE version.
        activate("tenant-contracts", admin, contractId, 1).andExpect(status().isOk());
        assertThat(statuses(contractId)).containsExactly("INACTIVE", "ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM platform.contract_versions cv "
                                + "JOIN platform.tenants t ON t.id=cv.tenant_id "
                                + "WHERE t.tenant_code='tenant-contracts' AND cv.status='ACTIVE'",
                        Integer.class))
                .isEqualTo(1);

        mvc.perform(get("/api/v1/tenants/tenant-contracts/contracts/" + contractId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.activeVersion").value(1))
                .andExpect(jsonPath("$.name").value("sensor-readings"));
        mvc.perform(get("/api/v1/tenants/tenant-contracts/contracts/" + contractId + "/versions/2")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapping.device_id").value("$.d"))
                .andExpect(jsonPath("$.requestSchema.components.schemas.Reading.type")
                        .value("object"));
        mvc.perform(get("/api/v1/tenants/tenant-contracts/contracts").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latestVersion").value(2))
                .andExpect(jsonPath("$[0].activeVersion").value(1));
        mvc.perform(get("/api/v1/tenants/tenant-contracts/contracts/" + contractId + "/versions/9")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    private List<String> statuses(String contractId) throws Exception {
        JsonNode detail =
                body(getAs(tenantAdmin("tenant-contracts"), "/api/v1/tenants/tenant-contracts/contracts/" + contractId)
                        .andReturn());
        return detail.get("versions").findValuesAsText("status");
    }

    @Test
    void invalidContractsAndMappingsAreRejected() throws Exception {
        registerTenant("tenant-contracts");
        String admin = tenantAdmin("tenant-contracts");
        byte[] mapping = MAPPING.getBytes(StandardCharsets.UTF_8);

        uploadContract(
                        "tenant-contracts",
                        admin,
                        "bad",
                        SPEC.replace("3.0.3", "3.1.0").getBytes(StandardCharsets.UTF_8),
                        mapping)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CONTRACT"));
        uploadContract(
                        "tenant-contracts",
                        admin,
                        "bad",
                        SPEC.replace("#/components/schemas/Reading", "https://evil/x.json")
                                .getBytes(StandardCharsets.UTF_8),
                        mapping)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CONTRACT"));
        uploadContract("tenant-contracts", admin, "bad", "not: [valid".getBytes(StandardCharsets.UTF_8), mapping)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CONTRACT"));

        uploadContract(
                        "tenant-contracts",
                        admin,
                        "bad",
                        SPEC.getBytes(StandardCharsets.UTF_8),
                        MAPPING.replace("$.v", "$.missing").getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_MAPPING"))
                .andExpect(jsonPath("$.errors[0].field").value("value"));
        uploadContract(
                        "tenant-contracts",
                        admin,
                        "bad",
                        SPEC.getBytes(StandardCharsets.UTF_8),
                        "[]".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_MAPPING"));

        // A rejected upload leaves no trace
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.contracts WHERE name='bad'", Integer.class))
                .isZero();

        mvc.perform(multipart("/api/v1/tenants/tenant-contracts/contracts")
                        .param("name", "x")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void oversizedSpecIsRejectedWithErrorBody() throws Exception {
        registerTenant("tenant-contracts");
        byte[] huge = new byte[3 * 1024 * 1024];
        Arrays.fill(huge, (byte) ' ');
        uploadContract(
                        "tenant-contracts",
                        tenantAdmin("tenant-contracts"),
                        "huge",
                        huge,
                        MAPPING.getBytes(StandardCharsets.UTF_8))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
}
