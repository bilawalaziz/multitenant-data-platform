package com.platform.ingestion.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.contract.OpenApiContractParser.ParsedContract;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OpenApiContractParserTest {

    private final OpenApiContractParser parser = new OpenApiContractParser();

    private static final String VALID_YAML =
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
                  responses:
                    '202': {description: ok}
            components:
              schemas:
                Reading:
                  type: object
                  required: [deviceId]
                  properties:
                    deviceId: {type: string}
            """;

    private ParsedContract parse(String spec) {
        return parser.parse(spec.getBytes(StandardCharsets.UTF_8), null);
    }

    private void assertInvalid(String spec) {
        assertThatThrownBy(() -> parse(spec)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                .isEqualTo(ErrorCode.INVALID_CONTRACT));
    }

    @Test
    void acceptsOpenApi303YamlAndJsonAndBuildsSelfContainedSchema() {
        ParsedContract parsed = parse(VALID_YAML);

        assertThat(parsed.format()).isEqualTo("YAML");
        assertThat(parsed.ingestionPath()).isEqualTo("/readings");
        assertThat(parsed.requestSchema().get("$ref").asText()).isEqualTo("#/components/schemas/Reading");
        assertThat(parsed.requestSchema()
                        .at("/components/schemas/Reading/properties/deviceId/type")
                        .asText())
                .isEqualTo("string");

        String json =
                """
                {"openapi":"3.0.3","info":{"title":"T","version":"1"},
                 "paths":{"/readings":{"post":{"requestBody":{"content":{"application/json":{"schema":
                   {"type":"object","properties":{"a":{"type":"string"}}}}}}}}}}
                """;
        ParsedContract fromJson = parse(json);
        assertThat(fromJson.format()).isEqualTo("JSON");
        assertThat(fromJson.requestSchema().at("/properties/a/type").asText()).isEqualTo("string");
    }

    @Test
    void checksumIsStableAndContentSensitive() {
        assertThat(parse(VALID_YAML).checksumSha256())
                .hasSize(64)
                .isEqualTo(parse(VALID_YAML).checksumSha256());
        assertThat(parse(VALID_YAML.replace("deviceId: {type: string}", "deviceId: {type: integer}"))
                        .checksumSha256())
                .isNotEqualTo(parse(VALID_YAML).checksumSha256());
    }

    static Stream<Arguments> invalidSpecs() {
        StringBuilder aliases = new StringBuilder("openapi: 3.0.3\ninfo: &a {title: T}\npaths: {}\nx:\n");
        for (int i = 0; i < 30; i++) {
            aliases.append("  - *a\n");
        }
        String noOperation =
                """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /readings:
                    get:
                      responses: {}
                """;
        String postOnOtherPath =
                """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /other:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object}
                """;
        String noJsonBody =
                """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /readings:
                    post:
                      requestBody:
                        content:
                          text/plain:
                            schema: {type: string}
                """;
        String externalRefElsewhere = VALID_YAML
                + """
                    Other:
                      type: object
                      properties:
                        x:
                          $ref: 'http://evil.example/schema.json'
                """;
        return Stream.of(
                Arguments.of("openapi 3.1.0", VALID_YAML.replace("3.0.3", "3.1.0")),
                Arguments.of("swagger 2.0", "swagger: '2.0'\ninfo: {title: T, version: '1'}\npaths: {}\n"),
                Arguments.of("https ref", VALID_YAML.replace("#/components/schemas/Reading", "https://evil/x.json")),
                Arguments.of("file ref", VALID_YAML.replace("#/components/schemas/Reading", "other.yaml#/X")),
                Arguments.of("external ref elsewhere", externalRefElsewhere),
                Arguments.of(
                        "dangling ref",
                        VALID_YAML.replace("#/components/schemas/Reading", "#/components/schemas/Missing")),
                Arguments.of("no POST operation", noOperation),
                Arguments.of("POST on another path", postOnOtherPath),
                Arguments.of("no json body", noJsonBody),
                Arguments.of("missing info", "openapi: 3.0.3\npaths: {}\n"),
                Arguments.of("missing paths", "openapi: 3.0.3\ninfo: {title: T, version: '1'}\n"),
                Arguments.of("not json", "{ not json"),
                Arguments.of("yaml list", "- just\n- a list\n"),
                Arguments.of("alias bomb", aliases.toString()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSpecs")
    void rejectsInvalidSpecs(String name, String spec) {
        assertInvalid(spec);
    }

    @Test
    void rejectsOversizedSpec() {
        byte[] big = new byte[OpenApiContractParser.MAX_SPEC_BYTES + 1];
        Arrays.fill(big, (byte) 'a');
        assertThatThrownBy(() -> parser.parse(big, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void honoursCustomIngestionPathAndRejectsSuspiciousOne() {
        String spec = VALID_YAML.replace("/readings:", "/v2/readings:");
        assertThat(parser.parse(spec.getBytes(StandardCharsets.UTF_8), "/v2/readings")
                        .ingestionPath())
                .isEqualTo("/v2/readings");
        assertThatThrownBy(() -> parser.parse(VALID_YAML.getBytes(StandardCharsets.UTF_8), "/../etc"))
                .isInstanceOf(ApiException.class);
    }
}
