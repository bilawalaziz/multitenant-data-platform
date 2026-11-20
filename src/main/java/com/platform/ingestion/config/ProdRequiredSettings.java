package com.platform.ingestion.config;

import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * With the prod profile the connection settings have no defaults. Spring Boot leaves an unresolved ${VAR} in a bound
 * property as literal text (the app would then try to log in as "${DB_USER}"), so resolve them strictly at startup
 * and fail naming the missing variable.
 */
public class ProdRequiredSettings implements EnvironmentPostProcessor {

    private static final List<String> REQUIRED = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.kafka.bootstrap-servers",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            REQUIRED.forEach(environment::getRequiredProperty);
        }
    }
}
