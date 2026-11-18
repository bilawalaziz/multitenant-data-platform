package com.platform.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.TestJwts;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class TenantAccessPolicyTest {

    private final TenantAccessPolicy policy = new TenantAccessPolicy();

    private void assertDenied(Authentication auth, String path, String header, ErrorCode expected) {
        assertThatThrownBy(() -> policy.check(auth, path, header))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    @Test
    void platformAdminMayAccessAnyTenant() {
        Authentication admin = TestJwts.auth(null, Roles.PLATFORM_ADMIN);
        assertThatCode(() -> policy.check(admin, "tenant-alpha", null)).doesNotThrowAnyException();
        assertThatCode(() -> policy.check(admin, "tenant-beta", null)).doesNotThrowAnyException();
    }

    @Test
    void platformAdminHeaderMustMatchPathTenant() {
        Authentication admin = TestJwts.auth(null, Roles.PLATFORM_ADMIN);
        assertThatCode(() -> policy.check(admin, "tenant-beta", "tenant-beta")).doesNotThrowAnyException();
        assertDenied(admin, "tenant-beta", "tenant-alpha", ErrorCode.TENANT_MISMATCH);
    }

    @Test
    void tenantAdminMayAccessOwnTenantOnly() {
        Authentication alphaAdmin = TestJwts.auth("tenant-alpha", Roles.TENANT_ADMIN);
        assertThatCode(() -> policy.check(alphaAdmin, "tenant-alpha", null)).doesNotThrowAnyException();
        assertDenied(alphaAdmin, "tenant-beta", null, ErrorCode.ACCESS_DENIED);
    }

    @Test
    void tenantIngestMayAccessOwnTenantOnly() {
        Authentication alphaIngest = TestJwts.auth("tenant-alpha", Roles.TENANT_INGEST);
        assertThatCode(() -> policy.check(alphaIngest, "tenant-alpha", "tenant-alpha"))
                .doesNotThrowAnyException();
        assertDenied(alphaIngest, "tenant-beta", null, ErrorCode.ACCESS_DENIED);
    }

    @Test
    void headerMismatchOnOwnTenantIsTenantMismatch() {
        assertDenied(
                TestJwts.auth("tenant-alpha", Roles.TENANT_ADMIN),
                "tenant-alpha",
                "tenant-beta",
                ErrorCode.TENANT_MISMATCH);
    }

    @Test
    void missingTenantClaimIsDenied() {
        assertDenied(TestJwts.auth(null, Roles.TENANT_ADMIN), "tenant-alpha", null, ErrorCode.ACCESS_DENIED);
    }

    @Test
    void noPlatformRoleIsDenied() {
        assertDenied(TestJwts.auth("tenant-alpha"), "tenant-alpha", null, ErrorCode.ACCESS_DENIED);
    }

    @Test
    void anonymousIsUnauthenticated() {
        assertDenied(null, "tenant-alpha", null, ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void converterMapsRealmRolesToAuthorities() {
        var jwt = TestJwts.jwt(
                "alpha-admin", "tenant-alpha", List.of("tenant-admin", "offline_access", "platform-admin"));
        var token = new KeycloakJwtAuthenticationConverter().convert(jwt);
        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_TENANT_ADMIN", "ROLE_PLATFORM_ADMIN");
        assertThat(token.getName()).isEqualTo("alpha-admin");
    }
}
