package com.platform.ingestion.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.common.FieldError;
import com.platform.ingestion.common.Hashing;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;

@Component
public class OpenApiContractParser {

    public static final int MAX_SPEC_BYTES = 512 * 1024;
    public static final String DEFAULT_INGESTION_PATH = "/readings";
    private static final String SUPPORTED_VERSION = "3.0.3";
    private static final Pattern INGESTION_PATH = Pattern.compile("^/[A-Za-z0-9._~/{}-]{0,198}$");

    public record ParsedContract(
            String format, String ingestionPath, JsonNode requestSchema, String checksumSha256, String specContent) {}

    private final ObjectMapper jsonMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final ObjectMapper yamlMapper =
            new YAMLMapper(YAMLFactory.builder().loaderOptions(yamlLimits()).build());

    private static LoaderOptions yamlLimits() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(10);
        options.setCodePointLimit(MAX_SPEC_BYTES);
        options.setNestingDepthLimit(50);
        options.setAllowDuplicateKeys(false);
        return options;
    }

    public ParsedContract parse(byte[] raw, String ingestionPath) {
        if (raw == null || raw.length == 0) {
            throw invalid("Specification is empty");
        }
        if (raw.length > MAX_SPEC_BYTES) {
            throw invalid("Specification exceeds " + (MAX_SPEC_BYTES / 1024) + " KB");
        }
        String path = ingestionPath == null || ingestionPath.isBlank() ? DEFAULT_INGESTION_PATH : ingestionPath.trim();
        if (!INGESTION_PATH.matcher(path).matches() || path.contains("..")) {
            throw invalid("ingestionPath must be an absolute API path such as /readings");
        }

        String text = decode(raw);
        boolean json = text.stripLeading().startsWith("{");
        JsonNode root = read(text, json);
        if (root == null || !root.isObject()) {
            throw invalid("Specification must be an object at the top level");
        }

        checkVersion(root);
        if (!root.path("info").isObject()) {
            throw invalid("'info' object is required");
        }
        if (!root.path("paths").isObject()) {
            throw invalid("'paths' object is required");
        }
        rejectExternalReferences(root, "$");

        JsonNode schema = extractRequestSchema(root, path);
        ObjectNode document = schema.deepCopy();
        JsonNode components = root.get("components");
        if (components != null && components.isObject()) {
            document.set("components", components.deepCopy());
        }
        verifyReferencesResolve(document);

        return new ParsedContract(json ? "JSON" : "YAML", path, document, Hashing.sha256Hex(raw), text);
    }

    private static String decode(byte[] raw) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException e) {
            throw invalid("Specification must be UTF-8 text");
        }
    }

    private JsonNode read(String text, boolean json) {
        try {
            return (json ? jsonMapper : yamlMapper).readTree(text);
        } catch (IOException | RuntimeException e) {
            throw invalid("Specification is not valid " + (json ? "JSON" : "YAML"));
        }
    }

    private static void checkVersion(JsonNode root) {
        if (root.has("swagger")) {
            throw invalid("Swagger/OpenAPI 2.0 documents are not supported; use OpenAPI " + SUPPORTED_VERSION);
        }
        JsonNode version = root.get("openapi");
        if (version == null || !version.isTextual()) {
            throw invalid("'openapi' version field is required");
        }
        if (!SUPPORTED_VERSION.equals(version.asText())) {
            throw invalid("Unsupported OpenAPI version '" + version.asText() + "'; only " + SUPPORTED_VERSION
                    + " is supported");
        }
    }

    /** Every $ref anywhere in the document must be a local pointer ("#/..."). */
    private static void rejectExternalReferences(JsonNode node, String location) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("$ref".equals(field.getKey())
                        && field.getValue().isTextual()
                        && !field.getValue().asText().startsWith("#/")) {
                    throw new ApiException(
                            ErrorCode.INVALID_CONTRACT,
                            "External or non-local $ref is not allowed: "
                                    + field.getValue().asText(),
                            List.of(new FieldError(location, "$ref must start with '#/'")));
                }
                rejectExternalReferences(field.getValue(), location + "." + field.getKey());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                rejectExternalReferences(node.get(i), location + "[" + i + "]");
            }
        }
    }

    private static JsonNode extractRequestSchema(JsonNode root, String path) {
        JsonNode pathItem = resolveLocal(root, root.path("paths").get(path));
        if (pathItem == null || !pathItem.isObject()) {
            throw invalid("Path '" + path + "' is not defined in 'paths'");
        }
        JsonNode post = pathItem.get("post");
        if (post == null || !post.isObject()) {
            throw invalid("Operation POST " + path + " is not defined");
        }
        JsonNode requestBody = resolveLocal(root, post.get("requestBody"));
        if (requestBody == null || !requestBody.isObject()) {
            throw invalid("POST " + path + " must declare a requestBody");
        }
        JsonNode schema = requestBody.path("content").path("application/json").get("schema");
        if (schema == null || !schema.isObject()) {
            throw invalid("POST " + path + " must declare a requestBody with content 'application/json' and a schema");
        }
        return schema;
    }

    private static JsonNode resolveLocal(JsonNode root, JsonNode node) {
        if (node != null
                && node.isObject()
                && node.has("$ref")
                && node.get("$ref").isTextual()) {
            JsonNode target =
                    root.at(JsonPointer.compile(node.get("$ref").asText().substring(1)));
            return target.isMissingNode() ? null : target;
        }
        return node;
    }

    /** The stored schema document must be self-contained: all references have to resolve inside it. */
    private static void verifyReferencesResolve(JsonNode document) {
        List<FieldError> unresolved = new ArrayList<>();
        collectUnresolved(document, document, unresolved);
        if (!unresolved.isEmpty()) {
            throw new ApiException(
                    ErrorCode.INVALID_CONTRACT,
                    "The request schema references definitions that do not exist or are outside components",
                    unresolved);
        }
    }

    private static void collectUnresolved(JsonNode root, JsonNode node, List<FieldError> unresolved) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null
                    && ref.isTextual()
                    && root.at(JsonPointer.compile(ref.asText().substring(1))).isMissingNode()) {
                unresolved.add(new FieldError(ref.asText(), "Reference cannot be resolved"));
            }
            node.elements().forEachRemaining(child -> collectUnresolved(root, child, unresolved));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectUnresolved(root, child, unresolved));
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_CONTRACT, message);
    }
}
