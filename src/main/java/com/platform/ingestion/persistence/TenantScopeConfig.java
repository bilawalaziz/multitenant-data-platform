package com.platform.ingestion.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TenantScopeConfig {

    /** Wraps the application pool only; Flyway builds its own owner-role connection and is not scoped. */
    @Bean
    static BeanPostProcessor tenantScopedDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource && !(bean instanceof TenantScopedDataSource)
                        ? new TenantScopedDataSource(dataSource)
                        : bean;
            }
        };
    }
}
