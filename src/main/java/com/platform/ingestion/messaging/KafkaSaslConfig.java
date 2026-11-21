package com.platform.ingestion.messaging;

import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Adds the SASL login (sasl.jaas.config) to the shared Kafka properties when the security protocol uses SASL. The
 * protocol and mechanism come from spring.kafka.properties; the credentials are built here so that they exist only
 * for SASL and never as a placeholder that is half empty. The properties are shared by the producer and by the admin
 * client, which TopicProvisioner copies, so topic creation authenticates as well.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaSaslConfig {

    static final String JAAS_KEY = "sasl.jaas.config";

    @Bean
    static BeanPostProcessor kafkaSaslCustomizer(Environment environment) {
        // Read from the Environment: a post-processor is created before @Value placeholders can be resolved.
        String username = environment.getProperty("KAFKA_SASL_USERNAME", "");
        String password = environment.getProperty("KAFKA_SASL_PASSWORD", "");
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaProperties kafka) {
                    applySasl(kafka.getProperties(), username, password);
                }
                return bean;
            }
        };
    }

    static void applySasl(Map<String, String> properties, String username, String password) {
        String protocol = properties.getOrDefault("security.protocol", "PLAINTEXT");
        if (!protocol.toUpperCase(Locale.ROOT).contains("SASL") || properties.containsKey(JAAS_KEY)) {
            return;
        }
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException(
                    "KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD are required when KAFKA_SECURITY_PROTOCOL is "
                            + protocol);
        }
        String mechanism = properties.getOrDefault("sasl.mechanism", "SCRAM-SHA-512");
        String module =
                switch (mechanism) {
                    case "PLAIN" -> "org.apache.kafka.common.security.plain.PlainLoginModule";
                    case "SCRAM-SHA-256", "SCRAM-SHA-512" -> "org.apache.kafka.common.security.scram.ScramLoginModule";
                    default -> throw new IllegalStateException("Unsupported KAFKA_SASL_MECHANISM " + mechanism);
                };
        properties.put(
                JAAS_KEY,
                module + " required username=\"" + escape(username) + "\" password=\"" + escape(password) + "\";");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
