package com.platform.ingestion.transformation;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks that a {@link PathExpression} can address something in a (self-contained) JSON schema document by
 * walking properties and items, following local {@code #/...} references and allOf/oneOf/anyOf branches.
 */
final class SchemaPathResolver {

    private SchemaPathResolver() {}

    static boolean exists(JsonNode schemaDocument, PathExpression path) {
        List<JsonNode> current = List.of(schemaDocument);
        for (PathExpression.Segment segment : path.segments()) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                for (JsonNode candidate : expand(schemaDocument, node, new HashSet<>())) {
                    JsonNode child;
                    if (segment instanceof PathExpression.Field f) {
                        JsonNode props = candidate.get("properties");
                        child = props == null ? null : props.get(f.name());
                    } else {
                        child = candidate.get("items");
                    }
                    if (child != null && child.isObject()) {
                        next.add(child);
                    }
                }
            }
            if (next.isEmpty()) {
                return false;
            }
            current = next;
        }
        return true;
    }

    /** Resolves $ref and composition keywords into the list of schemas that contribute properties/items. */
    private static List<JsonNode> expand(JsonNode root, JsonNode schema, Set<String> visitedRefs) {
        List<JsonNode> result = new ArrayList<>();
        result.add(schema);
        JsonNode ref = schema.get("$ref");
        if (ref != null && ref.isTextual() && ref.asText().startsWith("#/") && visitedRefs.add(ref.asText())) {
            JsonNode target = root.at(JsonPointer.compile(ref.asText().substring(1)));
            if (!target.isMissingNode() && target.isObject()) {
                result.addAll(expand(root, target, visitedRefs));
            }
        }
        for (String keyword : new String[] {"allOf", "oneOf", "anyOf"}) {
            JsonNode branches = schema.get(keyword);
            if (branches != null && branches.isArray()) {
                for (JsonNode branch : branches) {
                    if (branch.isObject()) {
                        result.addAll(expand(root, branch, visitedRefs));
                    }
                }
            }
        }
        return result;
    }
}
