package com.platform.ingestion.transformation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.ingestion.CanonicalEvent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MappingEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MappingValidator validator = new MappingValidator();
    private final MappingEngine engine = new MappingEngine();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode demo(String relative) throws Exception {
        return MAPPER.readTree(Files.readString(Path.of("demo").resolve(relative)));
    }

    private MappedFields map(String mapping, String payload) {
        return engine.map(json(payload), validator.parse(json(mapping)));
    }

    private static final String BASE_MAPPING =
            """
            {"device_id":"$.d","sensor_id":"$.s","value":"$.v","unit":"$.u","event_timestamp":"$.t"
            %s}
            """;

    @Test
    void alphaAndBetaPayloadsProduceTheSameCanonicalFieldsAndValues() throws Exception {
        MappingDocument alpha = validator.parse(demo("tenant-alpha/mapping.json"));
        MappingDocument beta = validator.parse(demo("tenant-beta/mapping.json"));

        MappedFields a = engine.map(
                json(
                        """
                {"deviceId":"MACHINE-001","sensorId":" TEMP-001 ","temperature":92.5,"unit":"celsius",
                 "recordedAt":"2026-11-17T14:30:00Z","location":{"site":"plant-1"}}"""),
                alpha);
        MappedFields b = engine.map(
                json(
                        """
                {"device_code":"MACHINE-001","sensor_code":"TEMP-001","reading":"92.50","measurement_unit":"CELSIUS",
                 "timestamp":"2026-11-17T16:30:00+02:00","diagnostics":{"firmware":"2.4.1","battery_pct":87}}"""),
                beta);

        assertThat(a.deviceId()).isEqualTo(b.deviceId());
        assertThat(a.sensorId()).isEqualTo("TEMP-001").isEqualTo(b.sensorId());
        assertThat(a.value()).isEqualTo(new BigDecimal("92.5")).isEqualTo(b.value());
        assertThat(a.unit()).isEqualTo("CELSIUS").isEqualTo(b.unit());
        assertThat(a.eventTimestamp())
                .isEqualTo(Instant.parse("2026-11-17T14:30:00Z"))
                .isEqualTo(b.eventTimestamp());

        UUID ids = UUID.randomUUID();
        Instant received = Instant.parse("2026-11-17T14:30:01.123Z");
        JsonNode ca = CanonicalEvent.build(ids, ids, "tenant-alpha", ids, 1, a, received);
        JsonNode cb = CanonicalEvent.build(ids, ids, "tenant-beta", ids, 1, b, received);
        assertThat(ca.fieldNames()).toIterable().containsExactlyElementsOf(() -> cb.fieldNames());
        assertThat(ca.get("event_timestamp").asText()).isEqualTo("2026-11-17T14:30:00Z");
        assertThat(ca.get("value").decimalValue()).isEqualByComparingTo("92.5");
        assertThat(ca.at("/metadata/site").asText()).isEqualTo("plant-1");
        assertThat(cb.at("/metadata/firmware").asText()).isEqualTo("2.4.1");
        assertThat(cb.at("/metadata/battery_pct").decimalValue()).isEqualByComparingTo("87");
    }

    @Test
    void metadataIsOmittedWhenAbsentAndDefaultsApply() {
        String payload = "{\"d\":\"M1\",\"s\":\"S\",\"v\":1,\"u\":\"C\",\"t\":\"2026-11-17T14:30:00Z\"}";
        assertThat(map(BASE_MAPPING.formatted(",\"metadata.battery\":\"$.diag.battery\""), payload)
                        .metadata())
                .isEmpty();
        assertThat(map(
                                BASE_MAPPING.formatted(
                                        ",\"metadata.site\":{\"path\":\"$.site\",\"default\":\"unknown\"}"),
                                payload)
                        .metadata())
                .containsEntry("site", "unknown");
    }

    @Test
    void timestampTransformsArrayPathsAndOffsetNormalisation() {
        MappedFields offset = map(
                BASE_MAPPING.formatted("").replace("\"$.t\"", "{\"path\":\"$.t\",\"transforms\":[\"isoTimestamp\"]}"),
                "{\"d\":\"M1\",\"s\":\"S\",\"v\":1,\"u\":\"C\",\"t\":\"2026-11-17T16:30:00+02:00\"}");
        assertThat(offset.eventTimestamp()).isEqualTo(Instant.parse("2026-11-17T14:30:00Z"));

        String mapping =
                """
                {"device_id":"$.d","sensor_id":"$.s[1]","value":{"path":"$.v","transforms":["toNumber"]},
                 "unit":{"path":"$.u","transforms":["uppercase"]},
                 "event_timestamp":{"path":"$.t","transforms":["epochSeconds"]}}""";
        MappedFields f = map(mapping, "{\"d\":\"M1\",\"s\":[\"a\",\"b\"],\"v\":\"3\",\"u\":\"c\",\"t\":1794061800}");
        assertThat(f.sensorId()).isEqualTo("b");
        assertThat(f.value()).isEqualTo(new BigDecimal("3"));
        assertThat(f.unit()).isEqualTo("C");
        assertThat(f.eventTimestamp()).isEqualTo(Instant.ofEpochSecond(1794061800L));

        MappedFields g = map(
                mapping.replace("epochSeconds", "epochMillis"),
                "{\"d\":\"M1\",\"s\":[\"a\",\"b\"],\"v\":3,\"u\":\"c\",\"t\":1794061800000}");
        assertThat(g.eventTimestamp()).isEqualTo(Instant.ofEpochMilli(1794061800000L));
    }

    static Stream<Arguments> dataProblems() {
        String unmapped =
                BASE_MAPPING.formatted("").replace("\"$.u\"", "{\"path\":\"$.u\",\"map\":{\"c\":\"CELSIUS\"}}");
        return Stream.of(
                Arguments.of(
                        "missing required value",
                        BASE_MAPPING.formatted(""),
                        "{\"d\":\"M1\",\"v\":1,\"u\":\"C\",\"t\":\"2026-11-17T14:30:00Z\"}",
                        "sensor_id"),
                Arguments.of(
                        "value without a map entry",
                        unmapped,
                        "{\"d\":\"M1\",\"s\":\"S\",\"v\":1,\"u\":\"k\",\"t\":\"2026-11-17T14:30:00Z\"}",
                        "unit"),
                Arguments.of(
                        "non-numeric value without toNumber",
                        BASE_MAPPING.formatted(""),
                        "{\"d\":\"M1\",\"s\":\"S\",\"v\":\"12\",\"u\":\"C\",\"t\":\"2026-11-17T14:30:00Z\"}",
                        "value"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dataProblems")
    void dataProblemsFailTransformation(String name, String mapping, String payload, String field) {
        assertThatThrownBy(() -> map(mapping, payload)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.TRANSFORMATION_FAILED);
            assertThat(e.errors()).anyMatch(f -> f.field().equals(field));
        });
    }
}
