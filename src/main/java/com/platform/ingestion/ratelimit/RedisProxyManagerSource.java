package com.platform.ingestion.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;

/**
 * Connects to Redis on first use rather than at startup, so an unavailable Redis never prevents the application
 * from starting (the limiter fails open). A failed attempt is retried by the next caller; once connected, Lettuce
 * reconnects by itself.
 */
@Component
public class RedisProxyManagerSource implements DisposableBean {

    /** Keeps idle buckets for a short while after they would be full again; a full bucket equals a missing one. */
    private static final Duration KEEP_AFTER_REFILL = Duration.ofSeconds(30);

    private final RedisProperties redis;
    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, byte[]> connection;
    private volatile ProxyManager<String> proxyManager;

    public RedisProxyManagerSource(RedisProperties redis) {
        this.redis = redis;
    }

    /**
     * @return the proxy manager, or null while another thread is connecting; no caller ever waits for a connect
     */
    ProxyManager<String> get() {
        ProxyManager<String> ready = proxyManager;
        if (ready != null) {
            return ready;
        }
        if (!connecting.compareAndSet(false, true)) {
            return null;
        }
        try {
            return connect();
        } finally {
            connecting.set(false);
        }
    }

    private ProxyManager<String> connect() {
        Duration timeout = redis.getTimeout() != null ? redis.getTimeout() : Duration.ofMillis(200);
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(redis.getHost())
                .withPort(redis.getPort())
                .withDatabase(redis.getDatabase())
                .withTimeout(timeout);
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            char[] password = redis.getPassword().toCharArray();
            if (redis.getUsername() != null && !redis.getUsername().isBlank()) {
                uri.withAuthentication(redis.getUsername(), password);
            } else {
                uri.withPassword(password);
            }
        }
        RedisClient created = RedisClient.create();
        created.setOptions(ClientOptions.builder()
                // Fail commands at once while the connection is down instead of queueing them behind a reconnect.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .build());
        try {
            StatefulRedisConnection<String, byte[]> opened =
                    created.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE), uri.build());
            ProxyManager<String> manager = LettuceBasedProxyManager.builderFor(opened)
                    .withExpirationStrategy(
                            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_REFILL))
                    .build();
            client = created;
            connection = opened;
            proxyManager = manager;
            return manager;
        } catch (RuntimeException e) {
            created.shutdown(Duration.ZERO, Duration.ZERO);
            throw e;
        }
    }

    @Override
    public void destroy() {
        StatefulRedisConnection<String, byte[]> open = connection;
        if (open != null) {
            open.close();
        }
        RedisClient created = client;
        if (created != null) {
            created.shutdown(Duration.ZERO, Duration.ofSeconds(1));
        }
    }
}
