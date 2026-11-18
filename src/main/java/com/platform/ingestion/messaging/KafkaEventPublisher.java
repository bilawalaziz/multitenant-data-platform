package com.platform.ingestion.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/** Kafka implementation: key = device id, headers event_id / tenant_id / schema_version, blocks for the broker ack. */
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> template;
    private final TopicProvisioner topics;
    private final long sendTimeoutMs;

    public KafkaEventPublisher(KafkaTemplate<String, String> template, TopicProvisioner topics, long sendTimeoutMs) {
        this.template = template;
        this.topics = topics;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Override
    public void publish(OutboxMessage message) {
        topics.tryEnsureTopic(message.topic());
        ProducerRecord<String, String> record = new ProducerRecord<>(message.topic(), message.key(), message.payload());
        record.headers()
                .add("event_id", bytes(message.eventId()))
                .add("tenant_id", bytes(message.tenantId()))
                .add("schema_version", bytes(message.schemaVersion()));
        try {
            template.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrupted while publishing to " + message.topic(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new PublishException("Kafka publish failed: " + cause, cause);
        } catch (TimeoutException e) {
            throw new PublishException("Kafka publish timed out after " + sendTimeoutMs + " ms", e);
        } catch (RuntimeException e) {
            throw new PublishException("Kafka publish failed: " + e, e);
        }
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
