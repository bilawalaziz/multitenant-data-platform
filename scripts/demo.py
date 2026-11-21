#!/usr/bin/env python3
"""End-to-end demo of the multi-tenant sensor ingestion platform (stdlib only, cross-platform).

Usage:
    python scripts/demo.py all            # run all scenarios in order
    python scripts/demo.py 6 7 8          # run selected scenarios by number
    python scripts/demo.py ingest-alpha   # or by name (see SCENARIOS below)
    python scripts/demo.py list
    python scripts/demo.py all --target k8s   # against the k3d cluster of scripts/aws-local.sh (default: compose)

Configuration (environment variables or a .env file in the project root):
    APP_URL (http://localhost:8080)  MANAGEMENT_URL (http://localhost:8081)  KEYCLOAK_URL (http://localhost:8180)
    PROMETHEUS_URL (http://localhost:9090)  GRAFANA_URL (http://localhost:3000)
    TEMPO_URL (http://localhost:3200)
    DEMO_USER_PASSWORD  ALPHA_INGEST_CLIENT_SECRET  BETA_INGEST_CLIENT_SECRET
    DB_APP_PASSWORD (scenario rls: psql as the application role through docker compose)
    DEMO_FORCE_UPLOAD=1 uploads a new contract version even if an active one exists.

With --target k8s (or DEMO_TARGET=k8s) the URLs are the ingress hosts (override with the same variables in the
environment, not .env; LOCAL_HTTP_PORT if the ingress is not on port 80), the credentials are read from the cluster
Secrets, and logs, scaling, psql and the Kafka outage go through kubectl in the helper container of aws-local.sh.

Every ingestion uses a fresh uuid-based Idempotency-Key, so the demo is re-runnable. Device secrets are kept in
memory and in the git-ignored .demo-state.json, and are never printed. Scenario 20 scales the consumer to two replicas
and back. Exit code is non-zero if any expectation failed.
"""

import json
import os
import random
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def _target():
    argv = sys.argv[1:]
    for i, a in enumerate(argv):
        if a == "--target" and i + 1 < len(argv):
            return argv[i + 1]
        if a.startswith("--target="):
            return a.split("=", 1)[1]
    return os.environ.get("DEMO_TARGET", "compose")


TARGET = _target()
if TARGET not in ("compose", "k8s"):
    raise SystemExit("unknown --target %r (compose or k8s)" % TARGET)
K8S = TARGET == "k8s"
# The cluster has its own device credentials; keeping them apart leaves the compose demo's state alone.
STATE_FILE = (
    os.path.join(ROOT, ".scratch", "aws-local", "demo-state.json") if K8S else os.path.join(ROOT, ".demo-state.json")
)
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


# Secrets of the cluster target: (Secret, key) per setting, read once with kubectl and never printed.
K8S_NS = "sensor-platform"
K8S_SECRET_KEYS = {
    "DEMO_USER_PASSWORD": ("keycloak-realm-secrets", "DEMO_USER_PASSWORD"),
    "ALPHA_INGEST_CLIENT_SECRET": ("keycloak-realm-secrets", "ALPHA_INGEST_CLIENT_SECRET"),
    "BETA_INGEST_CLIENT_SECRET": ("keycloak-realm-secrets", "BETA_INGEST_CLIENT_SECRET"),
    "DB_APP_USER": ("sensor-platform-app-secrets", "DB_USER"),
    "DB_APP_PASSWORD": ("sensor-platform-app-secrets", "DB_PASSWORD"),
}
_K8S_SECRET_CACHE = {}
KUBECTL_CONTAINER = os.environ.get("KUBECTL_CONTAINER", "sensor-local-kubectl")


def k8s_secret(secret, key):
    if secret not in _K8S_SECRET_CACHE:
        rc, out = kubectl("get", "secret", secret, "-o", "json")
        try:
            data = json.loads(out)["data"] if rc == 0 else {}
        except (ValueError, KeyError):
            data = {}
        import base64

        _K8S_SECRET_CACHE[secret] = {k: base64.b64decode(v).decode("utf-8") for k, v in data.items()}
    return _K8S_SECRET_CACHE[secret].get(key)


def cfg(name, default=None):
    if K8S:
        # .env belongs to the compose stack; only the environment may override a cluster setting.
        if name in K8S_SECRET_KEYS:
            return os.environ.get(name) or k8s_secret(*K8S_SECRET_KEYS[name]) or default
        return os.environ.get(name) or default
    return os.environ.get(name) or _ENV.get(name) or default


if K8S:
    _ingress_port = os.environ.get("LOCAL_HTTP_PORT", "80")
    _suffix = "" if _ingress_port == "80" else ":" + _ingress_port
    APP = cfg("APP_URL", "http://sensor.localtest.me" + _suffix).rstrip("/")
    KC = cfg("KEYCLOAK_URL", "http://keycloak.localtest.me" + _suffix).rstrip("/")
    GRAFANA = cfg("GRAFANA_URL", "http://grafana.localtest.me" + _suffix).rstrip("/")
    # Management, Prometheus and Tempo have no ingress: they are read through the Kubernetes API service proxy.
    MGMT = cfg("MANAGEMENT_URL", "kube-proxy://sensor-platform/sensor-platform-app:8081").rstrip("/")
    PROM = cfg("PROMETHEUS_URL", "kube-proxy://observability/prometheus-server:80").rstrip("/")
    TEMPO = cfg("TEMPO_URL", "kube-proxy://observability/tempo:3200").rstrip("/")
else:
    APP = cfg("APP_URL", "http://localhost:%s" % cfg("APP_HOST_PORT", "8080")).rstrip("/")
    MGMT = cfg("MANAGEMENT_URL", "http://localhost:8081").rstrip("/")
    KC = cfg("KEYCLOAK_URL", "http://localhost:%s" % cfg("KEYCLOAK_HOST_PORT", "8180")).rstrip("/")
    PROM = cfg("PROMETHEUS_URL", "http://localhost:%s" % cfg("PROMETHEUS_HOST_PORT", "9090")).rstrip("/")
    GRAFANA = cfg("GRAFANA_URL", "http://localhost:%s" % cfg("GRAFANA_HOST_PORT", "3000")).rstrip("/")
    TEMPO = cfg("TEMPO_URL", "http://localhost:%s" % cfg("TEMPO_HOST_PORT", "3200")).rstrip("/")
TOKEN_URL = KC + "/realms/sensor-platform/protocol/openid-connect/token"
TENANTS = {"alpha": "tenant-alpha", "beta": "tenant-beta"}
DEVICES = {"alpha": "MACHINE-001", "beta": "MACHINE-002"}
# Second alpha device, used as the "other device" in the mismatch and rate-limit scenarios, and a device that only
# exists to be flooded so the limit does not eat the budget of the devices the other scenarios use.
SPARE_DEVICE = "MACHINE-003"
BURST_DEVICE = "MACHINE-004"
# The device identities that ingest with their own Keycloak client (see device_credentials).
DEVICE_IDENT = {k: "device:%s:%s" % (k, d) for k, d in DEVICES.items()}


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
    if url.startswith("kube-proxy://"):
        return kube_proxy_get(url, timeout)
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


def kube_proxy_get(url, timeout):
    """GET through the API server's service proxy: kube-proxy://<namespace>/<service>:<port>/<path>."""
    ns, _, rest = url[len("kube-proxy://") :].partition("/")
    svc, _, path = rest.partition("/")
    rc, out = kubectl("get", "--raw", "/api/v1/namespaces/%s/services/%s/proxy/%s" % (ns, svc, path), timeout=timeout)
    if rc != 0:
        m = re.search(r"\((\w+)\)", out)
        code = {"NotFound": 404, "Forbidden": 403, "ServiceUnavailable": 503}.get(m.group(1) if m else "", 500)
        return code, out, {}
    try:
        return 200, json.loads(out) if out.strip() else None, {}
    except ValueError:
        return 200, out, {}


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
_TOKENS = {}  # identity -> (access token, expiry in epoch seconds)
_DEVICE_CREDS = {}  # "tenant/device" -> {"clientId", "clientSecret"}; never printed


def fetch_token(form):
    status, body, _ = http(
        "POST", TOKEN_URL, {"Content-Type": "application/x-www-form-urlencoded"}, urllib.parse.urlencode(form)
    )
    return status, body


def remember_device_credentials(slot, body):
    cred = {"clientId": body["clientId"], "clientSecret": body["clientSecret"]}
    _DEVICE_CREDS[slot] = cred
    state = load_state()
    state.setdefault("device_credentials", {})[slot] = cred
    save_state(state)
    return cred


def device_credentials(key, device_id, fresh=False):
    """Credentials of a registered device: from memory or the state file, else a new secret from the API.

    The secret of an existing device cannot be read back, only replaced, so a missing or rejected one is rotated.
    """
    slot = "%s/%s" % (TENANTS[key], device_id)
    if not fresh:
        cred = _DEVICE_CREDS.get(slot) or load_state().get("device_credentials", {}).get(slot)
        if cred:
            _DEVICE_CREDS[slot] = cred
            return cred
    st, body, _ = http(
        "POST",
        APP + "/api/v1/tenants/%s/devices/%s/credentials" % (TENANTS[key], device_id),
        bearer(token("platform-admin")),
    )
    if st != 200 or not isinstance(body, dict) or not body.get("clientSecret"):
        info("credentials for %s not issued: %s" % (slot, st))
        return None
    _TOKENS.pop("device:%s:%s" % (key, device_id), None)
    return remember_device_credentials(slot, body)


def register_device(key, device_id, description="Demo device"):
    """Registers a device and returns (status, credentials). A 409 means it exists, so its secret is replaced."""
    st, body, _ = http(
        "POST",
        APP + "/api/v1/tenants/%s/devices" % TENANTS[key],
        bearer(token("platform-admin")),
        {"deviceId": device_id, "description": description},
    )
    if st == 201 and isinstance(body, dict) and body.get("clientSecret"):
        return st, remember_device_credentials("%s/%s" % (TENANTS[key], device_id), body)
    if st == 409:
        return st, device_credentials(key, device_id, fresh=True)
    return st, None


def device_form(cred):
    return {"grant_type": "client_credentials", "client_id": cred["clientId"], "client_secret": cred["clientSecret"]}


def token(identity):
    cached = _TOKENS.get(identity)
    if cached and cached[1] > time.time() + 30:
        return cached[0]
    if identity in ("platform-admin", "alpha-admin", "beta-admin"):
        form = {
            "grant_type": "password",
            "client_id": "platform-cli",
            "username": identity,
            "password": cfg("DEMO_USER_PASSWORD", ""),
        }
        status, body = fetch_token(form)
    elif identity in ("alpha-ingest", "beta-ingest"):
        t = identity.split("-")[0]
        form = {
            "grant_type": "client_credentials",
            "client_id": "tenant-%s-ingest" % t,
            "client_secret": cfg("%s_INGEST_CLIENT_SECRET" % t.upper(), ""),
        }
        status, body = fetch_token(form)
    elif identity.startswith("device:"):
        _, key, device_id = identity.split(":", 2)
        status, body = 0, None
        # A remembered secret may predate a rotation, so one rejection is followed by a fresh secret.
        for fresh in (False, True):
            cred = device_credentials(key, device_id, fresh=fresh)
            if cred:
                status, body = fetch_token(device_form(cred))
                if status == 200:
                    break
    else:
        raise ValueError(identity)
    if status != 200 or not isinstance(body, dict) or "access_token" not in body:
        # The body of a failed token request holds an error, not a secret.
        raise RuntimeError("token request for %s failed: %s %s" % (identity, status, body))
    _TOKENS[identity] = (body["access_token"], time.time() + int(body.get("expires_in", 60)))
    return body["access_token"]


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


def compose(*args, env=None):
    cmd = ["docker", "compose"] + list(args)
    try:
        p = subprocess.run(
            cmd,
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
            env=dict(os.environ, **env) if env else None,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, str(e)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


# ----------------------------------------------------------------------------- runtime adapter (compose | k8s)
CONSUMER_SELECTOR = "app.kubernetes.io/component=consumer"


def kubectl(*args, namespace=K8S_NS, stdin=None, timeout=120):
    """kubectl in the long-running helper container of aws-local.sh (a docker run per call would be too slow)."""
    cmd = ["docker", "exec", "-i", KUBECTL_CONTAINER, "kubectl"]
    if namespace:
        cmd += ["-n", namespace]
    try:
        p = subprocess.run(
            cmd + list(args),
            input=stdin,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, str(e)
    return p.returncode, (p.stdout or "") if p.returncode == 0 else (p.stdout or "") + (p.stderr or "")


def rt_running_services():
    """Names of the platform services that are up (compose service names)."""
    if not K8S:
        rc, out = compose("ps", "--status", "running", "--services")
        return (set(out.split()) if rc == 0 else set()), out
    want = {
        "postgres": "statefulset/postgres",
        "kafka": "statefulset/kafka",
        "keycloak": "deployment/keycloak",
        "app": "deployment/sensor-platform-app",
        "consumer": "deployment/sensor-platform-consumer",
    }
    up = set()
    for svc, obj in want.items():
        rc, out = kubectl("get", obj, "-o", "jsonpath={.status.readyReplicas}/{.spec.replicas}")
        ready, _, desired = out.partition("/")
        if rc == 0 and ready.isdigit() and ready == desired and int(desired) > 0:
            up.add(svc)
    return up, "ready: %s" % sorted(up)


def rt_kafka(action):
    """Stops or starts the broker; returns (rc, output) once it is gone or Ready again."""
    if not K8S:
        return compose(action, "kafka")
    rc, out = kubectl("scale", "statefulset/kafka", "--replicas=%d" % (0 if action == "stop" else 1))
    if rc != 0:
        return rc, out
    if action == "stop":
        return kubectl("wait", "--for=delete", "pod/kafka-0", "--timeout=120s", timeout=150)
    deadline = time.time() + 300
    while time.time() < deadline:
        rc, out = kubectl("wait", "--for=condition=Ready", "pod/kafka-0", "--timeout=30s", timeout=60)
        if rc == 0:
            return rc, out
        time.sleep(3)
    return 1, out


def rt_consumer_logs(since):
    """Consumer logs since a duration ('1h') or an RFC 3339 time, one 'replica | line' per line on both targets."""
    if not K8S:
        rc, out = compose("logs", "--since", since, "consumer")
        return out
    flag = "--since-time=" + since if "T" in since else "--since=" + since
    # --tail=-1: with a selector kubectl defaults to the last 10 lines per pod.
    rc, out = kubectl("logs", "-l", CONSUMER_SELECTOR, "--prefix", "--tail=-1", flag, "--max-log-requests=10")
    lines = []
    for ln in out.splitlines():
        m = re.match(r"\[pod/([^/\]]+)/[^\]]+\] ?(.*)", ln)
        lines.append("%s | %s" % (m.group(1), m.group(2)) if m else ln)
    return "\n".join(lines)


def rt_scale_consumer(n):
    if not K8S:
        return compose("up", "-d", "--scale", "consumer=%d" % n, "--no-deps", "--no-recreate", "consumer")
    rc, out = kubectl("scale", "deployment/sensor-platform-consumer", "--replicas=%d" % n)
    if rc != 0:
        return rc, out
    return kubectl("rollout", "status", "deployment/sensor-platform-consumer", "--timeout=180s", timeout=200)


def rt_psql(user, password, db, statements):
    """Runs the statements as the given role; returns (rc, output). The password travels on stdin, not in argv."""
    args = ["-v", "ON_ERROR_STOP=1", "-U", user, "-d", db, "-tA"]
    for stmt in statements:
        args += ["-c", stmt]
    if not K8S:
        return compose("exec", "-T", "-e", "PGPASSWORD", "postgres", "psql", *args, env={"PGPASSWORD": password})
    # -h 127.0.0.1 forces password authentication; the socket in the image is trusted.
    script = 'read -r PGPASSWORD; export PGPASSWORD; exec psql -h 127.0.0.1 "$@"'
    return kubectl("exec", "-i", "postgres-0", "--", "sh", "-c", script, "sh", *args, stdin=password + "\n")


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
    running, out = rt_running_services()
    for svc in ("postgres", "kafka", "keycloak", "app", "consumer"):
        expect(
            "%s service %s running" % ("kubernetes" if K8S else "docker compose", svc),
            svc in running,
            out.strip()[:120],
        )


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
    title(3, "Register tenants and devices, each device with its own credential (409 = already exists)")
    adm = token("platform-admin")
    for key, tid in TENANTS.items():
        st, body, _ = http(
            "POST",
            APP + "/api/v1/admin/tenants",
            bearer(adm),
            {"tenantId": tid, "displayName": "Tenant " + key.capitalize()},
        )
        expect("register %s (201 or already exists 409)" % tid, st in (201, 409), (st, body))
        # The secret is returned only at registration, and a rerun gets 409, so the secret is replaced then.
        st, cred = register_device(key, DEVICES[key], "Demo machine of " + tid)
        expect("register device %s for %s (201, or 409 and a new secret)" % (DEVICES[key], tid), bool(cred), st)
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
    # The device's own Keycloak client, not the tenant-wide one: the token names tenant and device.
    claims = decode_jwt(token(DEVICE_IDENT[kind]))
    expect(
        "%s device token carries tenant_id and device_id" % kind,
        claims.get("tenant_id") == tid and claims.get("device_id") == DEVICES[kind],
        (claims.get("tenant_id"), claims.get("device_id")),
    )
    st, resp, key, _ = ingest(DEVICE_IDENT[kind], p)
    ok = expect(
        "%s ingest with the device credential -> 202 ACCEPTED" % kind,
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
    rc, out = rt_kafka("stop")
    expect("%s stop kafka" % ("kubectl scale" if K8S else "docker compose"), rc == 0, out.strip()[-200:])
    state = load_state()
    ids = []
    for kind, val in (("alpha", 30.5), ("alpha", 31.5), ("beta", 100.0)):
        st, resp, _, _ = ingest(DEVICE_IDENT[kind], fresh_reading(kind, val))
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
    rc, out = rt_kafka("start")
    expect("%s start kafka" % ("kubectl scale" if K8S else "docker compose"), rc == 0, out.strip()[-200:])
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


def consumer_plain_logs(since):
    if not K8S:
        return compose("logs", "--no-log-prefix", "--since", since, "consumer")[1]
    return "\n".join(ln.partition(" | ")[2] for ln in rt_consumer_logs(since).splitlines())


def s15_consumer_logs():
    title(15, "Downstream consumer processed the events")
    ids = load_state().get("outage_event_ids") or []
    out = ""
    deadline = time.time() + 90
    while time.time() < deadline:
        # A time window rather than --tail: tail counts per replica, and a replica may hold the events of the others.
        out = consumer_plain_logs("1h")
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
    st, body, _ = http("GET", PROM + "/api/v1/targets?state=active")
    targets = body.get("data", {}).get("activeTargets", []) if st == 200 and isinstance(body, dict) else []
    expect(
        "prometheus scrapes the application target and it is up",
        any(t.get("labels", {}).get("job") == "sensor-ingestion-platform" and t.get("health") == "up" for t in targets),
        [(t.get("labels", {}).get("job"), t.get("health")) for t in targets],
    )
    st, body, _ = http("GET", PROM + "/api/v1/rules?type=alert")
    rules = [
        r
        for g in (body.get("data", {}).get("groups", []) if st == 200 and isinstance(body, dict) else [])
        for r in g.get("rules", [])
    ]
    info("alert rules loaded: %s" % ", ".join(sorted(r.get("name", "?") for r in rules)))
    # Names are checked rather than a count, so adding a rule does not break the demo.
    expect(
        "alert rules loaded without errors, including RateLimiterDegraded",
        bool(rules)
        and all(r.get("health") != "err" for r in rules)
        and any(r.get("name") == "RateLimiterDegraded" for r in rules),
        [(r.get("name"), r.get("health")) for r in rules],
    )
    for q in (
        "sum by (outcome) (ingestion_requests_total)",
        "sum by (result) (outbox_publish_total)",
        "outbox_pending_events",
        "sum by (reason) (ingestion_rejections_total)",
        "sum(ratelimit_errors_total)",
    ):
        res = prom_query(q)
        shown = [
            "%s=%s" % (",".join("%s" % v for v in r["metric"].values()) or "-", r["value"][1]) for r in (res or [])
        ]
        info("%-52s %s" % (q, shown))
        if q.startswith(("sum by (outcome)", "sum by (result)")):
            expect("prometheus has data for %s" % q.split("(")[-1].rstrip(")").split()[-1], bool(res), res)


def reading_for(kind, device_id, value):
    """A fresh reading naming a specific device of the tenant."""
    p = fresh_reading(kind, value)
    p["deviceId" if kind == "alpha" else "device_code"] = device_id
    return p


def header(hdrs, name):
    return next((v for k, v in (hdrs or {}).items() if k.lower() == name.lower()), None)


def s17_device_credentials():
    title(17, "Per-device credentials: a device token cannot submit another device's reading")
    own, other = DEVICES["alpha"], SPARE_DEVICE
    st, cred = register_device("alpha", other, "Second alpha device")
    expect("second alpha device %s registered with its own credential" % other, bool(cred), st)
    if not cred:
        return
    own_ident, other_ident = DEVICE_IDENT["alpha"], "device:alpha:%s" % other

    st, resp, _, _ = ingest(own_ident, reading_for("alpha", other, 61.5))
    expect(
        "%s token sending a %s reading -> 403 DEVICE_MISMATCH" % (own, other),
        st == 403 and isinstance(resp, dict) and resp.get("errorCode") == "DEVICE_MISMATCH",
        (st, resp),
    )
    info(pretty(resp))
    st, resp, _, _ = ingest(own_ident, reading_for("alpha", own, 61.5))
    expect("%s token sending its own reading -> 202" % own, st == 202, (st, resp))
    st, resp, _, _ = ingest(other_ident, reading_for("alpha", other, 62.5))
    expect("%s token sending its own reading -> 202" % other, st == 202, (st, resp))
    st, resp, _, _ = ingest("alpha-ingest", reading_for("alpha", other, 63.5))
    expect("tenant-wide token still accepted outside production -> 202", st == 202, (st, resp))

    # Deactivation cannot be undone, so each run uses a device of its own and leaves it behind inactive.
    spare, spare_cred = None, None
    for _ in range(8):
        candidate = "MACHINE-%03d" % random.randint(500, 899)
        st, spare_cred = register_device("alpha", candidate, "Demo device that is deactivated")
        if st == 201 and spare_cred:
            spare = candidate
            break
    if not expect("a device to deactivate registered", spare is not None):
        return
    ident = "device:alpha:%s" % spare
    st, resp, _, _ = ingest(ident, reading_for("alpha", spare, 20.5))
    expect("%s accepted while active -> 202" % spare, st == 202, (st, resp))
    st, resp, _ = http(
        "POST",
        APP + "/api/v1/tenants/tenant-alpha/devices/%s/deactivate" % spare,
        bearer(token("platform-admin")),
    )
    expect("deactivate %s -> 200 INACTIVE" % spare, st == 200 and (resp or {}).get("status") == "INACTIVE", (st, resp))
    # The token issued before deactivation is still valid; the device registry is what rejects it.
    st, resp, _, _ = ingest(ident, reading_for("alpha", spare, 21.5))
    expect(
        "deactivated device with its existing token -> 422 DEVICE_NOT_REGISTERED",
        st == 422 and isinstance(resp, dict) and resp.get("errorCode") == "DEVICE_NOT_REGISTERED",
        (st, resp),
    )
    st, _ = fetch_token(device_form(spare_cred))
    expect("deactivated device's client is refused a new token", st != 200, st)


def s18_rate_limit():
    title(18, "Rate limiting: one device is throttled, its neighbour is not")
    st, burst_cred = register_device("alpha", BURST_DEVICE, "Device that is flooded")
    st2, _ = register_device("alpha", SPARE_DEVICE, "Second alpha device")
    if not expect("burst device and neighbour registered", bool(burst_cred) and st2 in (201, 409), (st, st2)):
        return
    burst_ident, calm_ident = "device:alpha:%s" % BURST_DEVICE, "device:alpha:%s" % SPARE_DEVICE
    token(burst_ident)  # fetched up front so the burst measures the limiter, not Keycloak
    token(calm_ident)

    # Concurrent, because a sequential loop may refill (10/s) about as fast as it spends and never hit the limit.
    def send(i):
        return ingest(burst_ident, reading_for("alpha", BURST_DEVICE, 40.0 + i % 10))

    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(send, range(60)))
    codes = [r[0] for r in results]
    info(
        "60 requests for one device: %d accepted, %d limited, other statuses %s"
        % (codes.count(202), codes.count(429), sorted(set(codes) - {202, 429}))
    )
    limited = [r for r in results if r[0] == 429]
    expect("requests within the burst capacity were accepted -> 202", codes.count(202) >= 1, codes)
    expect("requests beyond the device limit -> 429", len(limited) >= 1, sorted(set(codes)))
    if limited:
        resp, hdrs = limited[0][1], limited[0][3]
        expect(
            "429 carries errorCode RATE_LIMITED",
            isinstance(resp, dict) and resp.get("errorCode") == "RATE_LIMITED",
            resp,
        )
        retry = header(hdrs, "Retry-After")
        expect("429 carries a Retry-After of at least 1 second", str(retry).isdigit() and int(retry) >= 1, retry)
        info("Retry-After: %s" % retry)
    expect("only 202 and 429 during the burst (tenant limit not reached)", set(codes) <= {202, 429}, sorted(set(codes)))
    st, resp, _, _ = ingest(calm_ident, reading_for("alpha", SPARE_DEVICE, 55.5))
    expect("another device of the same tenant is still accepted -> 202", st == 202, (st, resp))
    # The device bucket refills 10/s; waiting lets it recover before a rerun or the next scenario uses it.
    time.sleep(2.5)


def tempo_spans(doc):
    """(service, span name) pairs of a Tempo trace, in either OTLP JSON layout (batches, or resourceSpans)."""
    if not isinstance(doc, dict):
        return []
    batches = doc.get("batches") or doc.get("resourceSpans") or (doc.get("trace") or {}).get("resourceSpans") or []
    out = []
    for b in batches:
        attrs = (b.get("resource") or {}).get("attributes") or []
        service = next((a["value"].get("stringValue") for a in attrs if a.get("key") == "service.name"), None)
        for scope in b.get("scopeSpans") or b.get("instrumentationLibrarySpans") or []:
            out.extend((service, sp.get("name")) for sp in scope.get("spans") or [])
    return out


def s19_tracing():
    title(19, "Tracing: one trace from the HTTP request through the outbox to the consumer")
    trace_id, parent_id = uuid.uuid4().hex, uuid.uuid4().hex[:16]
    st, resp, _, _ = ingest(
        DEVICE_IDENT["alpha"],
        fresh_reading("alpha", 77.5),
        extra_headers={"traceparent": "00-%s-%s-01" % (trace_id, parent_id)},
    )
    if not expect("reading with a traceparent accepted -> 202", st == 202, (st, resp)):
        return
    event_id = resp.get("eventId")
    info("trace id %s, event id %s" % (trace_id, event_id))
    wanted = {
        "application HTTP span": lambda s, n: s == "sensor-ingestion-platform"
        and (n or "").lower().startswith("http "),
        "outbox.publish span": lambda s, n: n == "outbox.publish",
        "consumer span": lambda s, n: s == "sensor-consumer" and n == "sensor-readings process",
    }
    spans, missing = [], list(wanted)
    deadline = time.time() + 40
    while time.time() < deadline:
        st, doc, _ = http("GET", "%s/api/traces/%s" % (TEMPO, trace_id), {"Accept": "application/json"})
        if st == 200:
            spans = tempo_spans(doc)
            missing = [k for k, match in wanted.items() if not any(match(s, n) for s, n in spans)]
            if not missing:
                break
        time.sleep(2)
    info("spans in the trace: %s" % sorted(set("%s / %s" % sp for sp in spans)))
    for name in wanted:
        expect("trace in Tempo contains the %s" % name, name not in missing, "missing: %s" % missing)
    line = None
    deadline = time.time() + 20
    while line is None:
        out = consumer_plain_logs("10m")
        line = next((ln for ln in out.splitlines() if trace_id in ln and event_id in ln), None)
        if line is not None or time.time() > deadline:
            break
        time.sleep(2)
    expect("consumer log line for the event carries this trace_id", line is not None and '"trace_id"' in line)
    if line:
        info(line[:230])


def running_consumers():
    if K8S:
        rc, out = kubectl(
            "get",
            "pods",
            "-l",
            CONSUMER_SELECTOR,
            "-o",
            r'jsonpath={range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[0].ready}{" "}'
            r'{.metadata.deletionTimestamp}{"\n"}{end}',
        )
        rows = [ln.split() for ln in out.splitlines()] if rc == 0 else []
        return [r[0] for r in rows if len(r) == 2 and r[1] == "true"]
    rc, out = compose("ps", "--status", "running", "-q", "consumer")
    return [x for x in out.split() if rc == 0 and re.fullmatch(r"[0-9a-f]{12,64}", x)]


def wait_for_consumers(n, seconds=90):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if len(running_consumers()) == n:
            return True
        time.sleep(3)
    return False


def consumer_records(since):
    """Parsed JSON log records of every consumer replica since the timestamp, as (replica, record) pairs."""
    out = rt_consumer_logs(since)
    recs = []
    for ln in out.splitlines():
        prefix, sep, rest = ln.partition("|")
        brace = rest.find("{")
        if not sep or brace < 0:
            continue
        try:
            recs.append((prefix.strip(), json.loads(rest[brace:])))
        except ValueError:
            continue
    return recs


def consumer_tally(since, ids):
    """event_id -> [replica, ...] for 'event processed' and for 'duplicate event skipped' log lines."""
    processed, skipped = {}, {}
    for replica, rec in consumer_records(since):
        target = {"event processed": processed, "duplicate event skipped": skipped}.get(rec.get("msg"))
        if target is not None and rec.get("event_id") in ids:
            target.setdefault(rec["event_id"], []).append(replica)
    return processed, skipped


def replicas_with_assignment(since):
    """Replicas whose latest 'partitions assigned' / 'partitions revoked' line leaves a non-empty assignment."""
    latest = {}
    for replica, rec in consumer_records(since):
        if rec.get("msg") in ("partitions assigned", "partitions revoked"):
            latest[replica] = rec.get("msg") == "partitions assigned" and bool(rec.get("partitions"))
    return {r for r, owns in latest.items() if owns}


def s20_shared_dedup():
    title(20, "Shared deduplication: two consumer replicas split the work and process each event exactly once")
    # Generous margin: the host and the Docker VM clocks need not agree to the second.
    since = datetime.fromtimestamp(time.time() - 60, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    baseline = len(running_consumers()) if K8S else 1
    try:
        # More devices per tenant spread the message keys over the topic's partitions, so both replicas get work.
        extra = ["MACHINE-%03d" % n for n in range(11, 14)]
        for key in TENANTS:
            for device in extra:
                st, cred = register_device(key, device, "Demo device for the shared-consumer scenario")
                expect("%s device %s registered" % (key, device), bool(cred), st)
        if K8S and baseline > 1:
            # The cluster already runs the replicas, so no rebalance would happen: start from one, as compose does.
            rc, out = rt_scale_consumer(1)
            expect("consumer scaled to 1 replica first", rc == 0 and wait_for_consumers(1, 120), out.strip()[-200:])
            since = datetime.fromtimestamp(time.time() - 5, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        # --no-deps and --no-recreate leave the rest of the stack, and the running replica, untouched.
        rc, out = rt_scale_consumer(2)
        expect("%s consumer=2" % ("kubectl scale" if K8S else "docker compose up --scale"), rc == 0, out.strip()[-200:])
        expect("two consumer replicas running", wait_for_consumers(2), len(running_consumers()))
        deadline = time.time() + 90
        owners = replicas_with_assignment(since)
        while time.time() < deadline and len(owners) < 2:
            time.sleep(3)
            owners = replicas_with_assignment(since)
        if not expect("both replicas logged 'partitions assigned' with partitions", len(owners) == 2, sorted(owners)):
            return
        senders = [(kind, device) for device in range(4) for kind in TENANTS]
        senders = [(kind, ([DEVICES[kind]] + extra)[n]) for kind, n in senders]
        ids = []
        for i in range(24):
            kind, device = senders[i % len(senders)]
            st, resp, _, _ = ingest("device:%s:%s" % (kind, device), reading_for(kind, device, 80.0 + i))
            if st == 202:
                ids.append(resp.get("eventId"))
        expect("24 readings with distinct event ids accepted", len(set(ids)) == 24, len(ids))
        deadline = time.time() + 90
        processed, skipped = consumer_tally(since, ids)
        while time.time() < deadline and len(processed) < len(ids):
            time.sleep(3)
            processed, skipped = consumer_tally(since, ids)
        # A second 'processed' for an event would be a dedup failure; give a late one a moment to show.
        time.sleep(3)
        processed, skipped = consumer_tally(since, ids)
        expect("every event was processed", len(processed) == len(ids), "%d of %d" % (len(processed), len(ids)))
        twice = {e: r for e, r in processed.items() if len(r) != 1}
        expect("exactly one 'event processed' per event id across replicas", not twice, twice)
        working = {r for reps in processed.values() for r in reps}
        info("events processed per replica: %s" % {r: sum(r in v for v in processed.values()) for r in sorted(working)})
        expect("each of the 2 replicas processed at least one event", len(working) == 2, sorted(working))
        info("redeliveries skipped as duplicates: %d" % sum(len(v) for v in skipped.values()))
    finally:
        rc, out = rt_scale_consumer(baseline)
        expect(
            "consumer scaled back to %d replica(s)" % baseline,
            rc == 0 and wait_for_consumers(baseline, 90),
            out.strip()[-200:],
        )


def s21_row_level_security():
    title(21, "Row-level security: the application role sees nothing without a tenant scope")
    password = cfg("DB_APP_PASSWORD")
    if not password:
        info("DB_APP_PASSWORD is not set; skipping")
        return
    app_user, db = cfg("DB_APP_USER", "sensor_app"), cfg("POSTGRES_DB", "sensor_platform")
    scope = (
        "SELECT set_config('app.tenant_id', "
        "(SELECT id::text FROM platform.tenants WHERE tenant_code = 'tenant-alpha'), false)"
    )

    def psql(*sql):
        # The password travels in the environment (compose) or on stdin (kubectl), not on the command line.
        rc, out = rt_psql(app_user, password, db, sql)
        lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
        return rc, lines[-1] if lines else None

    rc, last = psql("SELECT count(*) FROM platform.devices")
    expect("%s without a tenant scope sees 0 devices" % app_user, rc == 0 and last == "0", (rc, last))
    rc, last = psql(scope, "SELECT count(*) FROM platform.devices")
    expect("scoped to tenant-alpha the same role sees devices", rc == 0 and last not in (None, "0"), (rc, last))
    rc, last = psql(
        scope, "SELECT count(*) FROM platform.devices WHERE tenant_id <> current_setting('app.tenant_id')::uuid"
    )
    expect("scoped to tenant-alpha no other tenant's device is visible", rc == 0 and last == "0", (rc, last))


def s22_new_tenant_topic():
    title(22, "A tenant created while the consumer runs is consumed without a restart")
    code = "rt-" + RUN_ID  # tenant codes are lowercase, and a new one per run keeps the demo re-runnable
    device = "MACHINE-001"
    since = datetime.fromtimestamp(time.time() - 60, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    adm = token("platform-admin")
    st, body, _ = http(
        "POST", APP + "/api/v1/admin/tenants", bearer(adm), {"tenantId": code, "displayName": "Runtime tenant " + code}
    )
    if not expect("register new tenant %s -> 201" % code, st == 201, (st, body)):
        return
    TENANTS["rt"] = code  # the helpers resolve a tenant through TENANTS; removed again below
    try:
        st, cred = register_device("rt", device, "Device of the runtime tenant")
        if not expect("device %s registered for %s" % (device, code), bool(cred), st):
            return
        # The alpha contract fits the demo device and payload; the platform admin may manage any tenant.
        body, ctype = encode_multipart(
            {"name": "rt-readings"},
            {
                "spec": ("openapi.yaml", read_demo("tenant-alpha", "openapi.yaml"), "application/yaml"),
                "mapping": ("mapping.json", read_demo("tenant-alpha", "mapping.json"), "application/json"),
            },
        )
        st, resp, _ = http(
            "POST", APP + "/api/v1/tenants/%s/contracts" % code, bearer(adm, {"Content-Type": ctype}), body
        )
        if not expect("upload contract for %s -> 201" % code, st == 201, (st, resp)):
            return
        st, resp, _ = http(
            "POST",
            APP
            + "/api/v1/tenants/%s/contracts/%s/versions/%s/activate"
            % (code, pick(resp, "contractId", "id"), pick(resp, "version", "contractVersion")),
            bearer(adm),
        )
        if not expect("activate the contract -> 200 or 204", st in (200, 204), (st, resp)):
            return
        st, resp, _, _ = ingest("device:rt:%s" % device, reading_for("alpha", device, 71.5))
        if not expect("ingest one reading -> 202", st == 202 and isinstance(resp, dict), (st, resp)):
            return
        event_id = resp.get("eventId")
        deadline = time.time() + 40
        processed = {}
        while time.time() < deadline and event_id not in processed:
            time.sleep(3)
            processed, _ = consumer_tally(since, [event_id])
        expect(
            "the running consumer logged 'event processed' for the new tenant's event (no restart)",
            event_id in processed,
            "waited 40 s for %s" % event_id,
        )
    finally:
        TENANTS.pop("rt", None)


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
    ("device-credentials", s17_device_credentials),
    ("rate-limit", s18_rate_limit),
    ("tracing", s19_tracing),
    ("shared-dedup", s20_shared_dedup),
    ("rls", s21_row_level_security),
    ("new-tenant-topic", s22_new_tenant_topic),
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
    argv = [a for i, a in enumerate(argv) if not (a.startswith("--target") or (i and argv[i - 1] == "--target"))]
    selected = SCENARIOS if argv[0] == "all" else [resolve(a) for a in argv]
    print("run id %s | target %s | app %s | keycloak %s" % (RUN_ID, TARGET, APP, KC))
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
