package com.platform.ingestion.messaging;

import java.util.Map;

/** {@code traceHeaders} are propagation headers (traceparent, tracestate) added to the Kafka record as they are. */
public record OutboxMessage(
        String topic,
        String key,
        String payload,
        String eventId,
        String tenantId,
        String schemaVersion,
        Map<String, String> traceHeaders) {

    public OutboxMessage(
            String topic, String key, String payload, String eventId, String tenantId, String schemaVersion) {
        this(topic, key, payload, eventId, tenantId, schemaVersion, Map.of());
    }
}
