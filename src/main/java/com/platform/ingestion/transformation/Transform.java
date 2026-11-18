package com.platform.ingestion.transformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The fixed set of value transforms a mapping may use. */
public enum Transform {
    TRIM("trim", v -> TextNode.valueOf(requireText(v).trim())),
    LOWERCASE("lowercase", v -> TextNode.valueOf(requireText(v).toLowerCase(Locale.ROOT))),
    UPPERCASE("uppercase", v -> TextNode.valueOf(requireText(v).toUpperCase(Locale.ROOT))),
    TO_NUMBER("toNumber", Transform::toNumber),
    TO_STRING("toString", Transform::toStringValue),
    ISO_TIMESTAMP("isoTimestamp", Transform::isoTimestamp),
    EPOCH_SECONDS(
            "epochSeconds",
            v -> TextNode.valueOf(
                    Instant.ofEpochSecond(epoch(v).longValueExact()).toString())),
    EPOCH_MILLIS(
            "epochMillis",
            v -> TextNode.valueOf(
                    Instant.ofEpochMilli(epoch(v).longValueExact()).toString()));

    private static final Map<String, Transform> BY_NAME =
            Arrays.stream(values()).collect(Collectors.toMap(t -> t.externalName, t -> t));

    private final String externalName;
    private final Function<JsonNode, JsonNode> function;

    Transform(String externalName, Function<JsonNode, JsonNode> function) {
        this.externalName = externalName;
        this.function = function;
    }

    public String externalName() {
        return externalName;
    }

    public static Optional<Transform> byName(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    public static Set<String> names() {
        return BY_NAME.keySet();
    }

    /** @throws TransformFailure when the value cannot be transformed. */
    public JsonNode apply(JsonNode value) {
        try {
            return function.apply(value);
        } catch (TransformFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TransformFailure(externalName + " could not be applied to the value");
        }
    }

    /** Raised for data problems (not mapping problems). */
    public static final class TransformFailure extends RuntimeException {
        public TransformFailure(String message) {
            super(message);
        }
    }

    private static String requireText(JsonNode v) {
        if (!v.isTextual()) {
            throw new TransformFailure("expected a string value");
        }
        return v.asText();
    }

    private static JsonNode toNumber(JsonNode v) {
        if (v.isNumber()) {
            return v;
        }
        if (v.isTextual()) {
            try {
                return DecimalNode.valueOf(new BigDecimal(v.asText().trim()));
            } catch (NumberFormatException e) {
                throw new TransformFailure("'" + v.asText() + "' is not a number");
            }
        }
        throw new TransformFailure("cannot convert value to a number");
    }

    private static JsonNode toStringValue(JsonNode v) {
        if (v.isTextual()) {
            return v;
        }
        if (v.isNumber()) {
            return TextNode.valueOf(v.decimalValue().stripTrailingZeros().toPlainString());
        }
        if (v.isBoolean()) {
            return TextNode.valueOf(v.asText());
        }
        throw new TransformFailure("cannot convert value to a string");
    }

    private static JsonNode isoTimestamp(JsonNode v) {
        String text = requireText(v).trim();
        try {
            return TextNode.valueOf(OffsetDateTime.parse(text).toInstant().toString());
        } catch (DateTimeParseException e) {
            throw new TransformFailure("'" + text + "' is not an ISO-8601 timestamp with offset");
        }
    }

    private static BigDecimal epoch(JsonNode v) {
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isTextual()) {
            return new BigDecimal(v.asText().trim());
        }
        throw new TransformFailure("expected a numeric epoch value");
    }

    static JsonNodeFactory nodes() {
        return JsonNodeFactory.instance;
    }
}
