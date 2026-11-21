import io
import json
import os
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from unittest import mock

import redis
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

import consumer as c

EID = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b"
EID2 = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"


def good(**over):
    ev = {
        "event_id": EID,
        "schema_version": "1.0",
        "tenant_id": "tenant-alpha",
        "ingestion_id": "i1",
        "contract_id": "c1",
        "contract_version": 1,
        "device_id": "MACHINE-001",
        "sensor_id": "TEMP-001",
        "value": 92.5,
        "unit": "CELSIUS",
        "event_timestamp": "2026-11-17T14:30:00Z",
        "received_at": "2026-11-17T14:30:01.123Z",
        "metadata": {},
    }
    ev.update(over)
    return ev


class ValidateTest(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(c.validate_event(good()), [])

    def test_missing_field(self):
        ev = good()
        del ev["device_id"]
        self.assertIn("missing field device_id", c.validate_event(ev))

    def test_wrong_types(self):
        self.assertTrue(c.validate_event(good(value="hot")))
        self.assertTrue(c.validate_event(good(value=True)))
        self.assertTrue(c.validate_event(good(contract_version="1")))
        self.assertTrue(c.validate_event(good(event_timestamp="yesterday")))
        self.assertTrue(c.validate_event(good(metadata="x")))

    def test_not_object(self):
        self.assertTrue(c.validate_event([1]))

    def test_int_value_ok(self):
        self.assertEqual(c.validate_event(good(value=20)), [])


class DqTest(unittest.TestCase):
    NOW = datetime(2026, 11, 17, 14, 30, tzinfo=timezone.utc)

    def test_ok(self):
        self.assertEqual(c.dq_warnings(good(), self.NOW), [])

    def test_celsius_out_of_range(self):
        self.assertEqual(len(c.dq_warnings(good(value=151), self.NOW)), 1)
        self.assertEqual(len(c.dq_warnings(good(value=-51), self.NOW)), 1)

    def test_fahrenheit_range(self):
        self.assertEqual(c.dq_warnings(good(unit="FAHRENHEIT", value=198.5), self.NOW), [])
        self.assertEqual(len(c.dq_warnings(good(unit="FAHRENHEIT", value=303), self.NOW)), 1)

    def test_unknown_unit(self):
        self.assertEqual(len(c.dq_warnings(good(unit="KELVIN"), self.NOW)), 1)

    def test_future_timestamp(self):
        soon = (self.NOW + timedelta(minutes=4)).isoformat()
        far = (self.NOW + timedelta(minutes=6)).isoformat()
        self.assertEqual(c.dq_warnings(good(event_timestamp=soon), self.NOW), [])
        self.assertEqual(len(c.dq_warnings(good(event_timestamp=far), self.NOW)), 1)


class FakeRedis:
    """The slice of redis-py that DedupStore uses, with a controllable clock and failure."""

    def __init__(self):
        self.data = {}  # key -> (value, expires_at)
        self.ttls = {}
        self.now = 0
        self.down = False

    def set(self, name, value, nx=False, ex=None):
        if self.down:
            raise redis.ConnectionError("connection refused")
        entry = self.data.get(name)
        if nx and entry is not None and entry[1] > self.now:
            return None
        self.data[name] = (value, self.now + ex)
        self.ttls[name] = ex
        return True

    def close(self):
        pass


def store_on(fake, ttl=604800):
    return c.DedupStore(fake, ttl)


class DedupTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeRedis()
        self.store = store_on(self.fake)

    def run_quiet(self, raw):
        buf = io.StringIO()
        with redirect_stdout(buf):
            r = c.process_payload(raw, self.store)
        return r, [json.loads(line) for line in buf.getvalue().splitlines()]

    def test_dedup(self):
        raw = json.dumps(good())
        r1, logs1 = self.run_quiet(raw)
        r2, logs2 = self.run_quiet(raw)
        self.assertEqual((r1, r2), ("processed", "duplicate"))
        self.assertEqual(logs1[-1]["event_id"], EID)
        self.assertIn("skipped", logs2[-1]["msg"])

    def test_different_ids_both_processed(self):
        self.assertEqual(self.run_quiet(json.dumps(good(event_id=EID)))[0], "processed")
        self.assertEqual(self.run_quiet(json.dumps(good(event_id=EID2)))[0], "processed")

    def test_two_instances_sharing_redis_process_an_event_once(self):
        second = store_on(self.fake)
        raw = json.dumps(good())
        with redirect_stdout(io.StringIO()):
            results = [c.process_payload(raw, self.store), c.process_payload(raw, second)]
        self.assertEqual(results, ["processed", "duplicate"])
        self.assertEqual(self.fake.data[f"dedup:{EID}"][0], "tenant-alpha")

    def test_key_has_the_configured_ttl_and_expires_with_it(self):
        short = store_on(self.fake, ttl=60)
        self.assertTrue(short.first_seen(EID, "t"))
        self.assertEqual(self.fake.ttls[f"dedup:{EID}"], 60)
        self.fake.now = 59
        self.assertFalse(short.first_seen(EID, "t"))
        self.fake.now = 61
        self.assertTrue(short.first_seen(EID, "t"))

    def test_default_ttl_is_the_seven_day_idempotency_window(self):
        self.store.first_seen(EID, "t")
        self.assertEqual(self.fake.ttls[f"dedup:{EID}"], 7 * 24 * 3600)

    def test_from_env_reads_settings_without_connecting(self):
        env = {"REDIS_HOST": "r", "REDIS_PORT": "6380", "REDIS_PASSWORD": "pw", "DEDUP_TTL_SECONDS": "30"}
        with mock.patch.dict(os.environ, env):
            store = c.DedupStore.from_env()
        kw = store.client.connection_pool.connection_kwargs
        self.assertEqual((kw["host"], kw["port"], kw["password"]), ("r", 6380, "pw"))
        self.assertEqual((kw["socket_timeout"], kw["socket_connect_timeout"]), (2, 2))
        # client-side retries would turn the 2 s timeout into about 25 s per message during an outage
        self.assertEqual(kw["retry"].get_retries(), 0)
        self.assertEqual(store.ttl_seconds, 30)

    def test_event_id_that_is_not_a_uuid_is_invalid_and_never_reaches_redis(self):
        for bad in ("e1", "x" * 5000, "dedup:../../x", EID + "-extra"):
            self.assertEqual(self.run_quiet(json.dumps(good(event_id=bad)))[0], "invalid")
        self.assertEqual(self.fake.data, {})

    def test_uppercase_uuid_is_the_same_event(self):
        self.assertEqual(self.run_quiet(json.dumps(good(event_id=EID)))[0], "processed")
        self.assertEqual(self.run_quiet(json.dumps(good(event_id=EID.upper())))[0], "duplicate")

    def test_poison_not_json(self):
        r, logs = self.run_quiet(b"\xff{not json")
        self.assertEqual(r, "invalid")
        self.assertEqual(logs[0]["level"], "ERROR")

    def test_invalid_not_recorded_for_dedup(self):
        self.assertEqual(self.run_quiet(json.dumps(good(value="x")))[0], "invalid")
        self.assertEqual(self.run_quiet(json.dumps(good()))[0], "processed")

    def test_dq_warning_logged_but_processed(self):
        r, logs = self.run_quiet(json.dumps(good(value=999)))
        self.assertEqual(r, "processed")
        self.assertTrue(any(line["level"] == "WARN" for line in logs))


class FakeMessage:
    """The parts of a confluent_kafka.Message that processing reads."""

    def __init__(self, value, headers=None, topic="tenant-alpha.readings"):
        self._value, self._headers, self._topic = value, headers, topic

    def value(self):
        return self._value

    def headers(self):
        return self._headers

    def topic(self):
        return self._topic


class TracingTest(unittest.TestCase):
    TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    PARENT_SPAN_ID = "00f067aa0ba902b7"

    def setUp(self):
        self.store = store_on(FakeRedis())
        self.exporter = InMemorySpanExporter()
        provider = TracerProvider()
        provider.add_span_processor(SimpleSpanProcessor(self.exporter))
        self.tracer = provider.get_tracer("test")

    def handle(self, message):
        buf = io.StringIO()
        with redirect_stdout(buf):
            result = c.process_message(
                message.value(), message.headers(), self.store, {"topic": message.topic()}, self.tracer
            )
        logs = [json.loads(line) for line in buf.getvalue().splitlines()]
        return result, logs, self.exporter.get_finished_spans()

    def test_continues_the_trace_in_the_kafka_headers_and_logs_its_id(self):
        headers = [
            ("event_id", EID.encode()),
            ("traceparent", f"00-{self.TRACE_ID}-{self.PARENT_SPAN_ID}-01".encode()),
        ]
        result, logs, spans = self.handle(FakeMessage(json.dumps(good()), headers))

        self.assertEqual(result, "processed")
        self.assertEqual(len(spans), 1)
        span = spans[0]
        self.assertEqual(format(span.context.trace_id, "032x"), self.TRACE_ID)
        self.assertEqual(format(span.parent.span_id, "016x"), self.PARENT_SPAN_ID)
        self.assertNotEqual(format(span.context.span_id, "016x"), self.PARENT_SPAN_ID)
        processed = next(line for line in logs if line["msg"] == "event processed")
        self.assertEqual(processed["trace_id"], self.TRACE_ID)
        self.assertEqual(processed["span_id"], format(span.context.span_id, "016x"))

    def test_without_a_traceparent_a_new_trace_is_started_and_logged(self):
        result, logs, spans = self.handle(FakeMessage(json.dumps(good()), None))

        self.assertEqual(result, "processed")
        span = spans[0]
        self.assertIsNone(span.parent)
        self.assertEqual(logs[-1]["trace_id"], format(span.context.trace_id, "032x"))

    def test_malformed_traceparent_starts_a_new_trace_instead_of_failing(self):
        headers = [("traceparent", b"garbage"), ("tracestate", None)]
        result, logs, spans = self.handle(FakeMessage(json.dumps(good()), headers))

        self.assertEqual(result, "processed")
        self.assertIsNone(spans[0].parent)
        self.assertNotEqual(logs[-1]["trace_id"], "0" * 32)

    def test_invalid_and_duplicate_messages_are_traced_with_their_outcome(self):
        headers = [("traceparent", f"00-{self.TRACE_ID}-{self.PARENT_SPAN_ID}-01".encode())]
        self.handle(FakeMessage(b"not json", headers))
        self.handle(FakeMessage(json.dumps(good()), headers))
        _, logs, _ = self.handle(FakeMessage(json.dumps(good()), headers))

        self.assertEqual(
            [s.attributes["consumer.result"] for s in self.exporter.get_finished_spans()],
            ["invalid", "processed", "duplicate"],
        )
        self.assertEqual(logs[-1]["trace_id"], self.TRACE_ID)


class FakeKafkaConsumer:
    def __init__(self):
        self.commits, self.seeks = [], []

    def commit(self, message, asynchronous):
        self.commits.append(message.offset())

    def seek(self, tp):
        self.seeks.append((tp.topic, tp.partition, tp.offset))


class FakeRecord(FakeMessage):
    def __init__(self, value, offset):
        super().__init__(value)
        self._offset = offset

    def partition(self):
        return 0

    def offset(self):
        return self._offset


class RedisOutageTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeRedis()
        self.store = store_on(self.fake)
        self.kafka = FakeKafkaConsumer()
        self.pauses = []
        self.tracer = TracerProvider().get_tracer("test")

    def deliver(self, msg):
        buf = io.StringIO()
        with redirect_stdout(buf):
            ok = c.handle_record(msg, self.kafka, self.store, self.tracer, pause=self.pauses.append)
        return ok, buf.getvalue()

    def test_outage_does_not_commit_then_resumes_without_loss(self):
        msgs = [FakeRecord(json.dumps(good(event_id=e)), i) for i, e in enumerate((EID, EID2))]
        self.fake.down = True
        ok, out = self.deliver(msgs[0])
        self.assertFalse(ok)
        self.assertEqual(self.kafka.commits, [])
        self.assertEqual(self.kafka.seeks, [("tenant-alpha.readings", 0, 0)])
        self.assertEqual(self.pauses, [c.RETRY_PAUSE_SECONDS])
        self.assertIn("will retry", out)
        self.assertNotIn("event processed", out)

        self.fake.down = False
        for m in msgs:
            self.assertTrue(self.deliver(m)[0])
        self.assertEqual(self.kafka.commits, [0, 1])

    def test_error_log_does_not_contain_the_password(self):
        env = {"REDIS_HOST": "127.0.0.1", "REDIS_PORT": "1", "REDIS_PASSWORD": "s3cr3t-pw"}
        with mock.patch.dict(os.environ, env):
            self.store = c.DedupStore.from_env()
        ok, out = self.deliver(FakeRecord(json.dumps(good()), 0))
        self.assertFalse(ok)
        self.assertNotIn("s3cr3t-pw", out)
        self.assertEqual(self.kafka.commits, [])

    def test_duplicate_and_invalid_records_are_committed(self):
        self.assertTrue(self.deliver(FakeRecord(json.dumps(good()), 0))[0])
        self.assertTrue(self.deliver(FakeRecord(json.dumps(good()), 1))[0])
        self.assertTrue(self.deliver(FakeRecord(b"junk", 2))[0])
        self.assertEqual(self.kafka.commits, [0, 1, 2])


class SubscriptionTest(unittest.TestCase):
    def test_defaults_to_the_readings_pattern(self):
        self.assertEqual(c.subscription_topics({}), [r"^.*\.readings$"])

    def test_pattern_from_environment(self):
        self.assertEqual(c.subscription_topics({"TOPIC_PATTERN": r"^acme-.*\.readings$"}), [r"^acme-.*\.readings$"])

    def test_explicit_topics_win_over_the_pattern(self):
        env = {"TOPICS": " a.readings, b.readings ,", "TOPIC_PATTERN": "^x$"}
        self.assertEqual(c.subscription_topics(env), ["a.readings", "b.readings"])

    def test_blank_topics_fall_back_to_the_pattern(self):
        self.assertEqual(c.subscription_topics({"TOPICS": " , "}), [c.DEFAULT_TOPIC_PATTERN])

    def test_main_subscribes_with_the_pattern_and_rebalance_callbacks(self):
        fake = mock.MagicMock()
        fake.poll.side_effect = KeyboardInterrupt
        kafka = mock.MagicMock(Consumer=mock.MagicMock(return_value=fake), KafkaException=Exception)
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.dict("sys.modules", {"confluent_kafka": kafka}):
            with mock.patch.object(c.DedupStore, "from_env", return_value=mock.MagicMock()):
                with redirect_stdout(io.StringIO()):
                    with self.assertRaises(KeyboardInterrupt):
                        c.main()
        fake.subscribe.assert_called_once_with([c.DEFAULT_TOPIC_PATTERN], on_assign=c.on_assign, on_revoke=c.on_revoke)

    def test_assignment_callbacks_log_the_partitions(self):
        parts = [
            mock.Mock(topic="tenant-alpha.readings", partition=0),
            mock.Mock(topic="tenant-beta.readings", partition=2),
        ]
        for callback, msg in ((c.on_assign, "partitions assigned"), (c.on_revoke, "partitions revoked")):
            buf = io.StringIO()
            with redirect_stdout(buf):
                callback(None, parts)
            rec = json.loads(buf.getvalue())
            self.assertEqual(rec["msg"], msg)
            self.assertEqual(rec["count"], 2)
            self.assertEqual(
                rec["partitions"],
                [{"topic": "tenant-alpha.readings", "partition": 0}, {"topic": "tenant-beta.readings", "partition": 2}],
            )


if __name__ == "__main__":
    unittest.main()
