package com.platform.ingestion.retention;

import com.platform.ingestion.observability.PlatformMetrics;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purges old rows in short per-batch transactions, locking candidates with SKIP LOCKED so instances can run it
 * concurrently. Only PUBLISHED outbox rows are deleted. Ingestion records still referenced by an idempotency row
 * are kept.
 */
@Component
@ConditionalOnProperty(prefix = "platform.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private static final String DELETE_OUTBOX = "DELETE FROM platform.outbox_events WHERE id IN ("
            + "SELECT id FROM platform.outbox_events WHERE status = 'PUBLISHED' AND published_at < ? "
            + "ORDER BY published_at LIMIT ? FOR UPDATE SKIP LOCKED)";

    private static final String DELETE_IDEMPOTENCY = "DELETE FROM platform.idempotency_records WHERE "
            + "(tenant_id, idempotency_key) IN ("
            + "SELECT tenant_id, idempotency_key FROM platform.idempotency_records WHERE created_at < ? "
            + "ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED)";

    private static final String DELETE_INGESTION = "DELETE FROM platform.ingestion_records WHERE id IN ("
            + "SELECT r.id FROM platform.ingestion_records r WHERE r.received_at < ? "
            + "AND NOT EXISTS (SELECT 1 FROM platform.idempotency_records i WHERE i.ingestion_id = r.id) "
            + "ORDER BY r.received_at LIMIT ? FOR UPDATE OF r SKIP LOCKED)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlatformMetrics metrics;
    private final Clock clock;
    private final RetentionProperties props;

    public RetentionJob(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            PlatformMetrics metrics,
            Clock clock,
            RetentionProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.metrics = metrics;
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${platform.retention.fixed-delay:PT1H}", initialDelay = 60_000)
    public void run() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.error("Retention run failed", e);
        }
    }

    /** Runs one purge pass. Idempotency rows go before ingestion records so the latter become deletable. */
    public void purge() {
        Instant now = clock.instant();
        int outbox = deleteInBatches(DELETE_OUTBOX, now.minus(props.outboxPublished()));
        int idempotency = deleteInBatches(DELETE_IDEMPOTENCY, now.minus(props.idempotency()));
        int ingestion = deleteInBatches(DELETE_INGESTION, now.minus(props.ingestionRecords()));
        metrics.retentionDeleted("outbox_events", outbox);
        metrics.retentionDeleted("idempotency_records", idempotency);
        metrics.retentionDeleted("ingestion_records", ingestion);
        if (outbox + idempotency + ingestion > 0) {
            log.info(
                    "Retention deleted {} outbox events, {} idempotency records, {} ingestion records",
                    outbox,
                    idempotency,
                    ingestion);
        }
    }

    private int deleteInBatches(String sql, Instant cutoff) {
        int total = 0;
        for (int batch = 0; batch < props.maxBatchesPerRun(); batch++) {
            IntSupplier deleteOne = () -> jdbc.update(sql, Timestamp.from(cutoff), props.batchSize());
            Integer deleted = tx.execute(status -> deleteOne.getAsInt());
            int count = deleted == null ? 0 : deleted;
            total += count;
            if (count < props.batchSize()) {
                break;
            }
        }
        return total;
    }
}
