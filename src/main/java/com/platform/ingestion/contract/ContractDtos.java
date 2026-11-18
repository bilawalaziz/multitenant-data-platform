package com.platform.ingestion.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContractDtos {

    private ContractDtos() {}

    /** One version of a contract. {@code version} and {@code contractVersion} carry the same number. */
    public record ContractVersionResponse(
            UUID contractId,
            String name,
            int version,
            int contractVersion,
            UUID versionId,
            String status,
            String specFormat,
            String checksumSha256,
            String ingestionPath,
            Instant createdAt,
            Instant activatedAt,
            JsonNode requestSchema,
            JsonNode mapping) {

        static ContractVersionResponse of(Contract c, ContractVersion v) {
            return new ContractVersionResponse(
                    c.getId(),
                    c.getName(),
                    v.getVersion(),
                    v.getVersion(),
                    v.getId(),
                    v.getStatus().name(),
                    v.getSpecFormat(),
                    v.getChecksumSha256(),
                    v.getIngestionPath(),
                    v.getCreatedAt(),
                    v.getActivatedAt(),
                    null,
                    null);
        }

        static ContractVersionResponse detailed(Contract c, ContractVersion v, JsonNode mapping) {
            return new ContractVersionResponse(
                    c.getId(),
                    c.getName(),
                    v.getVersion(),
                    v.getVersion(),
                    v.getId(),
                    v.getStatus().name(),
                    v.getSpecFormat(),
                    v.getChecksumSha256(),
                    v.getIngestionPath(),
                    v.getCreatedAt(),
                    v.getActivatedAt(),
                    v.getRequestSchema(),
                    mapping);
        }
    }

    public record ContractSummaryResponse(
            UUID contractId, String name, Instant createdAt, int latestVersion, Integer activeVersion) {}

    public record ContractDetailResponse(
            UUID contractId,
            String name,
            Instant createdAt,
            Integer activeVersion,
            List<ContractVersionResponse> versions) {}
}
