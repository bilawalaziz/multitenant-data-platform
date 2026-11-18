package com.platform.ingestion.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "platform.kafka")
public record PlatformKafkaProperties(
        @DefaultValue("3") int topicPartitions,
        @DefaultValue("1") short topicReplicationFactor,
        @DefaultValue("5000") long adminTimeoutMs,
        @DefaultValue("20000") long sendTimeoutMs) {}
