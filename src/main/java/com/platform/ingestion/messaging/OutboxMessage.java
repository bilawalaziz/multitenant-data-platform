package com.platform.ingestion.messaging;

/** What gets published to Kafka: topic, key (device id), JSON value and the header values. */
public record OutboxMessage(
        String topic, String key, String payload, String eventId, String tenantId, String schemaVersion) {}
