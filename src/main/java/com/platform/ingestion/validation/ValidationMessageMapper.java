package com.platform.ingestion.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.ValidationMessage;
import com.platform.ingestion.common.FieldError;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ValidationMessageMapper {

    private ValidationMessageMapper() {}

    static List<FieldError> map(Set<ValidationMessage> messages) {
        return messages.stream()
                .map(ValidationMessageMapper::toFieldError)
                .distinct()
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
                .toList();
    }

    private static FieldError toFieldError(ValidationMessage m) {
        String type = m.getType() == null ? "" : m.getType();
        String location = fieldPath(m.getInstanceLocation().toString());
        JsonNode schema = m.getSchemaNode();
        return switch (type) {
            case "required" -> {
                String property = m.getProperty();
                yield new FieldError(join(location, property), "Missing required field");
            }
            case "type" -> new FieldError(location, typeMessage(schema));
            case "minimum" -> new FieldError(location, "Value must be greater than or equal to " + text(schema));
            case "maximum" -> new FieldError(location, "Value must be less than or equal to " + text(schema));
            case "exclusiveMinimum" -> new FieldError(location, "Value must be greater than " + text(schema));
            case "exclusiveMaximum" -> new FieldError(location, "Value must be less than " + text(schema));
            case "pattern" -> new FieldError(location, "Value does not match the required pattern");
            case "minLength" -> new FieldError(location, "Must be at least " + text(schema) + " characters long");
            case "maxLength" -> new FieldError(location, "Must be at most " + text(schema) + " characters long");
            case "minItems" -> new FieldError(location, "Must contain at least " + text(schema) + " items");
            case "maxItems" -> new FieldError(location, "Must contain at most " + text(schema) + " items");
            case "enum" -> new FieldError(location, "Must be one of: " + enumValues(schema));
            case "format" -> new FieldError(location, "Invalid " + text(m.getSchemaNode()) + " format");
            case "additionalProperties" -> new FieldError(join(location, m.getProperty()), "Unknown property");
            default -> new FieldError(location, stripPrefix(m.getMessage()));
        };
    }

    private static String typeMessage(JsonNode schema) {
        String expected = schema == null ? "" : schema.asText("");
        return switch (expected) {
            case "number" -> "Expected numeric value";
            case "integer" -> "Expected integer value";
            case "string" -> "Expected string value";
            case "boolean" -> "Expected boolean value";
            case "array" -> "Expected array value";
            case "object" -> "Expected object value";
            default -> "Unexpected value type";
        };
    }

    private static String enumValues(JsonNode schema) {
        if (schema == null || !schema.isArray()) {
            return "";
        }
        return Stream.iterate(0, i -> i + 1)
                .limit(schema.size())
                .map(i -> schema.get(i).asText())
                .collect(Collectors.joining(", "));
    }

    private static String text(JsonNode node) {
        return node == null ? "" : node.asText();
    }

    /** Strips the leading "$." from a path, so "$.tags[2]" becomes "tags[2]"; the root stays "$". */
    static String fieldPath(String jsonPath) {
        if (jsonPath == null || jsonPath.equals("$") || jsonPath.isEmpty()) {
            return "$";
        }
        return jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
    }

    private static String join(String location, String property) {
        if (property == null || property.isEmpty()) {
            return location;
        }
        return "$".equals(location) ? property : location + "." + property;
    }

    private static String stripPrefix(String message) {
        int idx = message == null ? -1 : message.indexOf(": ");
        return idx >= 0 ? message.substring(idx + 2) : String.valueOf(message);
    }
}
