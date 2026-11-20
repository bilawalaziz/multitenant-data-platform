package com.platform.ingestion.security;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.tenant.Tenant;
import com.platform.ingestion.tenant.TenantContext;
import com.platform.ingestion.tenant.TenantRepository;
import com.platform.ingestion.tenant.TenantStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** The token claim is authoritative; the X-Tenant-ID header is only a hint and must agree with it. */
@Component
public class TenantResolver {

    private final TenantRepository tenants;

    public TenantResolver(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public TenantContext resolve(Authentication authentication, String tenantHeader) {
        String claim = tenantClaim(authentication);
        if (claim == null || claim.isBlank()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Token does not carry a tenant_id claim");
        }
        if (tenantHeader != null
                && !tenantHeader.isBlank()
                && !tenantHeader.trim().equals(claim)) {
            throw new ApiException(ErrorCode.TENANT_MISMATCH, "X-Tenant-ID does not match the tenant of the token");
        }
        Tenant tenant = tenants.findByTenantCode(claim)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.TENANT_NOT_REGISTERED, "Tenant '" + claim + "' is not registered"));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new ApiException(ErrorCode.TENANT_INACTIVE, "Tenant '" + claim + "' is not active");
        }
        return tenant.toContext();
    }

    static String tenantClaim(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString(Roles.CLAIM_TENANT_ID);
        }
        return null;
    }
}
