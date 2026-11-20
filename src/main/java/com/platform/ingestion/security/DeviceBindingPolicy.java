package com.platform.ingestion.security;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Ties a device credential to its device: a token with a device_id claim may only submit readings of that device.
 * A token without the claim is a tenant-wide credential, which can be switched off once every device has its own.
 */
@Component
public class DeviceBindingPolicy {

    // Not final so a test can flip the flag in a shared application context instead of paying for a second one.
    private volatile boolean tenantWideEnabled;

    public DeviceBindingPolicy(
            @Value("${platform.security.tenant-ingest-credentials-enabled:true}") boolean tenantWideEnabled) {
        this.tenantWideEnabled = tenantWideEnabled;
    }

    /**
     * @return the device the token is bound to, or empty for an accepted tenant-wide token
     * @throws ApiException DEVICE_CREDENTIAL_REQUIRED when the token is tenant-wide and that is not allowed
     */
    public Optional<String> boundDevice(Authentication authentication) {
        String claim = null;
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            claim = jwt.getClaimAsString(Roles.CLAIM_DEVICE_ID);
        }
        if (claim != null && !claim.isBlank()) {
            return Optional.of(claim);
        }
        if (!tenantWideEnabled) {
            throw new ApiException(
                    ErrorCode.DEVICE_CREDENTIAL_REQUIRED,
                    "A device credential is required; tenant-wide ingestion credentials are disabled");
        }
        return Optional.empty();
    }

    public static void requireSameDevice(Optional<String> bound, String actualDeviceId) {
        if (bound.isPresent() && !bound.get().equals(actualDeviceId)) {
            throw new ApiException(
                    ErrorCode.DEVICE_MISMATCH, "The credential is not valid for the device named in the reading");
        }
    }
}
