package com.platform.ingestion.transformation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.contract.OpenApiContractParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MappingValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MappingValidator validator = new MappingValidator();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final JsonNode SCHEMA = json(
            """
            {"$ref":"#/components/schemas/R","components":{"schemas":{
              "R":{"type":"object","properties":{
                "d":{"type":"string"},"s":{"type":"string"},"v":{"type":"number"},"u":{"type":"string"},
                "t":{"type":"string"},
                "loc":{"$ref":"#/components/schemas/L"},"tags":{"type":"array","items":{"type":"string"}}}},
              "L":{"type":"object","properties":{"site":{"type":"string"}}}}}}""");

    private static final String MAPPING =
            """
            {"device_id":"$.d","sensor_id":"$.s","value":"$.v","unit":"$.u","event_timestamp":"$.t"%s}""";

    private void assertInvalid(String mapping, String expectedField) {
        assertThatThrownBy(() -> validator.validate(json(mapping), SCHEMA))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_MAPPING);
                    assertThat(e.errors()).anyMatch(f -> f.field().equals(expectedField));
                });
    }

    @Test
    void acceptsValidMappingFollowingRefsAndArrays() {
        assertThatCode(() -> validator.validate(
                        json(MAPPING.formatted(",\"metadata.site\":{\"path\":\"$.loc.site\",\"default\":\"x\"},"
                                + "\"metadata.tag\":\"$.tags[0]\"")),
                        SCHEMA))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownTarget() {
        assertInvalid(MAPPING.formatted(",\"color\":\"$.d\""), "color");
        assertInvalid(MAPPING.formatted(",\"metadata.bad name\":\"$.d\""), "metadata.bad name");
    }

    @Test
    void rejectsUnknownTransform() {
        assertInvalid(
                MAPPING.formatted("").replace("\"$.v\"", "{\"path\":\"$.v\",\"transforms\":[\"rot13\"]}"), "value");
    }

    @Test
    void rejectsMissingRequiredTarget() {
        assertInvalid(
                "{\"device_id\":\"$.d\",\"sensor_id\":\"$.s\",\"value\":\"$.v\",\"unit\":\"$.u\"}", "event_timestamp");
    }

    @Test
    void rejectsPathNotInSchema() {
        assertInvalid(MAPPING.formatted("").replace("$.v", "$.temperature"), "value");
        assertInvalid(MAPPING.formatted(",\"metadata.x\":\"$.loc.nope\""), "metadata.x");
    }

    @Test
    void rejectsBadPathSyntax() {
        assertInvalid(MAPPING.formatted("").replace("$.v", "v"), "value");
        assertInvalid(MAPPING.formatted("").replace("$.v", "$..v"), "value");
        assertInvalid(MAPPING.formatted("").replace("$.v", "$.tags[x]"), "value");
        assertInvalid(MAPPING.formatted("").replace("$.v", "$.a[0"), "value");
    }

    @Test
    void rejectsNonObjectMapping() {
        assertThatThrownBy(() -> validator.parse(json("[1]"))).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsUnknownEntryProperty() {
        assertInvalid(MAPPING.formatted("").replace("\"$.v\"", "{\"path\":\"$.v\",\"script\":\"x\"}"), "value");
    }

    @Test
    void demoMappingsAreValidAgainstDemoSpecs() throws Exception {
        OpenApiContractParser parser = new OpenApiContractParser();
        for (String tenant : new String[] {"tenant-alpha", "tenant-beta"}) {
            byte[] spec = Files.readAllBytes(Path.of("demo", tenant, "openapi.yaml"));
            JsonNode mapping = MAPPER.readTree(Files.readString(Path.of("demo", tenant, "mapping.json")));
            JsonNode schema = parser.parse(spec, null).requestSchema();
            assertThatCode(() -> validator.validate(mapping, schema)).as(tenant).doesNotThrowAnyException();
        }
    }
}
