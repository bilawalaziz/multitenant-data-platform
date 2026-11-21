package com.platform.ingestion.it;

import com.platform.ingestion.TestJwts;
import com.platform.ingestion.credential.CredentialProvisioningException;
import com.platform.ingestion.credential.CredentialProvisioningException.Reason;
import com.platform.ingestion.credential.DeviceClientIds;
import com.platform.ingestion.credential.DeviceCredential;
import com.platform.ingestion.credential.DeviceCredentialProvisioner;
import com.platform.ingestion.messaging.EventPublisher;
import com.platform.ingestion.messaging.KafkaEventPublisher;
import com.platform.ingestion.messaging.OutboxMessage;
import com.platform.ingestion.messaging.PublishException;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Test doubles: a JwtDecoder that understands tokens of the form {@code role[.tenant[.device]]} (so no Keycloak
 * is needed while the real authentication converter and filter chain still run), an EventPublisher that can be
 * switched to failure mode and otherwise delegates to the real Kafka publisher, and an in-memory device
 * credential provisioner.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

    public static class TogglingEventPublisher implements EventPublisher {

        private final KafkaEventPublisher delegate;
        private final AtomicBoolean failing = new AtomicBoolean(false);
        private final AtomicReference<Function<OutboxMessage, CompletableFuture<Void>>> sender =
                new AtomicReference<>();

        TogglingEventPublisher(KafkaEventPublisher delegate) {
            this.delegate = delegate;
        }

        public void setFailing(boolean failing) {
            this.failing.set(failing);
        }

        /**
         * When set, publish() calls this instead of Kafka. It may return a future that completes later or
         * exceptionally, or throw to simulate an immediate failure. null restores Kafka.
         */
        public void setSender(Function<OutboxMessage, CompletableFuture<Void>> sender) {
            this.sender.set(sender);
        }

        /** Like {@link #setSender} for a publisher that acknowledges at once unless the hook throws. */
        public void setInterceptor(Consumer<OutboxMessage> interceptor) {
            setSender(
                    interceptor == null
                            ? null
                            : message -> {
                                interceptor.accept(message);
                                return CompletableFuture.completedFuture(null);
                            });
        }

        @Override
        public CompletableFuture<Void> publish(OutboxMessage message) {
            Function<OutboxMessage, CompletableFuture<Void>> hook = sender.get();
            if (hook != null) {
                return hook.apply(message);
            }
            if (failing.get()) {
                throw new PublishException("simulated broker outage", null);
            }
            return delegate.publish(message);
        }
    }

    /** In-memory identity provider: remembers clients and can be made to fail or to run a hook mid-creation. */
    public static class FakeDeviceCredentialProvisioner implements DeviceCredentialProvisioner {

        public record Client(String secret, boolean enabled) {}

        public final Map<String, Client> clients = new ConcurrentHashMap<>();
        public final List<String> deleted = new CopyOnWriteArrayList<>();
        private volatile boolean unreachable;
        private volatile Runnable afterCreate;

        public void setUnreachable(boolean unreachable) {
            this.unreachable = unreachable;
        }

        /** Runs after the client exists but before create() returns, to simulate a concurrent change. */
        public void setAfterCreate(Runnable afterCreate) {
            this.afterCreate = afterCreate;
        }

        public void reset() {
            unreachable = false;
            afterCreate = null;
            deleted.clear();
        }

        private void requireReachable() {
            if (unreachable) {
                throw new CredentialProvisioningException(Reason.UNAVAILABLE, "identity provider unreachable");
            }
        }

        @Override
        public DeviceCredential create(String tenantCode, String deviceId) {
            requireReachable();
            String clientId = DeviceClientIds.forDevice(tenantCode, deviceId);
            if (clients.containsKey(clientId)) {
                throw new CredentialProvisioningException(Reason.CONFLICT, "exists");
            }
            String secret = "secret-" + UUID.randomUUID();
            clients.put(clientId, new Client(secret, true));
            Runnable hook = afterCreate;
            if (hook != null) {
                hook.run();
            }
            return new DeviceCredential(clientId, secret);
        }

        @Override
        public String rotateSecret(String clientId) {
            requireReachable();
            Client client = clients.get(clientId);
            if (client == null) {
                throw new CredentialProvisioningException(Reason.NOT_FOUND, "missing");
            }
            String secret = "secret-" + UUID.randomUUID();
            clients.put(clientId, new Client(secret, client.enabled()));
            return secret;
        }

        @Override
        public void disable(String clientId) {
            requireReachable();
            clients.computeIfPresent(clientId, (id, c) -> new Client(c.secret(), false));
        }

        @Override
        public void delete(String clientId) {
            requireReachable();
            clients.remove(clientId);
            deleted.add(clientId);
        }
    }

    /** Keeps every ended span; Spring Boot adds all SpanProcessor beans to the tracer provider. */
    public static class CapturedSpans implements SpanProcessor {

        private final List<SpanData> ended = new CopyOnWriteArrayList<>();

        public List<SpanData> ended() {
            return ended;
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {}

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            ended.add(span.toSpanData());
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }
    }

    @Bean
    CapturedSpans capturedSpans() {
        return new CapturedSpans();
    }

    @Bean
    @Primary
    FakeDeviceCredentialProvisioner fakeDeviceCredentialProvisioner() {
        return new FakeDeviceCredentialProvisioner();
    }

    /** Superuser template for assertions and fixtures that must see raw rows; it is not part of the application. */
    @Bean
    JdbcTemplate ownerJdbc(
            @Value("${spring.flyway.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    /**
     * Keeps the application's template on the scoped pool: defining {@link #ownerJdbc} would otherwise make
     * Spring Boot skip its own JdbcTemplate.
     */
    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Primary
    TogglingEventPublisher togglingEventPublisher(KafkaEventPublisher kafkaEventPublisher) {
        return new TogglingEventPublisher(kafkaEventPublisher);
    }

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return token -> {
            String[] parts = token.split("[.]", 3);
            if (parts[0].isBlank()
                    || !List.of("platform-admin", "tenant-admin", "tenant-ingest", "no-role")
                            .contains(parts[0])) {
                throw new BadJwtException("unknown test token");
            }
            return TestJwts.jwt(
                    token,
                    parts.length > 1 ? parts[1] : null,
                    parts.length > 2 ? parts[2] : null,
                    parts[0].equals("no-role") ? List.of("offline_access") : List.of(parts[0], "offline_access"));
        };
    }
}
