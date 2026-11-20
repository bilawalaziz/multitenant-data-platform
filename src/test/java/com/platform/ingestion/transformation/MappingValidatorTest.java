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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    static Stream<Arguments> invalidMappings() {
        String base = MAPPING.formatted("");
        return Stream.of(
                Arguments.of("unknown target", MAPPING.formatted(",\"color\":\"$.d\""), "color"),
                Arguments.of(
                        "bad metadata name", MAPPING.formatted(",\"metadata.bad name\":\"$.d\""), "metadata.bad name"),
                Arguments.of(
                        "unknown transform",
                        base.replace("\"$.v\"", "{\"path\":\"$.v\",\"transforms\":[\"rot13\"]}"),
                        "value"),
                Arguments.of(
                        "missing required target",
                        "{\"device_id\":\"$.d\",\"sensor_id\":\"$.s\",\"value\":\"$.v\",\"unit\":\"$.u\"}",
                        "event_timestamp"),
                Arguments.of("path not in schema", base.replace("$.v", "$.temperature"), "value"),
                Arguments.of(
                        "nested path not in schema", MAPPING.formatted(",\"metadata.x\":\"$.loc.nope\""), "metadata.x"),
                Arguments.of("path without root", base.replace("$.v", "v"), "value"),
                Arguments.of("recursive descent", base.replace("$.v", "$..v"), "value"),
                Arguments.of("non-numeric index", base.replace("$.v", "$.tags[x]"), "value"),
                Arguments.of("unclosed index", base.replace("$.v", "$.a[0"), "value"),
                Arguments.of(
                        "unknown entry property",
                        base.replace("\"$.v\"", "{\"path\":\"$.v\",\"script\":\"x\"}"),
                        "value"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMappings")
    void rejectsInvalidMappings(String name, String mapping, String expectedField) {
        assertInvalid(mapping, expectedField);
    }

    @Test
    void rejectsNonObjectMapping() {
        assertThatThrownBy(() -> validator.parse(json("[1]"))).isInstanceOf(ApiException.class);
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
