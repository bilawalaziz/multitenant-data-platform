"""Downstream consumer of canonical sensor events.

Delivery is at-least-once. Auto-commit is off and the offset is committed synchronously after a message has been
validated, quality-checked and recorded for deduplication.

Messages that are not valid JSON or miss canonical fields are logged as errors and committed (skipped), so one bad
record cannot block a partition; a real deployment would send them to a dead-letter topic. Duplicates (same event_id,
tracked in Redis so that every replica in the consumer group shares one view) are logged and committed. Topics that
do not exist yet are tolerated because tenants are created at runtime; the consumer picks them up through periodic
metadata refresh. Infrastructure failures such as a Redis error or timeout do not commit: the consumer seeks back and
retries after a pause. On SIGTERM/SIGINT it finishes the current message, commits and closes. All log lines are
single-line JSON.

Tracing: each message is handled inside a consume span that continues the trace carried in the record's W3C
traceparent header (a new trace when there is none), and every log line written inside it carries trace_id and
span_id. Spans are exported over OTLP/HTTP only when OTEL_EXPORTER_OTLP_ENDPOINT is set; otherwise they stay local, so
the ids still appear in the logs but nothing is sent anywhere.
"""

import json
import logging
import os
import re
import signal
import sys
import time
from datetime import datetime, timedelta, timezone

import redis
from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from redis.backoff import NoBackoff
from redis.retry import Retry

REQUIRED_FIELDS = {
    "event_id": str,
    "schema_version": str,
    "tenant_id": str,
    "ingestion_id": str,
    "contract_id": str,
    "contract_version": int,
    "device_id": str,
    "sensor_id": str,
    "value": (int, float),
    "unit": str,
    "event_timestamp": str,
    "received_at": str,
}
UNIT_RANGES = {"CELSIUS": (-50.0, 150.0), "FAHRENHEIT": (-58.0, 302.0)}
MAX_FUTURE_SKEW = timedelta(minutes=5)
# The producer always writes a UUID, so anything else is rejected before it can become an arbitrary or huge Redis key.
EVENT_ID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
DEFAULT_DEDUP_TTL_SECONDS = 604800  # 7 days, the application's idempotency window
REDIS_TIMEOUT_SECONDS = 2
RETRY_PAUSE_SECONDS = 1


def kafka_security_config(environ=os.environ):
    """librdkafka security settings from KAFKA_SECURITY_PROTOCOL / KAFKA_SASL_*; empty for the plaintext default."""
    protocol = environ.get("KAFKA_SECURITY_PROTOCOL", "").strip().upper()
    if not protocol or protocol == "PLAINTEXT":
        return {}
    conf = {"security.protocol": protocol.lower()}
    if "SASL" in protocol:
        for var, key in (
            ("KAFKA_SASL_MECHANISM", "sasl.mechanisms"),
            ("KAFKA_SASL_USERNAME", "sasl.username"),
            ("KAFKA_SASL_PASSWORD", "sasl.password"),
        ):
            if environ.get(var):
                conf[key] = environ[var]
    return conf


def redis_ssl_enabled(environ=os.environ):
    return environ.get("REDIS_SSL", "").strip().lower() in ("1", "true", "yes", "on")


def log(level, msg, **fields):
    rec = {"ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds"), "level": level, "msg": msg}
    ctx = trace.get_current_span().get_span_context()
    if ctx.is_valid:
        rec["trace_id"] = trace.format_trace_id(ctx.trace_id)
        rec["span_id"] = trace.format_span_id(ctx.span_id)
    rec.update(fields)
    print(json.dumps(rec, default=str), flush=True)


class _JsonLogHandler(logging.Handler):
    """Routes the OpenTelemetry SDK's own warnings (for example an unreachable collector) into the JSON log."""

    def emit(self, record):
        level = "ERROR" if record.levelno >= logging.ERROR else "WARN"
        log(level, record.getMessage(), logger=record.name)


def setup_tracing():
    """Returns (tracer, provider). Export is on only when an OTLP endpoint is configured."""
    service = os.environ.get("OTEL_SERVICE_NAME", "sensor-consumer")
    provider = TracerProvider(resource=Resource.create({"service.name": service}))
    if os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT") or os.environ.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"):
        # Imported here so a consumer without an endpoint never loads the HTTP client stack.
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.trace.export import BatchSpanProcessor

        sdk_logger = logging.getLogger("opentelemetry")
        sdk_logger.addHandler(_JsonLogHandler())
        sdk_logger.propagate = False
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    return provider.get_tracer("sensor-consumer"), provider


def trace_context_from_headers(headers):
    """Extracts the parent context from Kafka record headers ([(name, bytes)] or None)."""
    carrier = {}
    for name, value in headers or []:
        if value is not None and name in ("traceparent", "tracestate"):
            carrier[name] = value.decode("utf-8", errors="replace") if isinstance(value, bytes) else str(value)
    return TraceContextTextMapPropagator().extract(carrier)


def parse_ts(s):
    """Parse an ISO-8601 instant (Z or offset); returns an aware datetime or None."""
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def validate_event(ev):
    """Return a list of problems (empty list = valid canonical event)."""
    if not isinstance(ev, dict):
        return ["event is not a JSON object"]
    problems = []
    for name, typ in REQUIRED_FIELDS.items():
        if name not in ev or ev[name] is None:
            problems.append(f"missing field {name}")
            continue
        v = ev[name]
        if isinstance(v, bool) or not isinstance(v, typ):
            problems.append(f"field {name} has wrong type {type(v).__name__}")
        elif isinstance(v, str) and not v.strip():
            problems.append(f"field {name} is empty")
    for name in ("event_timestamp", "received_at"):
        if isinstance(ev.get(name), str) and parse_ts(ev[name]) is None:
            problems.append(f"field {name} is not an ISO-8601 timestamp")
    if isinstance(ev.get("event_id"), str) and not EVENT_ID_RE.fullmatch(ev["event_id"]):
        problems.append("field event_id is not a UUID")
    if ev.get("metadata") is not None and not isinstance(ev["metadata"], dict):
        problems.append("field metadata must be an object")
    return problems


def dq_warnings(ev, now=None):
    """Data-quality warnings for a structurally valid event."""
    now = now or datetime.now(timezone.utc)
    out = []
    rng = UNIT_RANGES.get(ev["unit"])
    if rng is None:
        out.append(f"no plausible range known for unit {ev['unit']}")
    elif not rng[0] <= ev["value"] <= rng[1]:
        out.append(f"value {ev['value']} outside plausible range {rng[0]}..{rng[1]} for {ev['unit']}")
    ts = parse_ts(ev["event_timestamp"])
    if ts is not None and ts > now + MAX_FUTURE_SKEW:
        out.append(f"event_timestamp {ev['event_timestamp']} is more than 5 minutes in the future")
    return out


class DedupStore:
    """Shared event-id registry: one Redis key per event, expiring after the idempotency window."""

    def __init__(self, client, ttl_seconds=DEFAULT_DEDUP_TTL_SECONDS):
        self.client = client
        self.ttl_seconds = ttl_seconds

    @classmethod
    def from_env(cls):
        # Connections are made lazily, so a Redis that is down at startup is retried per message, not fatal.
        # The client's own retries are off: they would multiply the timeout (about 25 s per message when Redis
        # hangs) and outlast a container's stop grace period. The consumer loop already retries the message.
        client = redis.Redis(
            host=os.environ.get("REDIS_HOST", "localhost"),
            port=int(os.environ.get("REDIS_PORT", "6379")),
            password=os.environ.get("REDIS_PASSWORD") or None,
            ssl=redis_ssl_enabled(),
            socket_connect_timeout=REDIS_TIMEOUT_SECONDS,
            socket_timeout=REDIS_TIMEOUT_SECONDS,
            retry=Retry(NoBackoff(), 0),
            decode_responses=True,
        )
        return cls(client, int(os.environ.get("DEDUP_TTL_SECONDS", DEFAULT_DEDUP_TTL_SECONDS)))

    def first_seen(self, event_id, tenant_id):
        """True if event_id was not seen before (and records it). Redis errors propagate so the caller retries."""
        # SET NX is atomic, so of several replicas racing on one event exactly one wins.
        return bool(self.client.set(f"dedup:{event_id.lower()}", tenant_id, nx=True, ex=self.ttl_seconds))

    def close(self):
        self.client.close()


def process_payload(raw, store, kafka_meta=None):
    """Process one message body. Returns 'processed' | 'duplicate' | 'invalid'. Never raises on bad data."""
    meta = kafka_meta or {}
    try:
        ev = json.loads(raw)
    except (ValueError, TypeError) as e:  # UnicodeDecodeError is a ValueError
        log("ERROR", "invalid message: not JSON", reason=str(e), **meta)
        return "invalid"
    problems = validate_event(ev)
    if problems:
        log(
            "ERROR",
            "invalid message: canonical validation failed",
            problems=problems,
            event_id=ev.get("event_id") if isinstance(ev, dict) else None,
            **meta,
        )
        return "invalid"
    # Marked before processing. That is safe only because processing is logging: a consumer with real side effects
    # must mark after processing, or use a two-phase (pending/done) marker, or a crash here loses the event.
    ids = {"event_id": ev["event_id"], "tenant_id": ev["tenant_id"], "device_id": ev["device_id"]}
    if not store.first_seen(ev["event_id"], ev["tenant_id"]):
        log("INFO", "duplicate event skipped", **ids, **meta)
        return "duplicate"
    for w in dq_warnings(ev):
        log("WARN", "data quality warning", dq_warning=w, **ids, **meta)
    log(
        "INFO",
        "event processed",
        **ids,
        sensor_id=ev["sensor_id"],
        value=ev["value"],
        unit=ev["unit"],
        event_timestamp=ev["event_timestamp"],
        **meta,
    )
    return "processed"


def process_message(raw, headers, store, kafka_meta, tracer):
    """Process one Kafka record inside a consume span that continues the producer's trace."""
    meta = kafka_meta or {}
    with tracer.start_as_current_span(
        "sensor-readings process",
        context=trace_context_from_headers(headers),
        kind=trace.SpanKind.CONSUMER,
        attributes={
            "messaging.system": "kafka",
            "messaging.destination.name": meta.get("topic", ""),
            "messaging.operation": "process",
        },
    ) as span:
        result = process_payload(raw, store, kafka_meta)
        span.set_attribute("consumer.result", result)
        return result


def handle_record(msg, consumer, store, tracer, pause=time.sleep):
    """Process one record and commit it. On an infrastructure failure rewind instead, so the record is retried."""
    from confluent_kafka import TopicPartition

    meta = {"topic": msg.topic(), "partition": msg.partition(), "offset": msg.offset()}
    try:
        process_message(msg.value(), msg.headers(), store, meta, tracer)
    except Exception as e:  # infrastructure failure: do not commit, rewind and retry
        log("ERROR", "processing failed; offset not committed, will retry", error=repr(e), **meta)
        consumer.seek(TopicPartition(msg.topic(), msg.partition(), msg.offset()))
        # A Redis call waits at most the socket timeout (no client retries); this pause keeps an outage from spinning.
        pause(RETRY_PAUSE_SECONDS)
        return False
    consumer.commit(message=msg, asynchronous=False)
    return True


DEFAULT_TOPIC_PATTERN = r"^.*\.readings$"


def subscription_topics(environ=os.environ):
    """Topics to subscribe to: an explicit TOPICS list wins, else one regex (librdkafka treats a leading ^ as one)."""
    explicit = [t.strip() for t in environ.get("TOPICS", "").split(",") if t.strip()]
    if explicit:
        return explicit
    return [environ.get("TOPIC_PATTERN", "").strip() or DEFAULT_TOPIC_PATTERN]


def _assignment_logger(msg):
    def on_event(_consumer, partitions):
        listed = [{"topic": p.topic, "partition": p.partition} for p in partitions]
        log("INFO", msg, partitions=listed, count=len(listed))

    return on_event


# Which partitions this replica owns, so a rebalance across replicas is visible in the logs.
on_assign = _assignment_logger("partitions assigned")
on_revoke = _assignment_logger("partitions revoked")


def main():
    from confluent_kafka import Consumer, KafkaException

    bootstrap = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094")
    topics = subscription_topics()
    group = os.environ.get("GROUP_ID", "sensor-readings-consumer")
    store = DedupStore.from_env()
    tracer, tracer_provider = setup_tracing()
    running = True

    def stop(signum, _frame):
        nonlocal running
        running = False
        log("INFO", "shutdown signal received", signal=signum)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    consumer = Consumer(
        {
            "bootstrap.servers": bootstrap,
            "group.id": group,
            "enable.auto.commit": False,
            "auto.offset.reset": "earliest",
            "topic.metadata.refresh.interval.ms": 10000,  # pick up topics created later by tenant registration
            "allow.auto.create.topics": False,
            **kafka_security_config(),
        }
    )
    consumer.subscribe(topics, on_assign=on_assign, on_revoke=on_revoke)
    log("INFO", "consumer started", bootstrap=bootstrap, topics=topics, group_id=group)
    try:
        while running:
            msg = consumer.poll(1.0)
            if msg is None:
                continue
            err = msg.error()
            if err is not None:
                # unknown topic/partition (topic not created yet) is expected and non-fatal
                log("WARN", "kafka consumer error (continuing)", error=str(err), code=err.code())
                time.sleep(1)
                continue
            handle_record(msg, consumer, store, tracer)
    except KafkaException as e:
        log("ERROR", "fatal kafka error", error=str(e))
        return 1
    finally:
        consumer.close()
        store.close()
        tracer_provider.shutdown()  # flushes spans still queued for export
        log("INFO", "consumer stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
