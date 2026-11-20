package com.platform.ingestion.messaging;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates per-tenant topics with the AdminClient. Never throws: Kafka being down must not break callers. */
public class TopicProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TopicProvisioner.class);

    private final Map<String, Object> adminConfig;
    private final int partitions;
    private final short replicationFactor;
    private final Duration timeout;
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    public TopicProvisioner(
            Map<String, Object> adminConfig, int partitions, short replicationFactor, Duration timeout) {
        this.adminConfig = Map.copyOf(adminConfig);
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
        this.timeout = timeout;
    }

    public boolean tryEnsureTopic(String topic) {
        if (ensured.contains(topic)) {
            return true;
        }
        Map<String, Object> config = new HashMap<>(adminConfig);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout.toMillis());
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) timeout.toMillis());
        Admin admin = null;
        try {
            admin = Admin.create(config);
            admin.createTopics(List.of(new NewTopic(topic, partitions, replicationFactor)))
                    .all()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            ensured.add(topic);
            log.info("Created Kafka topic {}", topic);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                ensured.add(topic);
                return true;
            }
            log.warn(
                    "Could not ensure Kafka topic {}: {}",
                    topic,
                    e.getCause() == null ? e.getMessage() : e.getCause().toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | RuntimeException e) {
            log.warn("Could not ensure Kafka topic {}: {}", topic, e.toString());
            return false;
        } finally {
            if (admin != null) {
                admin.close(Duration.ofSeconds(1));
            }
        }
    }
}
