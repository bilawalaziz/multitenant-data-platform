package com.platform.ingestion.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across the outbox. The request thread stores the current context as a W3C traceparent with the
 * outbox row; the publisher, running later on a scheduler thread, starts its span from that stored value and injects
 * the span's context into the Kafka record. Nothing here may fail the caller: tracing problems degrade to an
 * untraced send.
 */
@Component
public class OutboxTracing {

    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";

    private static final Logger log = LoggerFactory.getLogger(OutboxTracing.class);
    private static final Pattern VALID_TRACEPARENT =
            Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** @return the traceparent of the span current on this thread, or null when there is none. */
    public String currentTraceParent() {
        try {
            Span current = tracer.currentSpan();
            return current == null ? null : inject(current.context()).get(TRACEPARENT);
        } catch (RuntimeException e) {
            log.debug("Could not capture the current trace context", e);
            return null;
        }
    }

    /**
     * Starts the publish span. With a stored traceparent it is that context's child; without one it is a new root,
     * never a child of whatever happens to be current on the scheduler thread.
     */
    public PublishSpan startPublish(String storedTraceParent, String topic, String eventId) {
        try {
            Span.Builder builder = storedTraceParent != null
                            && VALID_TRACEPARENT.matcher(storedTraceParent).matches()
                    ? propagator.extract(Map.of(TRACEPARENT, storedTraceParent), Map::get)
                    : tracer.spanBuilder().setNoParent();
            Span span = builder.name("outbox.publish")
                    .kind(Span.Kind.PRODUCER)
                    .tag("messaging.system", "kafka")
                    .tag("messaging.destination.name", topic)
                    .tag("event.id", eventId)
                    .start();
            return new PublishSpan(tracer, span, inject(span.context()));
        } catch (RuntimeException e) {
            log.warn("Could not start the outbox publish span, sending without trace context: {}", e.toString());
            return PublishSpan.NONE;
        }
    }

    private Map<String, String> inject(TraceContext context) {
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier;
    }

    /** One in-flight send's span. Ended exactly once, from whichever outcome is seen first. */
    public static final class PublishSpan {

        static final PublishSpan NONE = new PublishSpan(null, null, Map.of());

        private final Tracer tracer;
        private final Span span;
        private final Map<String, String> headers;
        private final AtomicBoolean ended = new AtomicBoolean();

        PublishSpan(Tracer tracer, Span span, Map<String, String> headers) {
            this.tracer = tracer;
            this.span = span;
            this.headers = Map.copyOf(headers);
        }

        /** traceparent and, when present, tracestate for the Kafka record. */
        public Map<String, String> headers() {
            return headers;
        }

        /** Makes the span current while the send is issued, so logs on this thread carry its ids. */
        public AutoCloseable makeCurrent() {
            if (span == null) {
                return () -> {};
            }
            try {
                Tracer.SpanInScope scope = tracer.withSpan(span);
                return scope::close;
            } catch (RuntimeException e) {
                return () -> {};
            }
        }

        public void end(Throwable failure) {
            if (span == null || !ended.compareAndSet(false, true)) {
                return;
            }
            try {
                if (failure != null) {
                    span.error(failure);
                }
                span.end();
            } catch (RuntimeException e) {
                log.debug("Could not end the outbox publish span", e);
            }
        }
    }
}
