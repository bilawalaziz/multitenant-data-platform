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
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("active tenant", "tenant-alpha", null, TenantStatus.ACTIVE, null),
                Arguments.of("matching header", "tenant-alpha", "tenant-alpha", TenantStatus.ACTIVE, null),
                Arguments.of(
                        "header differs from claim", "tenant-alpha", "tenant-beta", null, ErrorCode.TENANT_MISMATCH),
                Arguments.of("missing claim", null, null, null, ErrorCode.ACCESS_DENIED),
                Arguments.of("unknown tenant", "tenant-ghost", null, null, ErrorCode.TENANT_NOT_REGISTERED),
                Arguments.of("inactive tenant", "tenant-old", null, TenantStatus.INACTIVE, ErrorCode.TENANT_INACTIVE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void resolvesFromTrustedRecordOrRejects(
            String name, String claim, String header, TenantStatus status, ErrorCode rejected) {
        Tenant record = status == null ? null : tenant(claim, status);
        if (claim != null) {
            when(tenants.findByTenantCode(claim)).thenReturn(Optional.ofNullable(record));
        }
        var auth = TestJwts.auth(claim, Roles.TENANT_INGEST);

        if (rejected == null) {
            TenantContext ctx = resolver.resolve(auth, header);
            assertThat(ctx.code()).isEqualTo(claim);
            assertThat(ctx.id()).isEqualTo(record.getId());
            assertThat(ctx.kafkaTopic()).isEqualTo(claim + ".readings");
        } else {
            assertThatThrownBy(() -> resolver.resolve(auth, header))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(rejected));
        }
    }
}
