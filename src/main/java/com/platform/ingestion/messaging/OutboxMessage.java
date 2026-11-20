package com.platform.ingestion.messaging;

public record OutboxMessage(
        String topic, String key, String payload, String eventId, String tenantId, String schemaVersion) {}
