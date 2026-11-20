package com.platform.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.TestJwts;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.Authentication;

class TenantAccessPolicyTest {

    private final TenantAccessPolicy policy = new TenantAccessPolicy();

    static Stream<Arguments> matrix() {
        Authentication platformAdmin = TestJwts.auth(null, Roles.PLATFORM_ADMIN);
        Authentication alphaAdmin = TestJwts.auth("tenant-alpha", Roles.TENANT_ADMIN);
        Authentication alphaIngest = TestJwts.auth("tenant-alpha", Roles.TENANT_INGEST);
        return Stream.of(
                Arguments.of("platform admin, any tenant", platformAdmin, "tenant-beta", null, null),
                Arguments.of("platform admin, header matches path", platformAdmin, "tenant-beta", "tenant-beta", null),
                Arguments.of(
                        "platform admin, header differs from path",
                        platformAdmin,
                        "tenant-beta",
                        "tenant-alpha",
                        ErrorCode.TENANT_MISMATCH),
                Arguments.of("tenant admin, own tenant", alphaAdmin, "tenant-alpha", null, null),
                Arguments.of("tenant admin, other tenant", alphaAdmin, "tenant-beta", null, ErrorCode.ACCESS_DENIED),
                Arguments.of("tenant ingest, own tenant", alphaIngest, "tenant-alpha", "tenant-alpha", null),
                Arguments.of("tenant ingest, other tenant", alphaIngest, "tenant-beta", null, ErrorCode.ACCESS_DENIED),
                Arguments.of(
                        "header differs on own tenant",
                        alphaAdmin,
                        "tenant-alpha",
                        "tenant-beta",
                        ErrorCode.TENANT_MISMATCH),
                Arguments.of(
                        "missing tenant claim",
                        TestJwts.auth(null, Roles.TENANT_ADMIN),
                        "tenant-alpha",
                        null,
                        ErrorCode.ACCESS_DENIED),
                Arguments.of(
                        "no platform role",
                        TestJwts.auth("tenant-alpha"),
                        "tenant-alpha",
                        null,
                        ErrorCode.ACCESS_DENIED),
                Arguments.of("anonymous", null, "tenant-alpha", null, ErrorCode.UNAUTHENTICATED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void authorizationMatrix(String name, Authentication auth, String path, String header, ErrorCode denied) {
        if (denied == null) {
            assertThatCode(() -> policy.check(auth, path, header)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> policy.check(auth, path, header))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(denied));
        }
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
