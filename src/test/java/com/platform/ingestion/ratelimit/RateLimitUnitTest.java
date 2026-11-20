package com.platform.ingestion.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.ratelimit.RateLimitProperties.Limit;
import com.platform.ingestion.ratelimit.RateLimitProperties.TenantOverride;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateLimitUnitTest {

    private final RateLimitProperties properties = new RateLimitProperties(
            true,
            new Limit(400, 200),
            new Limit(20, 10),
            Map.of(
                    "tenant-big", new TenantOverride(5000L, 1000.0),
                    "tenant-burst", new TenantOverride(900L, null)));

    @ParameterizedTest
    @CsvSource({
        "0, 1", // never advertise a zero-second wait
        "1, 1",
        "999999999, 1",
        "1000000000, 1",
        "1000000001, 2", // any fraction rounds up so the client does not retry early
        "20000000000, 20",
        "20400000000, 21"
    })
    void retryAfterIsWholeSecondsRoundedUp(long nanos, long seconds) {
        assertThat(RateLimiter.retryAfterSeconds(nanos)).isEqualTo(seconds);
    }

    @Test
    void tenantWithoutOverrideUsesTheDefault() {
        assertThat(properties.tenantLimit("tenant-other")).isEqualTo(new Limit(400, 200));
    }

    @Test
    void overrideReplacesBothValuesOrFallsBackFieldByField() {
        assertThat(properties.tenantLimit("tenant-big")).isEqualTo(new Limit(5000, 1000));
        assertThat(properties.tenantLimit("tenant-burst")).isEqualTo(new Limit(900, 200));
    }

    @Test
    void nonPositiveLimitsAreRejectedAtStartup() {
        assertThatThrownBy(() -> new Limit(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limit(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
