package com.platform.ingestion.transformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.common.FieldError;
import com.platform.ingestion.transformation.MappingDocument.FieldSpec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class MappingEngine {

    public MappedFields map(JsonNode payload, MappingDocument mapping) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, JsonNode> resolved = new TreeMap<>();

        for (FieldSpec spec : mapping.fields().values()) {
            try {
                JsonNode value = resolve(payload, spec);
                if (value == null) {
                    if (spec.required()) {
                        errors.add(new FieldError(
                                spec.target(), "No value found at '" + spec.path() + "' and no default configured"));
                    }
                    continue;
                }
                resolved.put(spec.target(), value);
            } catch (Transform.TransformFailure e) {
                errors.add(new FieldError(spec.target(), e.getMessage()));
            }
        }

        String deviceId = text(resolved, "device_id", errors);
        String sensorId = text(resolved, "sensor_id", errors);
        String unit = text(resolved, "unit", errors);
        BigDecimal value = number(resolved, "value", errors);
        Instant timestamp = timestamp(resolved, "event_timestamp", errors);
        Map<String, Object> metadata = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : resolved.entrySet()) {
            if (e.getKey().startsWith(MappingDocument.METADATA_PREFIX)) {
                metadata.put(
                        e.getKey().substring(MappingDocument.METADATA_PREFIX.length()),
                        scalar(e.getKey(), e.getValue(), errors));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(
                    ErrorCode.TRANSFORMATION_FAILED, "Payload could not be transformed to the canonical event", errors);
        }
        return new MappedFields(deviceId, sensorId, value, unit, timestamp, metadata);
    }

    private static JsonNode resolve(JsonNode payload, FieldSpec spec) {
        JsonNode value = spec.path().evaluate(payload);
        if (value != null && !value.isNull()) {
            for (Transform transform : spec.transforms()) {
                value = transform.apply(value);
            }
            if (!spec.valueMap().isEmpty()) {
                value = applyMap(value, spec.valueMap());
            }
        } else {
            value = null;
        }
        if (value == null) {
            value = spec.defaultValue();
        }
        return value;
    }

    private static JsonNode applyMap(JsonNode value, Map<String, String> valueMap) {
        if (value.isContainerNode()) {
            throw new Transform.TransformFailure("cannot map a non-scalar value");
        }
        String key =
                value.isNumber() ? value.decimalValue().stripTrailingZeros().toPlainString() : value.asText();
        String mapped = valueMap.get(key);
        if (mapped == null) {
            throw new Transform.TransformFailure("value '" + key + "' has no entry in the mapping table");
        }
        return TextNode.valueOf(mapped);
    }

    private static String text(Map<String, JsonNode> resolved, String target, List<FieldError> errors) {
        JsonNode node = resolved.get(target);
        if (node == null) {
            return null;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            errors.add(new FieldError(
                    target, "Must resolve to a non-empty string (use the toString transform for numbers)"));
            return null;
        }
        return node.asText();
    }

    private static BigDecimal number(Map<String, JsonNode> resolved, String target, List<FieldError> errors) {
        JsonNode node = resolved.get(target);
        if (node == null) {
            return null;
        }
        if (!node.isNumber()) {
            errors.add(new FieldError(target, "Must resolve to a number (use the toNumber transform)"));
            return null;
        }
        return normalise(node.decimalValue());
    }

    private static Instant timestamp(Map<String, JsonNode> resolved, String target, List<FieldError> errors) {
        JsonNode node = resolved.get(target);
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            try {
                return Instant.parse(node.asText());
            } catch (DateTimeParseException e) {
                // fall through to the error below
            }
        }
        errors.add(new FieldError(
                target, "Must resolve to an ISO-8601 UTC instant (use isoTimestamp, epochSeconds or epochMillis)"));
        return null;
    }

    private static Object scalar(String target, JsonNode node, List<FieldError> errors) {
        if (node.isNumber()) {
            return normalise(node.decimalValue());
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        errors.add(new FieldError(target, "Metadata values must be scalar"));
        return null;
    }

    static BigDecimal normalise(BigDecimal d) {
        BigDecimal stripped = d.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
