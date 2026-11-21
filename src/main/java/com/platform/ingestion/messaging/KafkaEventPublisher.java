package com.platform.ingestion.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> template;
    private final TopicProvisioner topics;

    public KafkaEventPublisher(KafkaTemplate<String, String> template, TopicProvisioner topics) {
        this.template = template;
        this.topics = topics;
    }

    @Override
    public CompletableFuture<Void> publish(OutboxMessage message) {
        topics.tryEnsureTopic(message.topic());
        ProducerRecord<String, String> record = new ProducerRecord<>(message.topic(), message.key(), message.payload());
        record.headers()
                .add("event_id", bytes(message.eventId()))
                .add("tenant_id", bytes(message.tenantId()))
                .add("schema_version", bytes(message.schemaVersion()));
        // Replace rather than add: a second traceparent header would make consumers pick one arbitrarily.
        message.traceHeaders()
                .forEach((name, value) -> record.headers().remove(name).add(name, bytes(value)));
        try {
            // send() can block up to max.block.ms waiting for metadata when the broker is unreachable
            return template.send(record).thenAccept(result -> {}).exceptionallyCompose(e -> {
                Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                return CompletableFuture.failedFuture(new PublishException("Kafka publish failed: " + cause, cause));
            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new PublishException("Kafka publish failed: " + e, e));
        }
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
