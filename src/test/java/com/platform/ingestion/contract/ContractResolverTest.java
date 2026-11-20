package com.platform.ingestion.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.contract.ContractResolver.ActiveContract;
import com.platform.ingestion.tenant.TenantContext;
import com.platform.ingestion.transformation.MappingValidator;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ContractVersionRepository versions = mock(ContractVersionRepository.class);
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final ContractMappingRepository mappings = mock(ContractMappingRepository.class);
    private final ContractResolver resolver =
            new ContractResolver(versions, contracts, mappings, new MappingValidator());

    private final TenantContext tenant =
            new TenantContext(UUID.randomUUID(), "tenant-alpha", "ts_tenant_alpha", "tenant-alpha.readings");

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolvesActiveVersionWithParsedMapping() {
        UUID contractId = UUID.randomUUID();
        ContractVersion version = new ContractVersion(
                UUID.randomUUID(),
                contractId,
                tenant.id(),
                2,
                "YAML",
                "spec",
                "a".repeat(64),
                "/readings",
                json("{\"type\":\"object\"}"),
                Instant.now());
        version.activate(Instant.now());
        when(versions.findByTenantIdAndStatus(tenant.id(), ContractStatus.ACTIVE))
                .thenReturn(Optional.of(version));
        when(contracts.findByIdAndTenantId(contractId, tenant.id()))
                .thenReturn(Optional.of(new Contract(contractId, tenant.id(), "alpha-readings", Instant.now())));
        when(mappings.findByContractVersionIdAndTenantId(version.getId(), tenant.id()))
                .thenReturn(Optional.of(new ContractMapping(
                        UUID.randomUUID(),
                        version.getId(),
                        tenant.id(),
                        json("{\"device_id\":\"$.d\",\"sensor_id\":\"$.s\",\"value\":\"$.v\","
                                + "\"unit\":\"$.u\",\"event_timestamp\":\"$.t\"}"),
                        Instant.now())));

        ActiveContract active = resolver.resolveActive(tenant);

        assertThat(active.contractId()).isEqualTo(contractId);
        assertThat(active.version()).isEqualTo(2);
        assertThat(active.name()).isEqualTo("alpha-readings");
        assertThat(active.mapping().fields()).containsKeys("device_id", "event_timestamp");
    }

    @Test
    void noActiveVersionOrNoMappingMeansTheTenantCannotIngest() {
        when(versions.findByTenantIdAndStatus(tenant.id(), ContractStatus.ACTIVE))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolveActive(tenant))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NO_ACTIVE_CONTRACT));

        UUID contractId = UUID.randomUUID();
        ContractVersion version = new ContractVersion(
                UUID.randomUUID(),
                contractId,
                tenant.id(),
                1,
                "YAML",
                "spec",
                "a".repeat(64),
                "/readings",
                json("{}"),
                Instant.now());
        when(versions.findByTenantIdAndStatus(tenant.id(), ContractStatus.ACTIVE))
                .thenReturn(Optional.of(version));
        when(contracts.findByIdAndTenantId(contractId, tenant.id()))
                .thenReturn(Optional.of(new Contract(contractId, tenant.id(), "x", Instant.now())));
        when(mappings.findByContractVersionIdAndTenantId(version.getId(), tenant.id()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveActive(tenant)).isInstanceOf(ApiException.class);
    }
}
