#!/usr/bin/env python3
"""End-to-end demo of the multi-tenant sensor ingestion platform (stdlib only, cross-platform).

Usage:
    python scripts/demo.py all            # run the 16 scenarios in order
    python scripts/demo.py 6 7 8          # run selected scenarios by number
    python scripts/demo.py ingest-alpha   # or by name (see SCENARIOS below)
    python scripts/demo.py list

Configuration (environment variables or a .env file in the project root):
    APP_URL (http://localhost:8080)  MANAGEMENT_URL (http://localhost:8081)  KEYCLOAK_URL (http://localhost:8180)
    PROMETHEUS_URL (http://localhost:9090)  GRAFANA_URL (http://localhost:3000)
    DEMO_USER_PASSWORD  ALPHA_INGEST_CLIENT_SECRET  BETA_INGEST_CLIENT_SECRET
    DEMO_FORCE_UPLOAD=1 uploads a new contract version even if an active one exists.

Every ingestion uses a fresh uuid-based Idempotency-Key, so the demo is re-runnable. Exit code is non-zero if any
expectation failed.
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
STATE_FILE = os.path.join(ROOT, ".demo-state.json")
RUN_ID = uuid.uuid4().hex[:8]


# ----------------------------------------------------------------------------- config
def load_env_file(path):
    out = {}
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip().strip('"').strip("'")
    return out


_ENV = load_env_file(os.path.join(ROOT, ".env"))


def cfg(name, default=None):
    return os.environ.get(name) or _ENV.get(name) or default


APP = cfg("APP_URL", "http://localhost:%s" % cfg("APP_HOST_PORT", "8080")).rstrip("/")
MGMT = cfg("MANAGEMENT_URL", "http://localhost:8081").rstrip("/")
KC = cfg("KEYCLOAK_URL", "http://localhost:%s" % cfg("KEYCLOAK_HOST_PORT", "8180")).rstrip("/")
PROM = cfg("PROMETHEUS_URL", "http://localhost:%s" % cfg("PROMETHEUS_HOST_PORT", "9090")).rstrip("/")
GRAFANA = cfg("GRAFANA_URL", "http://localhost:%s" % cfg("GRAFANA_HOST_PORT", "3000")).rstrip("/")
TOKEN_URL = KC + "/realms/sensor-platform/protocol/openid-connect/token"
TENANTS = {"alpha": "tenant-alpha", "beta": "tenant-beta"}
DEVICES = {"alpha": "MACHINE-001", "beta": "MACHINE-002"}


# ----------------------------------------------------------------------------- output / expectations
_COLOR = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None
if os.name == "nt" and _COLOR:
    os.system("")  # enable ANSI on Windows terminals


def c(code, s):
    return "\033[%sm%s\033[0m" % (code, s) if _COLOR else s


class Results:
    passed = 0
    failed = 0
    failures = []


def title(n, text):
    print("\n" + c("1;36", "=== [%s] %s ===" % (n, text)))


def info(text):
    print("    " + text)


def expect(name, ok, detail=""):
    if ok:
        Results.passed += 1
        print("  " + c("32", "PASS") + "  " + name)
    else:
        Results.failed += 1
        Results.failures.append(name)
        print("  " + c("31", "FAIL") + "  " + name + ("  -> " + str(detail) if detail != "" else ""))
    return ok


def pretty(obj):
    return json.dumps(obj, indent=2, sort_keys=True) if not isinstance(obj, str) else obj


# ----------------------------------------------------------------------------- HTTP
def http(method, url, headers=None, body=None, timeout=30):
    """Returns (status, parsed_body_or_text, response_headers). Never raises on HTTP error statuses."""
    data = body
    if isinstance(body, (dict, list)):
        data = json.dumps(body).encode("utf-8")
        headers = dict(headers or {})
        headers.setdefault("Content-Type", "application/json")
    elif isinstance(body, str):
        data = body.encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, raw, hdrs = r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        status, raw, hdrs = e.code, e.read(), dict(e.headers)
    except (urllib.error.URLError, OSError) as e:
        return 0, "connection error: %s" % e, {}
    text = raw.decode("utf-8", "replace")
    try:
        return status, json.loads(text) if text.strip() else None, hdrs
    except ValueError:
        return status, text, hdrs


def encode_multipart(fields, files):
    """fields: {name: str}; files: {name: (filename, bytes, content_type)} -> (body, content_type)."""
    boundary = "----demo" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(
            ('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n' % (boundary, k, v)).encode("utf-8")
        )
    for k, (fname, content, ctype) in files.items():
        parts.append(
            (
                '--%s\r\nContent-Disposition: form-data; name="%s"; filename="%s"\r\nContent-Type: %s\r\n\r\n'
                % (boundary, k, fname, ctype)
            ).encode("utf-8")
            + content
            + b"\r\n"
        )
    parts.append(("--%s--\r\n" % boundary).encode("utf-8"))
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


def bearer(tok, extra=None):
    h = {"Authorization": "Bearer " + tok, "Accept": "application/json"}
    h.update(extra or {})
    return h


# ----------------------------------------------------------------------------- tokens
_TOKENS = {}


def token(identity):
    if identity in _TOKENS:
        return _TOKENS[identity]
    if identity in ("platform-admin", "alpha-admin", "beta-admin"):
        form = {
            "grant_type": "password",
            "client_id": "platform-cli",
            "username": identity,
            "password": cfg("DEMO_USER_PASSWORD", ""),
        }
    elif identity in ("alpha-ingest", "beta-ingest"):
        t = identity.split("-")[0]
        form = {
            "grant_type": "client_credentials",
            "client_id": "tenant-%s-ingest" % t,
            "client_secret": cfg("%s_INGEST_CLIENT_SECRET" % t.upper(), ""),
        }
    else:
        raise ValueError(identity)
    status, body, _ = http(
        "POST", TOKEN_URL, {"Content-Type": "application/x-www-form-urlencoded"}, urllib.parse.urlencode(form)
    )
    if status != 200 or not isinstance(body, dict) or "access_token" not in body:
        raise RuntimeError("token request for %s failed: %s %s" % (identity, status, body))
    _TOKENS[identity] = body["access_token"]
    return _TOKENS[identity]


def decode_jwt(tok):
    import base64

    seg = tok.split(".")[1]
    seg += "=" * (-len(seg) % 4)
    return json.loads(base64.urlsafe_b64decode(seg))


# ----------------------------------------------------------------------------- helpers
def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(st):
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(st, f, indent=2)


def read_demo(*parts):
    with open(os.path.join(ROOT, "demo", *parts), "rb") as f:
        return f.read()


def payload(name):
    return json.loads(read_demo("payloads", name))


def as_list(body):
    """Accept a bare list or an object wrapping one (items/content/events/data...)."""
    if isinstance(body, list):
        return body
    if isinstance(body, dict):
        for k in ("items", "content", "events", "data", "ingestions", "contracts", "devices", "tenants", "outbox"):
            if isinstance(body.get(k), list):
                return body[k]
    return []


def pick(d, *names):
    if isinstance(d, dict):
        for n in names:
            if d.get(n) is not None:
                return d[n]
    return None


def compose(*args, check=False):
    cmd = ["docker", "compose"] + list(args)
    try:
        p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=300)
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, str(e)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def ingest(identity, body, key=None, extra_headers=None):
    key = key or "demo-%s-%s" % (RUN_ID, uuid.uuid4().hex[:12])
    h = bearer(token(identity), {"Idempotency-Key": key})
    h.update(extra_headers or {})
    status, resp, hdrs = http("POST", APP + "/api/v1/ingestion/readings", h, body)
    return status, resp, key, hdrs


def err_fields(resp):
    return [e.get("field") for e in (resp or {}).get("errors", [])] if isinstance(resp, dict) else []


def fresh_reading(kind, value):
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if kind == "alpha":
        p = payload("alpha-valid.json")
        p.update({"temperature": value, "recordedAt": now})
    else:
        p = payload("beta-valid.json")
        p.update({"reading": value, "timestamp": now})
    return p


def canonical_of(rec):
    if not isinstance(rec, dict):
        return None
    for k, v in rec.items():
        if "canonical" in k.lower() and isinstance(v, (dict, str)):
            return v if isinstance(v, dict) else json.loads(v)
    return None


# ----------------------------------------------------------------------------- scenarios
def s01_health():
    title(1, "Check services are healthy")
    st, body, _ = http("GET", MGMT + "/actuator/health")
    expect(
        "app /actuator/health is UP", st == 200 and isinstance(body, dict) and body.get("status") == "UP", (st, body)
    )
    st, body, _ = http("GET", KC + "/realms/sensor-platform/.well-known/openid-configuration")
    expect("keycloak realm sensor-platform reachable", st == 200 and isinstance(body, dict), st)
    st, _, _ = http("GET", PROM + "/-/healthy")
    expect("prometheus healthy", st == 200, st)
    st, _, _ = http("GET", GRAFANA + "/api/health")
    expect("grafana healthy", st == 200, st)
    rc, out = compose("ps", "--status", "running", "--services")
    running = set(out.split()) if rc == 0 else set()
    for svc in ("postgres", "kafka", "keycloak", "app", "consumer"):
        expect("docker compose service %s running" % svc, svc in running, out.strip()[:120])


def s02_tokens():
    title(2, "Obtain platform admin token (and inspect claims of all identities)")
    for ident in ("platform-admin", "alpha-admin", "beta-admin", "alpha-ingest", "beta-ingest"):
        try:
            claims = decode_jwt(token(ident))
        except Exception as e:  # noqa: BLE001
            expect("token for %s" % ident, False, e)
            continue
        info(
            "%-14s roles=%s tenant_id=%s aud=%s"
            % (ident, (claims.get("realm_access") or {}).get("roles"), claims.get("tenant_id"), claims.get("aud"))
        )
        expect(
            "token for %s has audience sensor-platform-api" % ident,
            "sensor-platform-api"
            in ([claims.get("aud")] if isinstance(claims.get("aud"), str) else claims.get("aud") or []),
        )


def s03_register():
    title(3, "Register tenants and devices (idempotent: 409 = already exists)")
    adm = token("platform-admin")
    for key, tid in TENANTS.items():
        st, body, _ = http(
            "POST",
            APP + "/api/v1/admin/tenants",
            bearer(adm),
            {"tenantId": tid, "displayName": "Tenant " + key.capitalize()},
        )
        expect("register %s (201 or already exists 409)" % tid, st in (201, 409), (st, body))
        st, body, _ = http(
            "POST",
            APP + "/api/v1/tenants/%s/devices" % tid,
            bearer(adm),
            {"deviceId": DEVICES[key], "description": "Demo machine of " + tid},
        )
        expect("register device %s for %s (201/409)" % (DEVICES[key], tid), st in (201, 409), (st, body))
    st, body, _ = http("GET", APP + "/api/v1/admin/tenants", bearer(adm))
    codes = [pick(t, "tenantId", "tenantCode", "id") for t in as_list(body)]
    expect("tenant list contains both tenants", all(t in codes for t in TENANTS.values()), codes)


def s04_upload():
    title(4, "Upload OpenAPI contract + mapping per tenant (as tenant admin)")
    state = load_state()
    for key, tid in TENANTS.items():
        admin = token("%s-admin" % key)
        st, active, _ = http("GET", APP + "/api/v1/tenants/%s/contracts/active" % tid, bearer(admin))
        if st == 200 and isinstance(active, dict) and not cfg("DEMO_FORCE_UPLOAD"):
            info(
                "%s already has an active contract (version %s); skipping upload (DEMO_FORCE_UPLOAD=1 to force)"
                % (tid, pick(active, "version", "contractVersion"))
            )
            expect("%s active contract present" % tid, True)
            state.pop("uploaded_" + key, None)
            continue
        body, ctype = encode_multipart(
            {"name": "%s-readings" % key},
            {
                "spec": ("openapi.yaml", read_demo("tenant-" + key, "openapi.yaml"), "application/yaml"),
                "mapping": ("mapping.json", read_demo("tenant-" + key, "mapping.json"), "application/json"),
            },
        )
        st, resp, _ = http(
            "POST", APP + "/api/v1/tenants/%s/contracts" % tid, bearer(admin, {"Content-Type": ctype}), body
        )
        ok = expect("upload contract for %s -> 201" % tid, st == 201, (st, resp))
        if ok:
            cid = pick(resp, "contractId", "id")
            ver = pick(resp, "version", "contractVersion")
            info("contractId=%s version=%s status=%s" % (cid, ver, pick(resp, "status")))
            state["uploaded_" + key] = {"contractId": cid, "version": ver}
    save_state(state)


def s05_activate():
    title(5, "Activate contract versions")
    state = load_state()
    for key, tid in TENANTS.items():
        admin = token("%s-admin" % key)
        up = state.get("uploaded_" + key)
        if up:
            st, resp, _ = http(
                "POST",
                APP + "/api/v1/tenants/%s/contracts/%s/versions/%s/activate" % (tid, up["contractId"], up["version"]),
                bearer(admin),
            )
            expect("activate %s contract v%s" % (tid, up["version"]), st in (200, 204), (st, resp))
            state.pop("uploaded_" + key, None)
        st, active, _ = http("GET", APP + "/api/v1/tenants/%s/contracts/active" % tid, bearer(admin))
        expect("%s has an ACTIVE contract" % tid, st == 200, (st, active))
        if st == 200:
            info(
                "active: contractId=%s version=%s"
                % (pick(active, "contractId", "id"), pick(active, "version", "contractVersion"))
            )
    save_state(state)


def _ingest_step(kind, n, label):
    title(n, label)
    tid = TENANTS[kind]
    p = payload("%s-valid.json" % kind)
    info("payload: " + json.dumps(p))
    st, resp, key, _ = ingest("%s-ingest" % kind, p)
    ok = expect(
        "%s ingest -> 202 ACCEPTED" % kind,
        st == 202 and isinstance(resp, dict) and resp.get("status") == "ACCEPTED",
        (st, resp),
    )
    if ok:
        info(pretty(resp))
        state = load_state()
        state["ingestion_" + kind] = {
            "ingestionId": resp.get("ingestionId"),
            "eventId": resp.get("eventId"),
            "tenantId": tid,
        }
        save_state(state)
        expect("response tenantId is %s (from token)" % tid, resp.get("tenantId") == tid, resp.get("tenantId"))


def s06_ingest_alpha():
    _ingest_step("alpha", 6, "Tenant alpha ingests a valid reading (camelCase shape)")


def s07_ingest_beta():
    _ingest_step("beta", 7, "Tenant beta ingests a different shape (snake_case, nested diagnostics)")


def s08_compare():
    title(8, "Fetch both ingestion records: canonical events side by side")
    state = load_state()
    events = {}
    for key, tid in TENANTS.items():
        ref = state.get("ingestion_" + key)
        if not ref:
            expect(
                "ingestion for %s recorded by scenario %s" % (key, 6 if key == "alpha" else 7),
                False,
                "run scenarios 6/7 first",
            )
            continue
        st, rec, _ = http(
            "GET", APP + "/api/v1/tenants/%s/ingestions/%s" % (tid, ref["ingestionId"]), bearer(token("%s-admin" % key))
        )
        expect("GET %s ingestion record -> 200" % tid, st == 200, (st, rec))
        ev = canonical_of(rec)
        expect("%s record contains a canonical event" % tid, ev is not None, rec)
        events[key] = ev or {}
    if len(events) == 2:
        keys = [
            "event_id",
            "schema_version",
            "tenant_id",
            "contract_version",
            "device_id",
            "sensor_id",
            "value",
            "unit",
            "event_timestamp",
            "metadata",
        ]
        print("\n    %-18s | %-34s | %s" % ("field", "tenant-alpha", "tenant-beta"))
        print("    " + "-" * 96)
        for k in keys:
            print("    %-18s | %-34s | %s" % (k, json.dumps(events["alpha"].get(k)), json.dumps(events["beta"].get(k))))
        expect(
            "both canonical events share the same field set (core fields)",
            all(k in events["alpha"] and k in events["beta"] for k in keys[:-1]),
        )
        expect(
            "alpha unit normalised to CELSIUS, beta to FAHRENHEIT",
            events["alpha"].get("unit") == "CELSIUS" and events["beta"].get("unit") == "FAHRENHEIT",
            (events["alpha"].get("unit"), events["beta"].get("unit")),
        )
        expect(
            "alpha value 92.5 / beta value 198.5",
            events["alpha"].get("value") == 92.5 and events["beta"].get("value") == 198.5,
        )


def s09_invalid():
    title(9, "Invalid payloads are rejected with structured errors")
    p = payload("alpha-invalid.json")
    info("payload: " + json.dumps(p))
    st, resp, _, _ = ingest("alpha-ingest", p)
    expect(
        "alpha invalid -> 400 PAYLOAD_VALIDATION_FAILED",
        st == 400 and isinstance(resp, dict) and resp.get("errorCode") == "PAYLOAD_VALIDATION_FAILED",
        (st, resp),
    )
    info(pretty(resp))
    fields = err_fields(resp)
    for f in ("sensorId", "temperature", "unit"):
        expect("error reported for field %s" % f, f in fields, fields)
    st, resp, _, _ = ingest("beta-ingest", payload("beta-invalid.json"))
    expect(
        "beta invalid -> 400 PAYLOAD_VALIDATION_FAILED",
        st == 400 and isinstance(resp, dict) and resp.get("errorCode") == "PAYLOAD_VALIDATION_FAILED",
        (st, resp),
    )
    info("beta error fields: %s" % err_fields(resp))
    st, resp, _, _ = ingest("alpha-ingest", payload("alpha-unknown-device.json"))
    expect(
        "unregistered device MACHINE-999 -> 422 DEVICE_NOT_REGISTERED",
        st == 422 and isinstance(resp, dict) and resp.get("errorCode") == "DEVICE_NOT_REGISTERED",
        (st, resp),
    )
    st, resp, _, _ = ingest("alpha-ingest", "{not json")
    expect("malformed JSON -> 400", st == 400, (st, resp))
    st, resp, _ = http(
        "POST", APP + "/api/v1/ingestion/readings", bearer(token("alpha-ingest")), payload("alpha-valid.json")
    )
    expect(
        "missing Idempotency-Key -> 400 MISSING_IDEMPOTENCY_KEY",
        st == 400 and isinstance(resp, dict) and resp.get("errorCode") == "MISSING_IDEMPOTENCY_KEY",
        (st, resp),
    )
    st, resp, _ = http(
        "POST", APP + "/api/v1/ingestion/readings", {"Idempotency-Key": "x"}, payload("alpha-valid.json")
    )
    expect("no token -> 401", st == 401, st)


def s10_tenant_mismatch():
    title(10, "X-Tenant-ID header that contradicts the token's tenant is rejected")
    st, resp, _, _ = ingest("alpha-ingest", payload("alpha-valid.json"), extra_headers={"X-Tenant-ID": "tenant-beta"})
    expect(
        "alpha token + X-Tenant-ID: tenant-beta -> 403 TENANT_MISMATCH",
        st == 403 and isinstance(resp, dict) and resp.get("errorCode") == "TENANT_MISMATCH",
        (st, resp),
    )
    info(pretty(resp))
    st, resp, _, _ = ingest("alpha-ingest", fresh_reading("alpha", 50.0), extra_headers={"X-Tenant-ID": "tenant-alpha"})
    expect("matching X-Tenant-ID is accepted -> 202", st == 202, (st, resp))


def s11_cross_tenant():
    title(11, "Cross-tenant access is denied")
    alpha_admin = token("alpha-admin")
    for path in ("", "/devices", "/contracts", "/ingestions", "/settings"):
        st, resp, _ = http("GET", APP + "/api/v1/tenants/tenant-beta" + path, bearer(alpha_admin))
        expect("alpha-admin GET /tenants/tenant-beta%s -> 403" % path, st == 403, (st, resp))
    st, resp, _ = http("GET", APP + "/api/v1/admin/tenants", bearer(alpha_admin))
    expect("alpha-admin cannot use platform admin API -> 403", st == 403, st)
    st, resp, _ = http("GET", APP + "/api/v1/tenants/tenant-alpha/ingestions", bearer(token("alpha-ingest")))
    expect("alpha-ingest may read own tenant ingestions -> 200", st == 200, st)
    st, resp, _ = http("GET", APP + "/api/v1/tenants/tenant-beta/ingestions", bearer(token("alpha-ingest")))
    expect("alpha-ingest reading beta ingestions -> 403", st == 403, st)
    st, resp, _ = http(
        "POST",
        APP + "/api/v1/tenants/tenant-alpha/devices",
        bearer(token("alpha-ingest")),
        {"deviceId": "MACHINE-777", "description": "x"},
    )
    expect("ingest role cannot register devices -> 403", st == 403, st)
    beta_ref = load_state().get("ingestion_beta")
    if beta_ref:
        st, resp, _ = http(
            "GET", APP + "/api/v1/tenants/tenant-alpha/ingestions/%s" % beta_ref["ingestionId"], bearer(alpha_admin)
        )
        expect("alpha path + beta ingestion id -> 404 (no existence leak)", st == 404, (st, resp))
    else:
        expect("beta ingestion id known (run scenario 7 first)", False)


def s12_idempotency():
    title(12, "Idempotency: duplicate key and conflicting key")
    key = "demo-%s-idem-%s" % (RUN_ID, uuid.uuid4().hex[:8])
    p = fresh_reading("alpha", 71.25)
    st1, r1, _, _ = ingest("alpha-ingest", p, key=key)
    expect(
        "first request -> 202 ACCEPTED",
        st1 == 202 and isinstance(r1, dict) and r1.get("status") == "ACCEPTED",
        (st1, r1),
    )
    st2, r2, _, h2 = ingest("alpha-ingest", p, key=key)
    expect(
        "same key + same payload -> 200 DUPLICATE",
        st2 == 200 and isinstance(r2, dict) and r2.get("status") == "DUPLICATE",
        (st2, r2),
    )
    if isinstance(r1, dict) and isinstance(r2, dict):
        expect(
            "duplicate returns the original ingestionId",
            r1.get("ingestionId") == r2.get("ingestionId"),
            (r1.get("ingestionId"), r2.get("ingestionId")),
        )
        info("original=%s replay=%s" % (r1.get("ingestionId"), r2.get("ingestionId")))
    p2 = dict(p)
    p2["temperature"] = 99.9
    st3, r3, _, _ = ingest("alpha-ingest", p2, key=key)
    expect(
        "same key + different payload -> 409 IDEMPOTENCY_KEY_CONFLICT",
        st3 == 409 and isinstance(r3, dict) and r3.get("errorCode") == "IDEMPOTENCY_KEY_CONFLICT",
        (st3, r3),
    )
    info(pretty(r3))


def _outbox(status):
    st, body, _ = http("GET", APP + "/api/v1/admin/outbox?status=" + status, bearer(token("platform-admin")))
    return st, body


def s13_kafka_down():
    title(13, "Kafka outage: ingestion keeps accepting, events wait in the outbox")
    rc, out = compose("stop", "kafka")
    expect("docker compose stop kafka", rc == 0, out.strip()[-200:])
    state = load_state()
    ids = []
    for kind, val in (("alpha", 30.5), ("alpha", 31.5), ("beta", 100.0)):
        st, resp, _, _ = ingest("%s-ingest" % kind, fresh_reading(kind, val))
        expect("%s reading accepted while Kafka is down -> 202" % kind, st == 202, (st, resp))
        if st == 202:
            ids.append(resp.get("eventId"))
    state["outage_event_ids"] = ids
    save_state(state)
    info("waiting (up to 45s) until the publisher has tried and failed at least once ...")
    deadline = time.time() + 45
    time.sleep(10)
    while time.time() < deadline:
        _, pend0 = _outbox("PENDING")
        if any(int(pick(r, "attempts") or 0) >= 1 for r in as_list(pend0)):
            break
        time.sleep(3)
    st, stats, _ = http("GET", APP + "/api/v1/admin/outbox/stats", bearer(token("platform-admin")))
    expect("GET /admin/outbox/stats -> 200", st == 200, (st, stats))
    info("outbox stats: " + json.dumps(stats))
    st, pend = _outbox("PENDING")
    rows = as_list(pend)
    expect("GET /admin/outbox?status=PENDING -> 200", st == 200, (st, pend))
    txt = json.dumps(pend)
    expect(
        "all %d outage events are PENDING in the outbox" % len(ids),
        all(i and i in txt for i in ids),
        "pending rows: %d" % len(rows),
    )
    # The publisher stops a batch at the first failure, so only the head-of-line PENDING row(s) are attempted;
    # the rest are released untouched (attempts=0). Assert that failed attempts are committed and visible.
    attempted = [r for r in rows if int(pick(r, "attempts") or 0) >= 1 and pick(r, "lastError", "last_error")]
    expect(
        "failed publish attempts are visible while Kafka is down (a PENDING row has attempts>=1 and lastError)",
        len(attempted) >= 1,
        "%d of %d pending rows" % (len(attempted), len(rows)),
    )
    for r in rows[:8]:
        info(
            "pending: eventId=%s topic=%s attempts=%s lastError=%s"
            % (
                pick(r, "eventId", "event_id"),
                pick(r, "topic"),
                pick(r, "attempts"),
                str(pick(r, "lastError", "last_error"))[:70],
            )
        )


def s14_kafka_up():
    title(14, "Kafka recovers: outbox drains, events get published")
    rc, out = compose("start", "kafka")
    expect("docker compose start kafka", rc == 0, out.strip()[-200:])
    ids = load_state().get("outage_event_ids") or []
    deadline = time.time() + 150
    drained = False
    while time.time() < deadline:
        st, pend = _outbox("PENDING")
        txt = json.dumps(pend)
        if st == 200 and not any(i and i in txt for i in ids):
            drained = True
            break
        info("still pending: %d rows ..." % len(as_list(pend)))
        time.sleep(5)
    expect("outage events no longer PENDING (published)", drained)
    if ids:
        st, pub = _outbox("PUBLISHED")
        txt = json.dumps(pub)
        expect("outage events are PUBLISHED", all(i and i in txt for i in ids))
    st, stats, _ = http("GET", APP + "/api/v1/admin/outbox/stats", bearer(token("platform-admin")))
    info("outbox stats: " + json.dumps(stats))


def s15_consumer_logs():
    title(15, "Downstream consumer processed the events")
    ids = load_state().get("outage_event_ids") or []
    out = ""
    deadline = time.time() + 90
    while time.time() < deadline:
        rc, out = compose("logs", "--no-log-prefix", "--tail", "200", "consumer")
        if all(i in out for i in ids):
            break
        time.sleep(5)
    lines = [
        ln for ln in out.splitlines() if "event processed" in ln or "duplicate" in ln or "WARN" in ln or "ERROR" in ln
    ]
    for ln in lines[-12:]:
        info(ln[:230])
    for i in ids:
        expect("consumer logged event %s" % i, i in out)
    if not ids:
        expect("outage event ids known (run scenario 13 first)", False)


def prom_query(q):
    st, body, _ = http("GET", PROM + "/api/v1/query?" + urllib.parse.urlencode({"query": q}))
    if st == 200 and isinstance(body, dict):
        return body.get("data", {}).get("result", [])
    return None


def s16_observability():
    title(16, "Observability: Prometheus and Grafana")
    info("Grafana   : %s  (dashboard: %s/d/ingestion-platform)" % (GRAFANA, GRAFANA))
    info("Prometheus: %s  (targets: %s/targets)" % (PROM, PROM))
    info("Swagger UI: %s/swagger-ui.html" % APP)
    time.sleep(6)  # allow one more scrape
    for q in (
        "sum by (outcome) (ingestion_requests_total)",
        "sum by (result) (outbox_publish_total)",
        "outbox_pending_events",
        "sum by (reason) (ingestion_rejections_total)",
    ):
        res = prom_query(q)
        shown = [
            "%s=%s" % (",".join("%s" % v for v in r["metric"].values()) or "-", r["value"][1]) for r in (res or [])
        ]
        info("%-52s %s" % (q, shown))
        if q.startswith(("sum by (outcome)", "sum by (result)")):
            expect("prometheus has data for %s" % q.split("(")[-1].rstrip(")").split()[-1], bool(res), res)


SCENARIOS = [
    ("health", s01_health),
    ("tokens", s02_tokens),
    ("register", s03_register),
    ("upload", s04_upload),
    ("activate", s05_activate),
    ("ingest-alpha", s06_ingest_alpha),
    ("ingest-beta", s07_ingest_beta),
    ("compare", s08_compare),
    ("invalid", s09_invalid),
    ("tenant-mismatch", s10_tenant_mismatch),
    ("cross-tenant", s11_cross_tenant),
    ("idempotency", s12_idempotency),
    ("kafka-down", s13_kafka_down),
    ("kafka-up", s14_kafka_up),
    ("consumer-logs", s15_consumer_logs),
    ("observability", s16_observability),
]


def resolve(arg):
    if arg.isdigit() and 1 <= int(arg) <= len(SCENARIOS):
        return SCENARIOS[int(arg) - 1]
    for name, fn in SCENARIOS:
        if name == arg:
            return name, fn
    raise SystemExit("unknown scenario %r (try: list)" % arg)


def main(argv):
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__)
        return 0
    if argv[0] == "list":
        for i, (name, fn) in enumerate(SCENARIOS, 1):
            print("%2d  %-16s %s" % (i, name, (fn.__doc__ or "").strip()))
        return 0
    selected = SCENARIOS if argv[0] == "all" else [resolve(a) for a in argv]
    print("run id %s | app %s | keycloak %s" % (RUN_ID, APP, KC))
    for name, fn in selected:
        try:
            fn()
        except Exception as e:  # noqa: BLE001
            expect("scenario %s completed without error" % name, False, "%s: %s" % (type(e).__name__, e))
    print("\n" + c("1", "SUMMARY: %d passed, %d failed" % (Results.passed, Results.failed)))
    for f in Results.failures:
        print("  - " + f)
    return 1 if Results.failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
