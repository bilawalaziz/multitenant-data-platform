package com.platform.ingestion.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Applies {@link TenantAccessPolicy} to every request under /api/v1/tenants/{tenantId}. */
@Component
public class TenantPathInterceptor implements HandlerInterceptor {

    private final TenantAccessPolicy policy;

    public TenantPathInterceptor(TenantAccessPolicy policy) {
        this.policy = policy;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String tenantId = variables == null ? null : variables.get("tenantId");
        if (tenantId != null) {
            policy.check(
                    SecurityContextHolder.getContext().getAuthentication(),
                    tenantId,
                    request.getHeader(Roles.HEADER_TENANT_ID));
        }
        return true;
    }
}
