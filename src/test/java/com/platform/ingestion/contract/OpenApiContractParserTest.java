package com.platform.ingestion.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.contract.OpenApiContractParser.ParsedContract;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

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
    void acceptsOpenApi303YamlAndBuildsSelfContainedSchema() {
        ParsedContract parsed = parse(VALID_YAML);

        assertThat(parsed.format()).isEqualTo("YAML");
        assertThat(parsed.ingestionPath()).isEqualTo("/readings");
        assertThat(parsed.requestSchema().get("$ref").asText()).isEqualTo("#/components/schemas/Reading");
        assertThat(parsed.requestSchema()
                        .at("/components/schemas/Reading/properties/deviceId/type")
                        .asText())
                .isEqualTo("string");
        assertThat(parsed.checksumSha256()).hasSize(64);
    }

    @Test
    void acceptsJsonSpecs() {
        String json =
                """
                {"openapi":"3.0.3","info":{"title":"T","version":"1"},
                 "paths":{"/readings":{"post":{"requestBody":{"content":{"application/json":{"schema":
                   {"type":"object","properties":{"a":{"type":"string"}}}}}}}}}}
                """;
        ParsedContract parsed = parse(json);
        assertThat(parsed.format()).isEqualTo("JSON");
        assertThat(parsed.requestSchema().at("/properties/a/type").asText()).isEqualTo("string");
    }

    @Test
    void checksumIsStableAndContentSensitive() {
        assertThat(parse(VALID_YAML).checksumSha256())
                .isEqualTo(parse(VALID_YAML).checksumSha256());
        assertThat(parse(VALID_YAML.replace("deviceId: {type: string}", "deviceId: {type: integer}"))
                        .checksumSha256())
                .isNotEqualTo(parse(VALID_YAML).checksumSha256());
    }

    @Test
    void rejectsOpenApi310() {
        assertInvalid(VALID_YAML.replace("3.0.3", "3.1.0"));
    }

    @Test
    void rejectsSwagger20() {
        assertInvalid(
                """
                swagger: '2.0'
                info: {title: T, version: '1'}
                paths: {}
                """);
    }

    @Test
    void rejectsExternalHttpsRef() {
        assertInvalid(VALID_YAML.replace("#/components/schemas/Reading", "https://evil/x.json"));
    }

    @Test
    void rejectsRelativeFileRef() {
        assertInvalid(VALID_YAML.replace("#/components/schemas/Reading", "other.yaml#/X"));
    }

    @Test
    void rejectsExternalRefAnywhereInDocument() {
        assertInvalid(
                VALID_YAML
                        + """
                    Other:
                      type: object
                      properties:
                        x:
                          $ref: 'http://evil.example/schema.json'
                """);
    }

    @Test
    void rejectsDanglingLocalRef() {
        assertInvalid(VALID_YAML.replace("#/components/schemas/Reading", "#/components/schemas/Missing"));
    }

    @Test
    void rejectsMissingOperation() {
        assertInvalid(
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
                """);
        assertInvalid(
                """
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /readings:
                    get:
                      responses: {}
                """);
    }

    @Test
    void rejectsMissingJsonRequestBody() {
        assertInvalid(
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
                """);
    }

    @Test
    void rejectsMissingInfoAndPaths() {
        assertInvalid("openapi: 3.0.3\npaths: {}\n");
        assertInvalid("openapi: 3.0.3\ninfo: {title: T, version: '1'}\n");
    }

    @Test
    void rejectsGarbageAndOversizedInput() {
        assertInvalid("{ not json");
        assertInvalid("- just\n- a list\n");
        byte[] big = new byte[OpenApiContractParser.MAX_SPEC_BYTES + 1];
        Arrays.fill(big, (byte) 'a');
        assertThatThrownBy(() -> parser.parse(big, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsYamlAliasBombs() {
        StringBuilder sb = new StringBuilder("openapi: 3.0.3\ninfo: &a {title: T}\npaths: {}\nx:\n");
        for (int i = 0; i < 30; i++) {
            sb.append("  - *a\n");
        }
        assertInvalid(sb.toString());
    }

    @Test
    void usesCustomIngestionPath() {
        String spec = VALID_YAML.replace("/readings:", "/v2/readings:");
        ParsedContract parsed = parser.parse(spec.getBytes(StandardCharsets.UTF_8), "/v2/readings");
        assertThat(parsed.ingestionPath()).isEqualTo("/v2/readings");
    }

    @Test
    void rejectsSuspiciousIngestionPath() {
        assertThatThrownBy(() -> parser.parse(VALID_YAML.getBytes(StandardCharsets.UTF_8), "/../etc"))
                .isInstanceOf(ApiException.class);
    }
}
