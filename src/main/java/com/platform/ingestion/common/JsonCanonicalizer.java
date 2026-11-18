package com.platform.ingestion.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic JSON serialisation: object keys sorted, no whitespace, numbers in plain normalised
 * form (1, 1.0 and 1.00 are the same). Used to hash request payloads for idempotency.
 */
public final class JsonCanonicalizer {

    private JsonCanonicalizer() {}

    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            sb.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(TextNode.valueOf(name)).append(':');
                write(node.get(name), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(node.get(i), sb);
            }
            sb.append(']');
        } else if (node.isNumber()) {
            if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
                throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Numeric values must be finite");
            }
            BigDecimal d = node.decimalValue().stripTrailingZeros();
            sb.append(d.signum() == 0 ? "0" : d.toPlainString());
        } else if (node.isTextual()) {
            sb.append(node.toString());
        } else {
            sb.append(node.asText());
        }
    }
}
