package com.platform.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.FieldError;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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

    @Test
    void validPayloadHasNoErrors() {
        assertThat(validate(VALID)).isEmpty();
    }

    @Test
    void reportsMissingRequiredPropertiesByName() {
        assertThat(validate("{\"deviceId\":\"MACHINE-001\"}"))
                .contains(
                        new FieldError("temperature", "Missing required field"),
                        new FieldError("unit", "Missing required field"),
                        new FieldError("recordedAt", "Missing required field"));
    }

    @Test
    void reportsTypeErrorsWithFriendlyMessages() {
        List<FieldError> errors = validate(
                """
                {"deviceId": 12, "temperature": "hot", "unit": "celsius", "recordedAt": "2026-11-17T14:30:00Z",
                 "count": 1.5, "active": "yes"}
                """);
        assertThat(errors)
                .contains(
                        new FieldError("temperature", "Expected numeric value"),
                        new FieldError("deviceId", "Expected string value"),
                        new FieldError("count", "Expected integer value"),
                        new FieldError("active", "Expected boolean value"));
    }

    @Test
    void enforcesNumericBounds() {
        assertThat(validate(VALID.replace("21.5", "501")))
                .anyMatch(e -> e.field().equals("temperature") && e.message().contains("500"));
        assertThat(validate(VALID.replace("21.5", "-51")))
                .anyMatch(e -> e.field().equals("temperature") && e.message().contains("-50"));
        assertThat(validate(VALID.replace("21.5", "500"))).isEmpty();
    }

    @Test
    void enforcesPatternAndMaxLength() {
        assertThat(validate(VALID.replace("MACHINE-001", "PUMP-1")))
                .anyMatch(e -> e.field().equals("deviceId"));
        assertThat(validate(VALID.replace("MACHINE-001", "MACHINE-0012345")))
                .anyMatch(e -> e.field().equals("deviceId") && e.message().contains("11"));
    }

    @Test
    void enforcesEnum() {
        assertThat(validate(VALID.replace("celsius", "kelvin")))
                .contains(new FieldError("unit", "Must be one of: celsius, fahrenheit"));
    }

    @Test
    void validatesNestedObjectsFollowingLocalRefs() {
        List<FieldError> errors = service.validate(
                json(SCHEMA),
                json(
                        """
                {"deviceId":"MACHINE-001","temperature":1,"unit":"celsius",
                "recordedAt":"2026-11-17T14:30:00Z",
                 "location":{"line":"A"}}
                """));
        assertThat(errors).contains(new FieldError("location.site", "Missing required field"));
    }

    @Test
    void validatesArrays() {
        List<FieldError> errors = service.validate(
                json(SCHEMA),
                json(
                        """
                {"deviceId":"MACHINE-001","temperature":1,"unit":"celsius",
                "recordedAt":"2026-11-17T14:30:00Z",
                 "tags":["a","b","c"]}
                """));
        assertThat(errors).anyMatch(e -> e.field().equals("tags") && e.message().contains("at most 2"));

        errors = service.validate(
                json(SCHEMA),
                json(
                        """
                {"deviceId":"MACHINE-001","temperature":1,"unit":"celsius",
                "recordedAt":"2026-11-17T14:30:00Z",
                 "tags":["a", 7]}
                """));
        assertThat(errors).contains(new FieldError("tags[1]", "Expected string value"));
    }

    @Test
    void assertsDateTimeFormat() {
        assertThat(validate(VALID.replace("2026-11-17T14:30:00Z", "yesterday")))
                .anyMatch(e -> e.field().equals("recordedAt")
                        && e.message().toLowerCase().contains("date-time"));
        assertThat(validate(VALID.replace("2026-11-17T14:30:00Z", "2026-11-17T14:30:00+02:00")))
                .isEmpty();
    }

    @Test
    void supportsOpenApiNullable() {
        assertThat(
                        service.validate(
                                json(SCHEMA),
                                json(
                                        """
                {"deviceId":"MACHINE-001","temperature":1,"unit":"celsius",
                "recordedAt":"2026-11-17T14:30:00Z","note":null}
                """)))
                .isEmpty();
    }

    @Test
    void supportsOpenApi30BooleanExclusiveMinimum() {
        JsonNode schema = json(
                """
                {"type":"object","properties":{"v":{"type":"number","minimum":0,"exclusiveMinimum":true}}}
                """);
        assertThat(service.validate(schema, json("{\"v\":0}"))).isNotEmpty();
        assertThat(service.validate(schema, json("{\"v\":0.1}"))).isEmpty();
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
