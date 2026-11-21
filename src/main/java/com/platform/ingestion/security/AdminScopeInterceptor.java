package com.platform.ingestion.security;

import com.platform.ingestion.tenant.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Platform administration spans tenants; the paths it covers are already restricted to PLATFORM_ADMIN. */
@Component
public class AdminScopeInterceptor implements HandlerInterceptor {

    private static final String SCOPE_ATTRIBUTE = AdminScopeInterceptor.class.getName() + ".scope";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(SCOPE_ATTRIBUTE, TenantScope.system());
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
