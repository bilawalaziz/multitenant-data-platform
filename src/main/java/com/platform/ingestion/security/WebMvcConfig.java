package com.platform.ingestion.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantPathInterceptor tenantPathInterceptor;

    public WebMvcConfig(TenantPathInterceptor tenantPathInterceptor) {
        this.tenantPathInterceptor = tenantPathInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantPathInterceptor)
                .addPathPatterns("/api/v1/tenants/{tenantId}/**", "/api/v1/tenants/{tenantId}");
    }
}
