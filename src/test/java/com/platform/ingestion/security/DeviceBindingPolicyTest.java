package com.platform.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.TestJwts;
import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class DeviceBindingPolicyTest {

    private static Authentication token(String deviceId) {
        return new JwtAuthenticationToken(TestJwts.jwt("client", "tenant-alpha", deviceId, List.of("tenant-ingest")));
    }

    @Test
    void deviceClaimBindsTheTokenWhetherOrNotTenantWideTokensAreAllowed() {
        assertThat(new DeviceBindingPolicy(true).boundDevice(token("M-1"))).contains("M-1");
        assertThat(new DeviceBindingPolicy(false).boundDevice(token("M-1"))).contains("M-1");
    }

    @Test
    void tenantWideTokenIsAcceptedOnlyWhileEnabled() {
        assertThat(new DeviceBindingPolicy(true).boundDevice(token(null))).isEmpty();
        assertThatThrownBy(() -> new DeviceBindingPolicy(false).boundDevice(token(null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ErrorCode.DEVICE_CREDENTIAL_REQUIRED));
    }

    @Test
    void blankDeviceClaimDoesNotCountAsADeviceCredential() {
        assertThatThrownBy(() -> new DeviceBindingPolicy(false).boundDevice(token(" ")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ErrorCode.DEVICE_CREDENTIAL_REQUIRED));
    }

    @Test
    void deviceMustMatchExactlyAndUnboundTokensMatchAnything() {
        DeviceBindingPolicy.requireSameDevice(Optional.of("M-1"), "M-1");
        DeviceBindingPolicy.requireSameDevice(Optional.empty(), "anything");
        assertThatThrownBy(() -> DeviceBindingPolicy.requireSameDevice(Optional.of("M-1"), "m-1"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_MISMATCH));
    }
}
