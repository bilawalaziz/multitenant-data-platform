package com.platform.ingestion.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.contract.ContractDtos.ContractDetailResponse;
import com.platform.ingestion.contract.ContractDtos.ContractSummaryResponse;
import com.platform.ingestion.contract.ContractDtos.ContractVersionResponse;
import com.platform.ingestion.contract.OpenApiContractParser.ParsedContract;
import com.platform.ingestion.tenant.TenantContext;
import com.platform.ingestion.tenant.TenantService;
import com.platform.ingestion.transformation.MappingValidator;
import com.platform.ingestion.validation.SchemaValidationService;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Contract registry: immutable versions per contract name, one ACTIVE version per tenant. */
@Service
public class ContractService {

    static final int MAX_MAPPING_BYTES = 64 * 1024;
    private static final Pattern CONTRACT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

    private final ContractRepository contracts;
    private final ContractVersionRepository versions;
    private final ContractMappingRepository mappings;
    private final OpenApiContractParser parser;
    private final MappingValidator mappingValidator;
    private final SchemaValidationService schemaValidation;
    private final TenantService tenantService;
    private final Clock clock;

    private final ObjectMapper mappingReader = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public ContractService(
            ContractRepository contracts,
            ContractVersionRepository versions,
            ContractMappingRepository mappings,
            OpenApiContractParser parser,
            MappingValidator mappingValidator,
            SchemaValidationService schemaValidation,
            TenantService tenantService,
            Clock clock) {
        this.contracts = contracts;
        this.versions = versions;
        this.mappings = mappings;
        this.parser = parser;
        this.mappingValidator = mappingValidator;
        this.schemaValidation = schemaValidation;
        this.tenantService = tenantService;
        this.clock = clock;
    }

    @Transactional
    public ContractVersionResponse upload(
            String tenantCode, String name, byte[] spec, byte[] mappingJson, String ingestionPath) {
        TenantContext tenant = tenantService.requireContext(tenantCode);
        if (name == null || !CONTRACT_NAME.matcher(name).matches()) {
            throw new ApiException(
                    ErrorCode.INVALID_CONTRACT, "Contract name must match ^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");
        }
        ParsedContract parsed = parser.parse(spec, ingestionPath);
        schemaValidation.assertCompilable(parsed.requestSchema());
        JsonNode mapping = readMapping(mappingJson);
        mappingValidator.validate(mapping, parsed.requestSchema());

        Contract contract = contracts
                .findWithLockByTenantIdAndName(tenant.id(), name)
                .orElseGet(() ->
                        contracts.saveAndFlush(new Contract(UUID.randomUUID(), tenant.id(), name, clock.instant())));
        int nextVersion = versions.maxVersion(contract.getId()) + 1;
        ContractVersion version = versions.saveAndFlush(new ContractVersion(
                UUID.randomUUID(),
                contract.getId(),
                tenant.id(),
                nextVersion,
                parsed.format(),
                parsed.specContent(),
                parsed.checksumSha256(),
                parsed.ingestionPath(),
                parsed.requestSchema(),
                clock.instant()));
        mappings.saveAndFlush(
                new ContractMapping(UUID.randomUUID(), version.getId(), tenant.id(), mapping, clock.instant()));
        return ContractVersionResponse.of(contract, version);
    }

    @Transactional(readOnly = true)
    public List<ContractSummaryResponse> list(String tenantCode) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        Map<UUID, List<ContractVersion>> byContract = versions.findByTenantId(tenantId).stream()
                .collect(Collectors.groupingBy(ContractVersion::getContractId));
        return contracts.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(c -> {
                    List<ContractVersion> vs = byContract.getOrDefault(c.getId(), List.of());
                    int latest = vs.stream()
                            .mapToInt(ContractVersion::getVersion)
                            .max()
                            .orElse(0);
                    Integer active = vs.stream()
                            .filter(v -> v.getStatus() == ContractStatus.ACTIVE)
                            .map(ContractVersion::getVersion)
                            .findFirst()
                            .orElse(null);
                    return new ContractSummaryResponse(c.getId(), c.getName(), c.getCreatedAt(), latest, active);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ContractDetailResponse detail(String tenantCode, UUID contractId) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        Contract contract = requireContract(contractId, tenantId);
        List<ContractVersionResponse> history =
                versions.findByContractIdAndTenantIdOrderByVersionDesc(contractId, tenantId).stream()
                        .map(v -> ContractVersionResponse.of(contract, v))
                        .toList();
        Integer active = history.stream()
                .filter(v -> "ACTIVE".equals(v.status()))
                .map(ContractVersionResponse::version)
                .findFirst()
                .orElse(null);
        return new ContractDetailResponse(
                contract.getId(), contract.getName(), contract.getCreatedAt(), active, history);
    }

    @Transactional(readOnly = true)
    public ContractVersionResponse versionDetail(String tenantCode, UUID contractId, int version) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        Contract contract = requireContract(contractId, tenantId);
        ContractVersion v = requireVersion(contractId, tenantId, version);
        JsonNode mapping = mappings.findByContractVersionIdAndTenantId(v.getId(), tenantId)
                .map(ContractMapping::getMapping)
                .orElse(null);
        return ContractVersionResponse.detailed(contract, v, mapping);
    }

    @Transactional(readOnly = true)
    public ContractVersionResponse active(String tenantCode) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        ContractVersion v = versions.findByTenantIdAndStatus(tenantId, ContractStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("Active contract"));
        Contract contract = requireContract(v.getContractId(), tenantId);
        JsonNode mapping = mappings.findByContractVersionIdAndTenantId(v.getId(), tenantId)
                .map(ContractMapping::getMapping)
                .orElse(null);
        return ContractVersionResponse.detailed(contract, v, mapping);
    }

    /** Re-validates the stored mapping, then atomically switches the tenant's ACTIVE version. */
    @Transactional
    public ContractVersionResponse activate(String tenantCode, UUID contractId, int version) {
        UUID tenantId = tenantService.requireContext(tenantCode).id();
        Contract contract = requireContract(contractId, tenantId);
        ContractVersion target = requireVersion(contractId, tenantId, version);
        if (target.getStatus() == ContractStatus.ACTIVE) {
            return ContractVersionResponse.of(contract, target);
        }
        JsonNode mapping = mappings.findByContractVersionIdAndTenantId(target.getId(), tenantId)
                .map(ContractMapping::getMapping)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_MAPPING, "Contract version has no mapping"));
        mappingValidator.validate(mapping, target.getRequestSchema());

        versions.deactivateActive(tenantId);
        ContractVersion fresh = versions.findById(target.getId()).orElseThrow();
        fresh.activate(clock.instant());
        versions.saveAndFlush(fresh);
        return ContractVersionResponse.of(contract, fresh);
    }

    private JsonNode readMapping(byte[] mappingJson) {
        if (mappingJson == null || mappingJson.length == 0) {
            throw new ApiException(ErrorCode.INVALID_MAPPING, "Mapping is required");
        }
        if (mappingJson.length > MAX_MAPPING_BYTES) {
            throw new ApiException(ErrorCode.INVALID_MAPPING, "Mapping exceeds " + (MAX_MAPPING_BYTES / 1024) + " KB");
        }
        try {
            return mappingReader.readTree(mappingJson);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_MAPPING, "Mapping is not valid JSON");
        }
    }

    private Contract requireContract(UUID contractId, UUID tenantId) {
        return contracts.findByIdAndTenantId(contractId, tenantId).orElseThrow(() -> ApiException.notFound("Contract"));
    }

    private ContractVersion requireVersion(UUID contractId, UUID tenantId, int version) {
        return versions.findByContractIdAndTenantIdAndVersion(contractId, tenantId, version)
                .orElseThrow(() -> ApiException.notFound("Contract version"));
    }
}
