package com.platform.ingestion.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.tenant.TenantContext;
import com.platform.ingestion.transformation.MappingDocument;
import com.platform.ingestion.transformation.MappingValidator;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the tenant's ACTIVE contract version together with its parsed mapping for ingestion. */
@Service
public class ContractResolver {

    public record ActiveContract(
            UUID contractId,
            UUID versionId,
            int version,
            String name,
            JsonNode requestSchema,
            MappingDocument mapping) {}

    private final ContractVersionRepository versions;
    private final ContractRepository contracts;
    private final ContractMappingRepository mappings;
    private final MappingValidator mappingValidator;

    public ContractResolver(
            ContractVersionRepository versions,
            ContractRepository contracts,
            ContractMappingRepository mappings,
            MappingValidator mappingValidator) {
        this.versions = versions;
        this.contracts = contracts;
        this.mappings = mappings;
        this.mappingValidator = mappingValidator;
    }

    @Transactional(readOnly = true)
    public ActiveContract resolveActive(TenantContext tenant) {
        ContractVersion version = versions.findByTenantIdAndStatus(tenant.id(), ContractStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NO_ACTIVE_CONTRACT, "Tenant has no active contract; upload and activate one first"));
        Contract contract = contracts
                .findByIdAndTenantId(version.getContractId(), tenant.id())
                .orElseThrow(() -> new ApiException(ErrorCode.NO_ACTIVE_CONTRACT, "Active contract is unavailable"));
        ContractMapping mapping = mappings.findByContractVersionIdAndTenantId(version.getId(), tenant.id())
                .orElseThrow(() -> new ApiException(ErrorCode.NO_ACTIVE_CONTRACT, "Active contract has no mapping"));
        return new ActiveContract(
                contract.getId(),
                version.getId(),
                version.getVersion(),
                contract.getName(),
                version.getRequestSchema(),
                mappingValidator.parse(mapping.getMapping()));
    }
}
