package com.platform.ingestion.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class IngestionDtos {

    private IngestionDtos() {}

    /** Response of POST /ingestion/readings: 202 ACCEPTED, or 200 DUPLICATE for an idempotent replay. */
    public record IngestionResponse(
            String status,
            UUID ingestionId,
            UUID eventId,
            String tenantId,
            UUID contractId,
            int contractVersion,
            Instant receivedAt) {}

    public record IngestionResult(boolean duplicate, IngestionResponse response) {}

    public record IngestionSummary(
            UUID ingestionId, UUID eventId, String deviceId, String status, Instant receivedAt) {}

    public record IngestionDetail(
            UUID ingestionId,
            UUID eventId,
            String deviceId,
            String status,
            Instant receivedAt,
            String idempotencyKey,
            UUID contractVersionId,
            JsonNode canonicalEvent,
            JsonNode rawPayload) {}
}
