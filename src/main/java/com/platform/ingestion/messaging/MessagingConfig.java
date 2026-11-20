package com.platform.ingestion.messaging;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
public class MessagingConfig {

    @Bean
    TopicProvisioner topicProvisioner(KafkaAdmin kafkaAdmin, PlatformKafkaProperties props) {
        return new TopicProvisioner(
                kafkaAdmin.getConfigurationProperties(),
                props.topicPartitions(),
                props.topicReplicationFactor(),
                Duration.ofMillis(props.adminTimeoutMs()));
    }

    @Bean
    KafkaEventPublisher kafkaEventPublisher(
            KafkaTemplate<String, String> template, TopicProvisioner topics, PlatformKafkaProperties props) {
        return new KafkaEventPublisher(template, topics);
    }

    @Bean
    BackoffPolicy backoffPolicy(OutboxProperties props) {
        return new BackoffPolicy(Duration.ofMillis(props.backoffBaseMs()), Duration.ofMillis(props.backoffCapMs()));
    }
}
