package com.platform.ingestion.ingestion;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.ingestion.transformation.MappedFields;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Builds the canonical event (snake_case JSON) as published to Kafka. */
public final class CanonicalEvent {

    public static final String SCHEMA_VERSION = "1.0";

    private CanonicalEvent() {}

    public static ObjectNode build(
            UUID eventId,
            UUID ingestionId,
            String tenantCode,
            UUID contractId,
            int contractVersion,
            MappedFields fields,
            Instant receivedAt) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode event = f.objectNode();
        event.put("event_id", eventId.toString());
        event.put("schema_version", SCHEMA_VERSION);
        event.put("tenant_id", tenantCode);
        event.put("ingestion_id", ingestionId.toString());
        event.put("contract_id", contractId.toString());
        event.put("contract_version", contractVersion);
        event.put("device_id", fields.deviceId());
        event.put("sensor_id", fields.sensorId());
        event.put("value", fields.value());
        event.put("unit", fields.unit());
        event.put("event_timestamp", fields.eventTimestamp().toString());
        event.put("received_at", receivedAt.toString());
        ObjectNode metadata = event.putObject("metadata");
        for (Map.Entry<String, Object> e : fields.metadata().entrySet()) {
            Object v = e.getValue();
            if (v instanceof BigDecimal d) {
                metadata.put(e.getKey(), d);
            } else if (v instanceof Boolean b) {
                metadata.put(e.getKey(), b);
            } else if (v != null) {
                metadata.put(e.getKey(), v.toString());
            }
        }
        return event;
    }
}
