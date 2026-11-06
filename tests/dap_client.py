#!/usr/bin/env python3
"""DAP /v1 API client + check harness (Python 3 stdlib only).

Shared by the native test suite (goldens, smoke, MCP e2e, bench).

The client is deliberately thin: it never interprets responses, it only turns
HTTP errors into Response objects so scenarios can assert exact status codes.
"""

from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.request

PASS = "PASS"
FAIL = "FAIL"
SKIP = "SKIP"

DEFAULT_TIMEOUT = 600.0


class ApiError(RuntimeError):
    """Protocol/client level failure (not an assertion)."""


class ConnectionFailed(ApiError):
    """The service did not answer at all."""


class Response:
    """Status + decoded JSON + headers of one HTTP response."""

    __slots__ = ("status", "body", "headers")

    def __init__(self, status, body, headers):
        self.status = status
        self.body = body
        self.headers = headers or {}

    @property
    def ok(self):
        return 200 <= self.status < 300

    def header(self, name, default=None):
        """Case-insensitive header lookup (http.client keeps server casing)."""
        wanted = name.lower()
        for key, value in self.headers.items():
            if key.lower() == wanted:
                return value
        return default

    def require(self, status=200):
        if self.status != status:
            raise AssertionError(
                "expected HTTP %s, got %s: %s" % (status, self.status, _short(self.body)))
        return self.body


def _short(value, limit=600):
    try:
        text = json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        text = repr(value)
    return text if len(text) <= limit else text[:limit] + "..."


def _decode(raw):
    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8", "replace"))
    except ValueError:
        return {"raw": raw.decode("utf-8", "replace")[:500]}


def digest(value):
    """Stable digest of a JSON value, used for determinism checks."""
    payload = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(payload).hexdigest()


class Api:
    """One DAP session: every request carries X-DAP-Session."""

    def __init__(self, base, session="default", timeout=DEFAULT_TIMEOUT):
        self.base = base.rstrip("/")
        self.session = session
        self.timeout = timeout
        self.calls = 0

    # ------------------------------------------------------------- transport

    def request(self, method, path, body=None, timeout=None, session=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("X-DAP-Session", session or self.session)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        self.calls += 1
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
                return Response(resp.status, _decode(resp.read()), dict(resp.headers))
        except urllib.error.HTTPError as err:
            return Response(err.code, _decode(err.read()), dict(err.headers))
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as err:
            raise ConnectionFailed("%s %s failed: %s" % (method, path, err)) from err

    def get(self, path, **kwargs):
        return self.request("GET", path, **kwargs)

    def post(self, path, body=None, **kwargs):
        return self.request("POST", path, body, **kwargs)

    def patch(self, path, body=None, **kwargs):
        return self.request("PATCH", path, body, **kwargs)

    def delete(self, path, body=None, **kwargs):
        return self.request("DELETE", path, body, **kwargs)

    # ---------------------------------------------------------------- health

    def health(self):
        return self.get("/v1/health").require()

    def server_info(self):
        return self.get("/v1").require()

    # ----------------------------------------------------------------- nodes

    def nodes_index(self, query=None, category=None, limit=None):
        params = {}
        if query is not None:
            params["q"] = query
        if category is not None:
            params["category"] = category
        if limit is not None:
            params["limit"] = str(limit)
        path = "/v1/nodes"
        if params:
            path += "?" + urllib.parse.urlencode(params)
        return self.get(path).require()

    def node(self, node_id):
        return self.get("/v1/nodes/" + urllib.parse.quote(node_id, safe=""))

    def dialog(self, node_id):
        return self.get("/v1/nodes/" + urllib.parse.quote(node_id, safe="") + "/dialog")

    # ------------------------------------------------------------- workflows

    def create_workflow(self, name):
        body = self.post("/v1/workflows", {"name": name}).require()
        if "id" not in body:
            raise AssertionError("workflow response has no id: " + _short(body))
        return body["id"]

    def workflows(self):
        return self.get("/v1/workflows").require()["workflows"]

    def delete_workflow(self, workflow):
        return self.delete("/v1/workflows/" + workflow)

    def add_node(self, workflow, factory, settings=None):
        payload = {"factory": factory}
        if settings is not None:
            payload["settings"] = settings
        return self.post("/v1/workflows/%s/nodes" % workflow, payload)

    def add_node_ok(self, workflow, factory, settings=None):
        body = self.add_node(workflow, factory, settings).require()
        return body["node"]

    def configure(self, workflow, node, settings):
        return self.patch("/v1/workflows/%s/nodes/%s" % (workflow, node), {"settings": settings})

    def connect(self, workflow, source, target, source_port=0, target_port=0):
        return self.post("/v1/workflows/%s/connect" % workflow, {
            "from": source,
            "to": target,
            "fromPort": source_port,
            "toPort": target_port,
        })

    def connect_ok(self, workflow, source, target, source_port=0, target_port=0):
        return self.connect(workflow, source, target, source_port, target_port).require()

    def run(self, workflow, body=None, timeout=None):
        return self.post("/v1/workflows/%s/run" % workflow, body or {}, timeout=timeout)

    def run_ok(self, workflow, body=None, timeout=None):
        return self.run(workflow, body, timeout).require()

    def run_async(self, workflow, timeout_sec=None):
        body = {"async": True}
        if timeout_sec is not None:
            body["timeoutSec"] = timeout_sec
        return self.post("/v1/workflows/%s/run" % workflow, body)

    def workflow_nodes(self, workflow):
        return self.get("/v1/workflows/%s/nodes" % workflow).require()["nodes"]

    def debug(self, workflow):
        return self.get("/v1/workflows/%s/debug" % workflow).require()

    def table(self, workflow, node, limit=50, port=0):
        return self.get(
            "/v1/workflows/%s/nodes/%s/table?port=%d&limit=%d" % (workflow, node, port, limit)
        ).require()

    def profile(self, workflow, node):
        return self.get("/v1/workflows/%s/nodes/%s/profile" % (workflow, node)).require()

    # ------------------------------------------------------------------ jobs

    def jobs(self):
        return self.get("/v1/jobs").require()

    def job(self, job_id):
        return self.get("/v1/jobs/" + job_id)

    def cancel_job(self, job_id):
        return self.delete("/v1/jobs/" + job_id)

    def wait_job(self, job_id, timeout=120.0, interval=0.2):
        """Poll until the job leaves queued/running; returns (job body, states)."""
        states = []
        deadline = time.time() + timeout
        body = None
        while time.time() < deadline:
            response = self.job(job_id)
            if response.status != 200:
                raise AssertionError("GET /v1/jobs/%s -> %s %s"
                                     % (job_id, response.status, _short(response.body)))
            body = response.body
            states.append(body.get("state"))
            if body.get("state") not in ("queued", "running"):
                return body, states
            time.sleep(interval)
        raise AssertionError("job %s did not finish in %.1fs (states: %s)"
                             % (job_id, timeout, states[-6:]))


# --------------------------------------------------------------------- checks


class Check:
    __slots__ = ("name", "status", "detail")

    def __init__(self, name, status, detail=""):
        self.name = name
        self.status = status
        self.detail = detail

    def line(self):
        text = "  %-5s %-44s %s" % (self.status, self.name, self.detail)
        return text.rstrip()


class Scenario:
    """Named list of checks; each check is a callable that raises on failure."""

    def __init__(self, name):
        self.name = name
        self.checks = []

    def run_check(self, name, fn, skip=None):
        if skip is not None:
            self.checks.append(Check(name, SKIP, skip))
            return
        try:
            detail = fn()
        except AssertionError as err:
            self.checks.append(Check(name, FAIL, str(err)))
        except ApiError as err:
            self.checks.append(Check(name, FAIL, "%s: %s" % (type(err).__name__, err)))
        except Exception as err:  # noqa: BLE001 - a broken check is a failed check
            self.checks.append(Check(name, FAIL, "%s: %s" % (type(err).__name__, err)))
        else:
            self.checks.append(Check(name, PASS, "" if detail is None else str(detail)))

    def skip(self, name, reason):
        self.checks.append(Check(name, SKIP, reason))

    def status(self):
        statuses = {check.status for check in self.checks}
        if FAIL in statuses:
            return FAIL
        if statuses == {SKIP}:
            return SKIP
        return PASS

    def counts(self):
        counts = {PASS: 0, FAIL: 0, SKIP: 0}
        for check in self.checks:
            counts[check.status] += 1
        return counts

    def render(self):
        counts = self.counts()
        head = "[%s] %s  (%d pass, %d fail, %d skip)" % (
            self.status(), self.name, counts[PASS], counts[FAIL], counts[SKIP])
        return "\n".join([head] + [check.line() for check in self.checks])


# ----------------------------------------------------------- assert helpers


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


def expect_eq(actual, expected, what):
    if actual != expected:
        raise AssertionError("%s: expected %r, got %r" % (what, expected, actual))


def expect_close(actual, expected, tolerance, what):
    try:
        delta = abs(float(actual) - float(expected))
    except (TypeError, ValueError):
        raise AssertionError("%s: expected a number close to %r, got %r"
                             % (what, expected, actual))
    if delta > tolerance:
        raise AssertionError("%s: expected %r +/- %r, got %r" % (what, expected, tolerance, actual))


def expect_in(value, allowed, what):
    if value not in allowed:
        raise AssertionError("%s: expected one of %r, got %r" % (what, sorted(allowed), value))


def expect_contains(haystack, needle, what):
    if needle not in (haystack or ""):
        raise AssertionError("%s: expected %r in %r" % (what, needle, haystack))
