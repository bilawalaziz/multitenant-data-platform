package com.platform.ingestion.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantPathInterceptor tenantPathInterceptor;
    private final AdminScopeInterceptor adminScopeInterceptor;

    public WebMvcConfig(TenantPathInterceptor tenantPathInterceptor, AdminScopeInterceptor adminScopeInterceptor) {
        this.tenantPathInterceptor = tenantPathInterceptor;
        this.adminScopeInterceptor = adminScopeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantPathInterceptor)
                .addPathPatterns("/api/v1/tenants/{tenantId}/**", "/api/v1/tenants/{tenantId}");
        registry.addInterceptor(adminScopeInterceptor).addPathPatterns("/api/v1/admin/**");
    }
}
