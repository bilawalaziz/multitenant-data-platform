package com.platform.ingestion.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RetentionPropertiesTest {

    @Test
    void defaultsMatchTheDocumentedRetention() {
        RetentionProperties props = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("platform.retention", RetentionProperties.class);

        assertThat(props.outboxPublished()).isEqualTo(Duration.ofDays(7));
        assertThat(props.idempotency()).isEqualTo(Duration.ofDays(7));
        assertThat(props.ingestionRecords()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void idempotencyWindowLongerThanIngestionRecordsIsRejected() {
        assertThatThrownBy(() -> new RetentionProperties(
                        true,
                        Duration.ofHours(1),
                        Duration.ofDays(7),
                        Duration.ofDays(100),
                        Duration.ofDays(90),
                        1000,
                        100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotency");
    }
}
