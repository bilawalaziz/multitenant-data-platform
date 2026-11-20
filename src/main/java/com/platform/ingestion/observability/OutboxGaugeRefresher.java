package com.platform.ingestion.observability;

import com.platform.ingestion.persistence.OutboxRepository;
import com.platform.ingestion.persistence.OutboxStatus;
import com.platform.ingestion.tenant.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxGaugeRefresher {

    private static final Logger log = LoggerFactory.getLogger(OutboxGaugeRefresher.class);

    private final OutboxRepository outbox;
    private final PlatformMetrics metrics;

    public OutboxGaugeRefresher(OutboxRepository outbox, PlatformMetrics metrics) {
        this.outbox = outbox;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${platform.metrics.gauge-refresh-ms:5000}", initialDelay = 2000)
    public void refresh() {
        try (TenantScope ignored = TenantScope.system()) {
            metrics.updateOutboxGauges(
                    outbox.countByStatus(OutboxStatus.PENDING), outbox.countByStatus(OutboxStatus.FAILED));
        } catch (RuntimeException e) {
            log.warn("Could not refresh outbox gauges: {}", e.getMessage());
        }
    }
}
