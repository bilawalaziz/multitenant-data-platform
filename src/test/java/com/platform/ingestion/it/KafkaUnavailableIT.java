package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.messaging.BackoffPolicy;
import com.platform.ingestion.messaging.KafkaEventPublisher;
import com.platform.ingestion.messaging.OutboxMessage;
import com.platform.ingestion.messaging.OutboxProperties;
import com.platform.ingestion.messaging.OutboxPublisher;
import com.platform.ingestion.messaging.PlatformKafkaProperties;
import com.platform.ingestion.messaging.PublishException;
import com.platform.ingestion.messaging.TopicProvisioner;
import com.platform.ingestion.observability.PlatformMetrics;
import com.platform.ingestion.persistence.OutboxEvent;
import com.platform.ingestion.persistence.OutboxRepository;
import com.platform.ingestion.tenant.TenantScope;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real KafkaEventPublisher against an address nobody listens on: it must fail fast and the outbox must keep the
 * event.
 */
class KafkaUnavailableIT extends AbstractIntegrationTest {

    private static final String DEAD = "127.0.0.1:1";

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PlatformMetrics metrics;

    private KafkaEventPublisher deadPublisher() {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                DEAD,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                1500,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                1000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                2000,
                ProducerConfig.ACKS_CONFIG,
                "all");
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
        TopicProvisioner topics =
                new TopicProvisioner(Map.of("bootstrap.servers", DEAD), 3, (short) 1, Duration.ofMillis(1500));
        return new KafkaEventPublisher(template, topics);
    }

    @Test
    void publisherFailsFastWhenBrokerIsUnreachable() {
        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> deadPublisher()
                        .publish(new OutboxMessage(
                                "tenant-offline.readings",
                                "MACHINE-001",
                                "{\"event_id\":\"x\"}",
                                "x",
                                "tenant-offline",
                                "1.0"))
                        .get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(PublishException.class);
        assertThat(System.currentTimeMillis() - started).isLessThan(30_000);
    }

    @Test
    void outboxRowIsRetainedWhenKafkaIsDown() throws Exception {
        registerTenant("tenant-offline");
        UUID tenantId =
                jdbc.queryForObject("SELECT id FROM platform.tenants WHERE tenant_code='tenant-offline'", UUID.class);
        UUID eventId = UUID.randomUUID();
        Clock clock = Clock.systemUTC();
        try (TenantScope scope = TenantScope.tenant(tenantId)) {
            tx.executeWithoutResult(s -> outboxRepository.save(new OutboxEvent(
                    UUID.randomUUID(),
                    tenantId,
                    eventId,
                    "tenant-offline.readings",
                    "MACHINE-009",
                    mapper.createObjectNode()
                            .put("tenant_id", "tenant-offline")
                            .put("schema_version", "1.0")
                            .put("device_id", "MACHINE-009"),
                    clock.instant().minusSeconds(1))));
        }

        OutboxPublisher offline = new OutboxPublisher(
                outboxRepository,
                deadPublisher(),
                new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30)),
                metrics,
                tx,
                clock,
                new OutboxProperties(true, 1000, 50, 50, 1000, 30000, Duration.ofSeconds(60), Duration.ofSeconds(10)),
                new PlatformKafkaProperties(3, (short) 1, 1500, 4000),
                springKafka(1500));

        assertThat(offline.publishDue()).isZero();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempts, last_error FROM platform.outbox_events WHERE event_id = ?", eventId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).isNotBlank();

        // clean up so the shared outbox is not polluted for other tests
        jdbc.update(
                "UPDATE platform.outbox_events SET status='PUBLISHED', published_at=now() WHERE event_id = ?", eventId);
    }
}
