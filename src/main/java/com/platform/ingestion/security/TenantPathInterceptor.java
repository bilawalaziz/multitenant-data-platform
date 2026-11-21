package com.platform.ingestion.security;

import com.platform.ingestion.tenant.Tenant;
import com.platform.ingestion.tenant.TenantRepository;
import com.platform.ingestion.tenant.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Authorises the tenant in the path and scopes the request to it, so row-level security limits every query of the
 * request to that tenant. The scope is opened here, before any service takes a connection, and closed when the
 * request completes.
 */
@Component
public class TenantPathInterceptor implements HandlerInterceptor {

    private static final String SCOPE_ATTRIBUTE = TenantPathInterceptor.class.getName() + ".scope";

    private final TenantAccessPolicy policy;
    private final TenantRepository tenants;

    public TenantPathInterceptor(TenantAccessPolicy policy, TenantRepository tenants) {
        this.policy = policy;
        this.tenants = tenants;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String tenantCode = variables == null ? null : variables.get("tenantId");
        if (tenantCode != null) {
            policy.check(
                    SecurityContextHolder.getContext().getAuthentication(),
                    tenantCode,
                    request.getHeader(Roles.HEADER_TENANT_ID));
            // An unknown tenant gets no scope: the handler then sees no rows and answers not found as before.
            tenants.findByTenantCode(tenantCode)
                    .map(Tenant::getId)
                    .ifPresent(id -> request.setAttribute(SCOPE_ATTRIBUTE, TenantScope.tenant(id)));
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(SCOPE_ATTRIBUTE) instanceof TenantScope scope) {
            scope.close();
            request.removeAttribute(SCOPE_ATTRIBUTE);
        }
    }
}
