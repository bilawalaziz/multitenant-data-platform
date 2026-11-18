package com.platform.ingestion.transformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parsed (syntactically valid) mapping: canonical target name to field specification. */
public record MappingDocument(Map<String, FieldSpec> fields) {

    public static final Set<String> REQUIRED_TARGETS =
            Set.of("device_id", "sensor_id", "value", "unit", "event_timestamp");
    public static final String METADATA_PREFIX = "metadata.";

    public record FieldSpec(
            String target,
            PathExpression path,
            List<Transform> transforms,
            Map<String, String> valueMap,
            JsonNode defaultValue) {

        public boolean required() {
            return REQUIRED_TARGETS.contains(target);
        }
    }
}
