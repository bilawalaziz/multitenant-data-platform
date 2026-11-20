package com.platform.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30));

    @ParameterizedTest(name = "attempt {0} waits {1}s")
    @CsvSource({"-3,1", "0,1", "1,1", "2,2", "3,4", "5,16", "6,30", "50,30", "2147483647,30"})
    void growsExponentiallyFromBaseAndIsCapped(int attempt, long seconds) {
        assertThat(policy.delay(attempt)).isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
