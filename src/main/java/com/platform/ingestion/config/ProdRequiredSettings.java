package com.platform.ingestion.config;

import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Spring Boot leaves an unresolved ${VAR} as literal text, so with the prod profile resolve connection settings
 * strictly at startup and fail naming the missing variable.
 */
public class ProdRequiredSettings implements EnvironmentPostProcessor {

    private static final List<String> REQUIRED = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.flyway.user",
            "spring.flyway.password",
            "spring.kafka.bootstrap-servers",
            "spring.data.redis.host",
            "spring.data.redis.password",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            "platform.keycloak.provisioner.url",
            "platform.keycloak.provisioner.client-id",
            "platform.keycloak.provisioner.client-secret");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            REQUIRED.forEach(environment::getRequiredProperty);
        }
    }
}
