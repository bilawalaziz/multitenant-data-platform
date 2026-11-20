package com.platform.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.FieldError;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SchemaValidationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SchemaValidationService service = new SchemaValidationService();

    private static final String SCHEMA =
            """
            {
              "$ref": "#/components/schemas/Reading",
              "components": {"schemas": {
                "Reading": {
                  "type": "object",
                  "required": ["deviceId", "temperature", "unit", "recordedAt"],
                  "properties": {
                    "deviceId": {"type": "string", "pattern": "^MACHINE-[0-9]{3}$", "maxLength": 11},
                    "temperature": {"type": "number", "minimum": -50, "maximum": 500},
                    "unit": {"type": "string", "enum": ["celsius", "fahrenheit"]},
                    "recordedAt": {"type": "string", "format": "date-time"},
                    "count": {"type": "integer"},
                    "active": {"type": "boolean"},
                    "note": {"type": "string", "nullable": true},
                    "location": {"$ref": "#/components/schemas/Location"},
                    "tags": {"type": "array", "maxItems": 2, "items": {"type": "string"}}
                  }
                },
                "Location": {
                  "type": "object",
                  "required": ["site"],
                  "properties": {"site": {"type": "string"}, "line": {"type": "string"}}
                }
              }}
            }
            """;

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<FieldError> validate(String payload) {
        return service.validate(json(SCHEMA), json(payload));
    }

    private static final String VALID =
            """
            {"deviceId":"MACHINE-001","temperature":21.5,"unit":"celsius",
                "recordedAt":"2026-11-17T14:30:00Z"}
            """;

    static Stream<Arguments> invalidPayloads() {
        String nested =
                """
                {"deviceId":"MACHINE-001","temperature":1,"unit":"celsius","recordedAt":"2026-11-17T14:30:00Z",
                 %s}
                """;
        return Stream.of(
                Arguments.of("{\"deviceId\":\"MACHINE-001\"}", "temperature", "Missing required field"),
                Arguments.of("{\"deviceId\":\"MACHINE-001\"}", "unit", "Missing required field"),
                Arguments.of("{\"deviceId\":\"MACHINE-001\"}", "recordedAt", "Missing required field"),
                Arguments.of(VALID.replace("21.5", "\"hot\""), "temperature", "Expected numeric value"),
                Arguments.of(VALID.replace("}\n", ",\"count\":1.5}\n"), "count", "Expected integer value"),
                Arguments.of(VALID.replace("}\n", ",\"active\":\"yes\"}\n"), "active", "Expected boolean value"),
                Arguments.of(VALID.replace("\"MACHINE-001\"", "12"), "deviceId", "Expected string value"),
                Arguments.of(VALID.replace("21.5", "501"), "temperature", "500"),
                Arguments.of(VALID.replace("21.5", "-51"), "temperature", "-50"),
                Arguments.of(VALID.replace("MACHINE-001", "PUMP-1"), "deviceId", "pattern"),
                Arguments.of(VALID.replace("MACHINE-001", "MACHINE-0012345"), "deviceId", "11"),
                Arguments.of(VALID.replace("celsius", "kelvin"), "unit", "Must be one of: celsius, fahrenheit"),
                Arguments.of(VALID.replace("2026-11-17T14:30:00Z", "yesterday"), "recordedAt", "date-time"),
                Arguments.of(
                        nested.formatted("\"location\":{\"line\":\"A\"}"), "location.site", "Missing required field"),
                Arguments.of(nested.formatted("\"tags\":[\"a\",\"b\",\"c\"]"), "tags", "at most 2"),
                Arguments.of(nested.formatted("\"tags\":[\"a\",7]"), "tags[1]", "Expected string value"));
    }

    @ParameterizedTest(name = "{1}: {2}")
    @MethodSource("invalidPayloads")
    void mapsLibraryErrorsToFieldMessages(String payload, String field, String messageFragment) {
        assertThat(validate(payload))
                .anyMatch(e ->
                        e.field().equals(field) && e.message().toLowerCase().contains(messageFragment.toLowerCase()));
    }

    @Test
    void acceptsValidPayloadsAndOpenApi30Dialect() {
        assertThat(validate(VALID)).isEmpty();
        assertThat(validate(VALID.replace("21.5", "500"))).isEmpty();
        assertThat(validate(VALID.replace("2026-11-17T14:30:00Z", "2026-11-17T14:30:00+02:00")))
                .isEmpty();
        assertThat(validate(VALID.replace("}\n", ",\"note\":null}\n"))).isEmpty();

        JsonNode exclusive = json(
                """
                {"type":"object","properties":{"v":{"type":"number","minimum":0,"exclusiveMinimum":true}}}
                """);
        assertThat(service.validate(exclusive, json("{\"v\":0}"))).isNotEmpty();
        assertThat(service.validate(exclusive, json("{\"v\":0.1}"))).isEmpty();
    }

    @Test
    void cachesCompiledSchemaPerTenantContractVersion() {
        UUID tenant = UUID.randomUUID();
        UUID contract = UUID.randomUUID();
        var key = new SchemaValidationService.CacheKey(tenant, contract, 1);
        service.validate(key, json(SCHEMA), json(VALID));
        service.validate(key, json(SCHEMA), json(VALID));
        service.validate(new SchemaValidationService.CacheKey(tenant, contract, 2), json(SCHEMA), json(VALID));
        assertThat(service.cacheSize()).isEqualTo(2);
    }

    @Test
    void remoteSchemaLoadingIsDisabled() {
        JsonNode schema = json("{\"$ref\":\"https://evil.example/schema.json\"}");
        assertThatThrownBy(() -> service.validate(schema, json("{}"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.assertCompilable(schema)).isInstanceOf(ApiException.class);
    }
}
