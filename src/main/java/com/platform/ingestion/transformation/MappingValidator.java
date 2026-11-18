package com.platform.ingestion.transformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.common.FieldError;
import com.platform.ingestion.transformation.MappingDocument.FieldSpec;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses and validates a mapping document: known targets and transforms, all required targets present,
 * valid path syntax, and every path must exist in the contract request schema (following local $refs).
 */
@Component
public class MappingValidator {

    private static final Pattern METADATA_NAME = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
    private static final Set<String> SPEC_KEYS = Set.of("path", "transforms", "map", "default");

    /** Syntax-only parse (used at runtime for already validated, stored mappings). */
    public MappingDocument parse(JsonNode mapping) {
        List<FieldError> errors = new ArrayList<>();
        MappingDocument doc = parse(mapping, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_MAPPING, "Mapping is invalid", errors);
        }
        return doc;
    }

    /** Full validation against the request schema document. */
    public MappingDocument validate(JsonNode mapping, JsonNode requestSchemaDocument) {
        List<FieldError> errors = new ArrayList<>();
        MappingDocument doc = parse(mapping, errors);
        if (errors.isEmpty()) {
            for (FieldSpec spec : doc.fields().values()) {
                if (!SchemaPathResolver.exists(requestSchemaDocument, spec.path())) {
                    errors.add(new FieldError(
                            spec.target(), "Path '" + spec.path() + "' does not exist in the contract request schema"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_MAPPING, "Mapping is invalid", errors);
        }
        return doc;
    }

    private MappingDocument parse(JsonNode mapping, List<FieldError> errors) {
        Map<String, FieldSpec> fields = new LinkedHashMap<>();
        if (mapping == null || !mapping.isObject()) {
            errors.add(new FieldError("$", "Mapping must be a JSON object"));
            return new MappingDocument(fields);
        }
        Iterator<Map.Entry<String, JsonNode>> it = mapping.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String target = entry.getKey();
            if (!isKnownTarget(target)) {
                errors.add(new FieldError(target, "Unknown mapping target"));
                continue;
            }
            FieldSpec spec = parseSpec(target, entry.getValue(), errors);
            if (spec != null) {
                fields.put(target, spec);
            }
        }
        for (String required : MappingDocument.REQUIRED_TARGETS) {
            if (!mapping.has(required)) {
                errors.add(new FieldError(required, "Required mapping target is missing"));
            }
        }
        return new MappingDocument(fields);
    }

    private static boolean isKnownTarget(String target) {
        if (MappingDocument.REQUIRED_TARGETS.contains(target)) {
            return true;
        }
        return target.startsWith(MappingDocument.METADATA_PREFIX)
                && METADATA_NAME
                        .matcher(target.substring(MappingDocument.METADATA_PREFIX.length()))
                        .matches();
    }

    private FieldSpec parseSpec(String target, JsonNode node, List<FieldError> errors) {
        if (node.isTextual()) {
            return build(target, node.asText(), null, null, null, errors);
        }
        if (!node.isObject()) {
            errors.add(new FieldError(target, "Mapping entry must be a path string or an object"));
            return null;
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!SPEC_KEYS.contains(name)) {
                errors.add(new FieldError(target, "Unknown mapping property '" + name + "'"));
            }
        });
        JsonNode path = node.get("path");
        if (path == null || !path.isTextual()) {
            errors.add(new FieldError(target, "'path' is required and must be a string"));
            return null;
        }
        return build(target, path.asText(), node.get("transforms"), node.get("map"), node.get("default"), errors);
    }

    private FieldSpec build(
            String target,
            String path,
            JsonNode transformsNode,
            JsonNode mapNode,
            JsonNode defaultNode,
            List<FieldError> errors) {
        int before = errors.size();
        PathExpression expression = null;
        try {
            expression = PathExpression.parse(path);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError(target, "Invalid path '" + path + "': " + e.getMessage()));
        }
        List<Transform> transforms = new ArrayList<>();
        if (transformsNode != null && !transformsNode.isNull()) {
            if (!transformsNode.isArray()) {
                errors.add(new FieldError(target, "'transforms' must be an array of transform names"));
            } else {
                for (JsonNode t : transformsNode) {
                    Transform.byName(t.asText())
                            .ifPresentOrElse(
                                    transforms::add,
                                    () -> errors.add(new FieldError(
                                            target,
                                            "Unknown transform '" + t.asText() + "'. Allowed: "
                                                    + new TreeSet<>(Transform.names()))));
                }
            }
        }
        Map<String, String> valueMap = new LinkedHashMap<>();
        if (mapNode != null && !mapNode.isNull()) {
            if (!mapNode.isObject()) {
                errors.add(new FieldError(target, "'map' must be an object of string values"));
            } else {
                mapNode.fields().forEachRemaining(e -> {
                    if (e.getValue().isTextual()) {
                        valueMap.put(e.getKey(), e.getValue().asText());
                    } else {
                        errors.add(new FieldError(target, "'map' values must be strings"));
                    }
                });
            }
        }
        JsonNode defaultValue = null;
        if (defaultNode != null && !defaultNode.isNull()) {
            if (defaultNode.isContainerNode()) {
                errors.add(new FieldError(target, "'default' must be a scalar value"));
            } else {
                defaultValue = defaultNode;
            }
        }
        if (errors.size() > before) {
            return null;
        }
        return new FieldSpec(target, expression, List.copyOf(transforms), valueMap, defaultValue);
    }
}
