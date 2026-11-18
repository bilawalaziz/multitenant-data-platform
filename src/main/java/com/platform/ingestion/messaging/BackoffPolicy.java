package com.platform.ingestion.messaging;

import java.time.Duration;

/** Exponential backoff: base * 2^(attempt-1), capped. attempt is the number of failed attempts so far (>= 1). */
public class BackoffPolicy {

    private final Duration base;
    private final Duration cap;

    public BackoffPolicy(Duration base, Duration cap) {
        if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("backoff base must be positive and not larger than cap");
        }
        this.base = base;
        this.cap = cap;
    }

    public Duration delay(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 30);
        long millis = base.toMillis() * (1L << shift);
        return millis >= cap.toMillis() || millis <= 0 ? cap : Duration.ofMillis(millis);
    }
}
