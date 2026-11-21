import io
import json
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone

from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

import consumer as c


def good(**over):
    ev = {
        "event_id": "e1",
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


class DedupTest(unittest.TestCase):
    def setUp(self):
        self.store = c.DedupStore(":memory:")

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
        self.assertEqual(logs1[-1]["event_id"], "e1")
        self.assertIn("skipped", logs2[-1]["msg"])

    def test_different_ids_both_processed(self):
        self.assertEqual(self.run_quiet(json.dumps(good(event_id="a")))[0], "processed")
        self.assertEqual(self.run_quiet(json.dumps(good(event_id="b")))[0], "processed")

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
        self.store = c.DedupStore(":memory:")
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
            ("event_id", b"e1"),
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


if __name__ == "__main__":
    unittest.main()
