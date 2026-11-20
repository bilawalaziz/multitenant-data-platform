package com.platform.ingestion.it;

import com.platform.ingestion.TestJwts;
import com.platform.ingestion.messaging.EventPublisher;
import com.platform.ingestion.messaging.KafkaEventPublisher;
import com.platform.ingestion.messaging.OutboxMessage;
import com.platform.ingestion.messaging.PublishException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Test doubles: a JwtDecoder that understands tokens of the form {@code role[.tenant]} (so no Keycloak is
 * needed while the real authentication converter and filter chain still run), and an EventPublisher that can be
 * switched to failure mode and otherwise delegates to the real Kafka publisher.
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

    @Bean
    @Primary
    TogglingEventPublisher togglingEventPublisher(KafkaEventPublisher kafkaEventPublisher) {
        return new TogglingEventPublisher(kafkaEventPublisher);
    }

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return token -> {
            String[] parts = token.split("[.]", 2);
            if (parts[0].isBlank()
                    || !List.of("platform-admin", "tenant-admin", "tenant-ingest", "no-role")
                            .contains(parts[0])) {
                throw new BadJwtException("unknown test token");
            }
            Jwt jwt = TestJwts.jwt(
                    token,
                    parts.length > 1 ? parts[1] : null,
                    parts[0].equals("no-role") ? List.of("offline_access") : List.of(parts[0], "offline_access"));
            return jwt;
        };
    }
}
