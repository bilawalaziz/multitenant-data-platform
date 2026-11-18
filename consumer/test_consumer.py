import io
import json
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone

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


if __name__ == "__main__":
    unittest.main()
