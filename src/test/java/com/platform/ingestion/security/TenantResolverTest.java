package com.platform.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.platform.ingestion.TestJwts;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.tenant.Tenant;
import com.platform.ingestion.tenant.TenantContext;
import com.platform.ingestion.tenant.TenantRepository;
import com.platform.ingestion.tenant.TenantStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantResolverTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantResolver resolver = new TenantResolver(tenants);

    private Tenant tenant(String code, TenantStatus status) {
        return new Tenant(
                UUID.randomUUID(),
                code,
                code,
                status,
                "ts_" + code.replace('-', '_'),
                code + ".readings",
                Instant.now());
    }

    @Test
    void resolvesTenantFromClaimUsingTrustedRecord() {
        Tenant alpha = tenant("tenant-alpha", TenantStatus.ACTIVE);
        when(tenants.findByTenantCode("tenant-alpha")).thenReturn(Optional.of(alpha));

        TenantContext ctx = resolver.resolve(TestJwts.auth("tenant-alpha", Roles.TENANT_INGEST), null);

        assertThat(ctx.code()).isEqualTo("tenant-alpha");
        assertThat(ctx.id()).isEqualTo(alpha.getId());
        assertThat(ctx.kafkaTopic()).isEqualTo("tenant-alpha.readings");
    }

    @Test
    void matchingHeaderIsAccepted() {
        when(tenants.findByTenantCode("tenant-alpha"))
                .thenReturn(Optional.of(tenant("tenant-alpha", TenantStatus.ACTIVE)));

        assertThat(resolver.resolve(TestJwts.auth("tenant-alpha", Roles.TENANT_INGEST), "tenant-alpha")
                        .code())
                .isEqualTo("tenant-alpha");
    }

    @Test
    void headerDifferingFromClaimIsTenantMismatch() {
        assertThatThrownBy(() -> resolver.resolve(TestJwts.auth("tenant-alpha", Roles.TENANT_INGEST), "tenant-beta"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_MISMATCH));
    }

    @Test
    void missingClaimIsAccessDenied() {
        assertThatThrownBy(() -> resolver.resolve(TestJwts.auth(null, Roles.TENANT_INGEST), null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    void unknownTenantIsNotRegistered() {
        when(tenants.findByTenantCode("tenant-ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(TestJwts.auth("tenant-ghost", Roles.TENANT_INGEST), null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_NOT_REGISTERED));
    }

    @Test
    void inactiveTenantIsRejected() {
        when(tenants.findByTenantCode("tenant-old"))
                .thenReturn(Optional.of(tenant("tenant-old", TenantStatus.INACTIVE)));

        assertThatThrownBy(() -> resolver.resolve(TestJwts.auth("tenant-old", Roles.TENANT_INGEST), null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_INACTIVE));
    }
}
