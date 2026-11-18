package com.platform.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30));

    @Test
    void growsExponentiallyFromBase() {
        assertThat(policy.delay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delay(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delay(5)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void isCappedAndNeverOverflows() {
        assertThat(policy.delay(6)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delay(50)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delay(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void attemptZeroOrNegativeUsesBase() {
        assertThat(policy.delay(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delay(-3)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
