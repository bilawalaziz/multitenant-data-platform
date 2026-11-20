package com.platform.ingestion.security;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/** Decides which tenant a caller may address: platform admins any tenant, tenant roles only their own. */
@Component
public class TenantAccessPolicy {

    public void check(Authentication authentication, String pathTenantId, String tenantHeader) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required");
        }
        boolean hasHeader = tenantHeader != null && !tenantHeader.isBlank();

        if (hasRole(authentication, Roles.PLATFORM_ADMIN)) {
            if (hasHeader && !tenantHeader.trim().equals(pathTenantId)) {
                throw new ApiException(ErrorCode.TENANT_MISMATCH, "X-Tenant-ID does not match the tenant in the path");
            }
            return;
        }
        if (!hasRole(authentication, Roles.TENANT_ADMIN) && !hasRole(authentication, Roles.TENANT_INGEST)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Access denied");
        }
        String claim = TenantResolver.tenantClaim(authentication);
        if (claim == null || claim.isBlank() || !claim.equals(pathTenantId)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Access to this tenant is not permitted");
        }
        if (hasHeader && !tenantHeader.trim().equals(claim)) {
            throw new ApiException(ErrorCode.TENANT_MISMATCH, "X-Tenant-ID does not match the tenant of the token");
        }
    }

    private static boolean hasRole(Authentication authentication, String role) {
        String authority = "ROLE_" + role;
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
