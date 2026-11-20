package com.platform.ingestion.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "platform.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxPublisher publisher;

    public OutboxScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${platform.outbox.fixed-delay-ms:1000}", initialDelay = 2000)
    public void run() {
        try {
            publisher.publishDue();
        } catch (RuntimeException e) {
            log.error("Outbox publisher cycle failed", e);
        }
    }
}
