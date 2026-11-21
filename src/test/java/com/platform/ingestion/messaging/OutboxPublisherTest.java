package com.platform.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.observability.OutboxTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;

/** A cycle must be able to finish inside the lease even when every blocking step runs to its timeout. */
class OutboxPublisherTest {

    private static OutboxPublisher publisher(Duration lease, long adminTimeoutMs, Integer maxBlockMs, long sendMs) {
        KafkaProperties springKafka = new KafkaProperties();
        if (maxBlockMs != null) {
            springKafka.getProducer().getProperties().put("max.block.ms", String.valueOf(maxBlockMs));
        }
        return new OutboxPublisher(
                null,
                null,
                null,
                null,
                null,
                Clock.systemUTC(),
                new OutboxProperties(true, 1000, 50, 50, 1000, 30000, lease, Duration.ofSeconds(10)),
                new PlatformKafkaProperties(3, (short) 1, adminTimeoutMs, sendMs),
                springKafka,
                new OutboxTracing(Tracer.NOOP, Propagator.NOOP));
    }

    @Test
    void leaseMustOutlastIssueDeadlinePlusBlockingSendPlusSendTimeout() {
        // 10 s deadline + (5 s admin + 5 s max.block) + 12 s send timeout = 32 s
        assertThatCode(() -> publisher(Duration.ofSeconds(60), 5000, 5000, 12000))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> publisher(Duration.ofSeconds(32), 5000, 5000, 12000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease");
        // without max.block.ms the Kafka default of 60 s applies
        assertThatThrownBy(() -> publisher(Duration.ofSeconds(60), 5000, null, 12000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease");
    }
}
