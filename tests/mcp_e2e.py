#!/usr/bin/env python3
"""End-to-end check of the MCP adapter against the native engine (Phase 3 DoD).

Launches "adapters/mcp/dap_mcp.py" on a free loopback port and
lets the adapter start "engine-native/target/dap-native.jar" on its first tool
call. The scenario then uses MCP tools only:

    dap_search_nodes -> dap_workflow_create -> dap_add_node (csv_reader, sorter)
    -> dap_connect -> dap_run -> dap_get_table -> dap_delete_workflow
    -> dap_shutdown -> restart via the next tool call

and stops the service with POST /v1/shutdown at the end. The whole run is
bounded by --timeout (120 s by default).

Requires the native jar and JDK 21
(discovered through JAVA_HOME, the usual local install, or java on PATH).

    python3 tests/mcp_e2e.py [--port 7923] [--timeout 120]
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import queue
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "adapters" / "mcp" / "dap_mcp.py"
JAR = ROOT / "engine-native" / "target" / "dap-native.jar"
PROTOCOL = "2024-11-05"
SPECS_INDEX = ROOT / "engine-native" / "src" / "main" / "resources" / "specs" / "index.txt"
REQUIRED_TOOLS = {
    "dap_search_nodes",
    "dap_workflow_create",
    "dap_add_node",
    "dap_connect",
    "dap_run",
    "dap_get_table",
    "dap_save_workflow",
    "dap_load_workflow",
    "dap_shutdown",
    "dap_delete_workflow",
    "dap_ingest",
    "dap_recipe_list",
    "dap_recipe_save",
    "dap_recipe_run",
    "dap_sql",
    "dap_report",
    "dap_profile",
}


class Failure(Exception):
    """A failed expectation; the message is printed and the process exits 1."""


def check(condition, message):
    if not condition:
        raise Failure(message)


def step(message):
    print("[mcp-e2e] " + message, flush=True)


def health(base, timeout=2.0):
    try:
        with urllib.request.urlopen(base + "/v1/health", timeout=timeout) as response:
            return response.status == 200
    except Exception:
        return False


def pick_port(requested):
    """Use the requested port when free, otherwise an ephemeral one."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", requested))
            return requested
        except OSError:
            pass
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def find_java_home():
    local = pathlib.Path.home() / "Projects" / "tools" / "jdk-21.0.13+9" / "Contents" / "Home"
    for candidate in (os.environ.get("JAVA_HOME"), str(local)):
        if candidate and (pathlib.Path(candidate) / "bin" / "java").is_file():
            return candidate
    java = shutil.which("java")
    if java:
        home = pathlib.Path(java).resolve().parent.parent
        if (home / "bin" / "java").is_file():
            return str(home)
    return None


def shutdown(base):
    request = urllib.request.Request(base + "/v1/shutdown", data=b"{}", method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("X-DAP-Session", "mcp-e2e")
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            body = json.loads(response.read().decode("utf-8") or "{}")
            return response.status == 200 and body.get("ok") is True, body
    except Exception as error:  # noqa: BLE001 - shutdown is best effort cleanup
        return False, str(error)


NATIVE_PID_RE = re.compile(r"dap-mcp: native engine pid=(\d+)")


def launched_engine_pids(log_path):
    """PIDs the adapter announced on stderr; empty when it reused a service."""
    try:
        text = log_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    return [int(match.group(1)) for match in NATIVE_PID_RE.finditer(text)]


def process_command(pid):
    """Command line from ps, or None when the platform/sandbox hides processes."""
    try:
        result = subprocess.run(
            ["ps", "-p", str(pid), "-o", "command="],
            capture_output=True, text=True, timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def process_alive(pid):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except OSError:
        return True
    return True


def engine_gone(pid):
    command = process_command(pid)
    if command is not None:
        return command == ""
    return not process_alive(pid)


def terminate_engine(pid, timeout=10.0):
    """Terminate the PID the adapter announced, never an unrelated process."""
    command = process_command(pid)
    if command is not None and "dap-native.jar" not in command:
        return False
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    if command is None:
        # ps is unavailable (sandbox/container): we cannot distinguish a zombie
        # from a live process, so signal and let the health check decide.
        time.sleep(min(timeout, 2.0))
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        return True
    limit = time.monotonic() + timeout
    while time.monotonic() < limit:
        if engine_gone(pid):
            return True
        time.sleep(0.1)
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        return True
    limit = time.monotonic() + 3
    while time.monotonic() < limit:
        if engine_gone(pid):
            return True
        time.sleep(0.1)
    return False


def wait_engine_down(base, timeout=10.0):
    limit = time.monotonic() + timeout
    while time.monotonic() < limit and health(base):
        time.sleep(0.1)
    return not health(base)


class Mcp:
    """Line-oriented JSON-RPC 2.0 client over the adapter's stdio transport."""

    def __init__(self, process, deadline):
        self.process = process
        self.deadline = deadline
        self._next_id = 0
        self._messages = queue.Queue()
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    def _read_loop(self):
        try:
            for line in self.process.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    self._messages.put(json.loads(line))
                except json.JSONDecodeError:
                    self._messages.put({"__invalid__": line})
        finally:
            self._messages.put(None)

    def _send(self, payload):
        try:
            self.process.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            raise Failure("adapter closed stdin: %s" % (error,))

    def _receive(self, timeout, what):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise Failure("timeout budget exhausted while waiting for %s" % (what,))
        try:
            message = self._messages.get(timeout=min(timeout, remaining))
        except queue.Empty:
            raise Failure("timed out after %.0f s waiting for %s" % (timeout, what))
        if message is None:
            raise Failure("adapter closed stdout while waiting for %s" % (what,))
        if "__invalid__" in message:
            raise Failure("adapter wrote invalid JSON: %r" % (message["__invalid__"][:300],))
        return message

    def request(self, method, params, timeout, what=None):
        self._next_id += 1
        request_id = self._next_id
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        message = self._receive(timeout, what or method)
        if message.get("jsonrpc") != "2.0" or message.get("id") != request_id:
            raise Failure("unexpected JSON-RPC response to %s: %r" % (method, message))
        if "error" in message:
            error = message["error"]
            raise Failure("%s failed: code=%s message=%s"
                          % (method, error.get("code"), error.get("message")))
        return message["result"]

    def notify(self, method, params=None):
        self._send({"jsonrpc": "2.0", "method": method, "params": params or {}})

    def tool(self, name, arguments, timeout):
        result = self.request("tools/call", {"name": name, "arguments": arguments},
                              timeout, what="%s %s" % (name, arguments))
        content = result.get("content")
        check(isinstance(content, list) and content, "%s returned no content: %r"
              % (name, result))
        check(content[0].get("type") == "text", "%s returned non-text content: %r"
              % (name, content[0]))
        try:
            return json.loads(content[0]["text"])
        except json.JSONDecodeError as error:
            raise Failure("%s returned invalid JSON: %s (%r)"
                          % (name, error, content[0]["text"][:300]))


def write_csv(directory):
    source = pathlib.Path(directory) / "mcp-e2e.csv"
    source.write_text(
        "name,age,score\n"
        "alice,34,9.5\n"
        "bob,28,7.0\n"
        "carol,41,8.25\n",
        encoding="utf-8",
    )
    return source


def scenario(client, source, base):
    step("initialize + tools/list")
    init = client.request("initialize", {
        "protocolVersion": PROTOCOL,
        "capabilities": {},
        "clientInfo": {"name": "mcp-e2e", "version": "1.0"},
    }, 10)
    check(init.get("protocolVersion") == PROTOCOL,
          "unexpected protocol version: %r" % (init.get("protocolVersion"),))
    check((init.get("serverInfo") or {}).get("name") == "dap-mcp",
          "unexpected serverInfo: %r" % (init.get("serverInfo"),))
    client.notify("notifications/initialized")

    tools = client.request("tools/list", {}, 10).get("tools") or []
    names = {tool.get("name") for tool in tools}
    check(len(tools) == 30, "tools/list returned %d tools, expected 30" % len(tools))
    missing = REQUIRED_TOOLS - names
    check(not missing, "tools/list misses %s" % sorted(missing))

    step("start native engine via the adapter and check the catalog")
    catalog = client.tool("dap_search_nodes", {"limit": 100},
                          timeout=client.deadline - time.monotonic())
    nodes = catalog.get("nodes") or []
    ids = [node.get("id") for node in nodes]
    expected = len([line for line in SPECS_INDEX.read_text(encoding="utf-8").splitlines()
                    if line.strip() and not line.strip().startswith("#")])
    check(len(ids) == expected,
          "native catalog should expose one node per spec (%d), got %d: %s"
          % (expected, len(ids), sorted(ids)))
    check(all(str(node_id).startswith("dap.") for node_id in ids),
          "non-native node ids in catalog: %s" % sorted(ids))
    check(health(base), "engine did not become healthy at %s" % base)
    step("catalog: %d nodes, all dap.*; health ok" % len(ids))

    step("dap_workflow_create")
    workflow = client.tool("dap_workflow_create", {"name": "mcp-e2e"}, 10)["id"]
    check(str(workflow).startswith("wf-"), "unexpected workflow id: %r" % (workflow,))

    step("dap_add_node dap.csv_reader + dap.sorter")
    reader = client.tool("dap_add_node", {
        "workflow": workflow,
        "factory": "dap.csv_reader",
        "settings": {
            "file": str(source),
            "delimiter": ",",
            "header": True,
            "columns": [
                {"name": "name", "type": "Utf8"},
                {"name": "age", "type": "Int64"},
                {"name": "score", "type": "Float64"},
            ],
            "emptyStringIsNull": False,
        },
    }, 15)["node"]
    sorter = client.tool("dap_add_node", {
        "workflow": workflow,
        "factory": "dap.sorter",
        "settings": {"keys": [{"column": "score", "direction": "desc"}]},
    }, 15)["node"]

    step("dap_connect + dap_run")
    client.tool("dap_connect", {
        "workflow": workflow, "from": reader, "fromPort": 0,
        "to": sorter, "toPort": 0,
    }, 10)
    run = client.tool("dap_run", {"workflow": workflow, "timeoutSec": 30}, 60)
    check(run.get("ok") is True, "dap_run was not ok: %r" % (run,))
    states = {entry.get("node"): entry.get("state") for entry in run.get("nodes") or []}
    check(len(states) == 2, "unexpected run node count: %r" % (states,))
    check(set(states.values()) == {"EXECUTED"}, "unexpected node states: %r" % (states,))
    step("run ok: %s in %s ms" % (states, run.get("durationMs")))

    step("dap_get_table")
    table = client.tool("dap_get_table", {
        "workflow": workflow, "node": sorter, "port": 0, "limit": 10,
    }, 15)
    check(table.get("rows") == 3, "table rows=%r, expected 3" % (table.get("rows"),))
    columns = [column.get("name") for column in table.get("columns") or []]
    check(columns == ["name", "age", "score"], "unexpected columns: %r" % (columns,))
    data = table.get("data") or []
    check(len(data) == 3, "table preview has %d rows, expected 3" % len(data))
    names = [row[0] for row in data]
    check(names == ["alice", "carol", "bob"],
          "sorter order is %r, expected ['alice', 'carol', 'bob']" % (names,))
    ages = [row[1] for row in data]
    check(ages == ["34", "41", "28"], "unexpected age values: %r" % (ages,))
    step("table: rows=3 columns=%s order=%s" % (columns, names))

    step("dap_delete_workflow")
    deleted = client.tool("dap_delete_workflow", {"workflow": workflow}, 10)
    check(deleted.get("ok") is True, "delete was not ok: %r" % (deleted,))
    remaining = client.tool("dap_list_workflows", {}, 10).get("workflows") or []
    check(all(item.get("id") != workflow for item in remaining),
          "workflow %s is still listed after delete" % (workflow,))
    step("workflow deleted")

    step("dap_shutdown + restart")
    down = client.tool("dap_shutdown", {}, 15)
    check(down.get("ok") is True, "shutdown was not ok: %r" % (down,))
    catalog = client.tool("dap_search_nodes", {"limit": 5}, 30)
    check(len(catalog.get("nodes") or []) > 0, "engine did not restart after dap_shutdown")
    step("engine restarted after dap_shutdown")


def stop_adapter(process):
    if process.poll() is not None:
        return
    try:
        if process.stdin:
            process.stdin.close()
    except OSError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def run(port, timeout):
    deadline = time.monotonic() + timeout
    check(ADAPTER.is_file(), "adapter not found: %s" % ADAPTER)
    check(JAR.is_file(),
          "native jar not found: %s (build it: cd engine-native && ./mvnw -o -DskipTests package)"
          % JAR)

    chosen = pick_port(port)
    base = "http://127.0.0.1:%d" % chosen
    check(not health(base), "port %d already serves a DAP engine; refusing to reuse it" % chosen)
    step("adapter=%s port=%d timeout=%ss"
         % (ADAPTER.relative_to(ROOT), chosen, timeout))

    env = dict(os.environ)
    env.update({
        "DAP_HOME": str(ROOT),
        "DAP_PORT": str(chosen),
        "DAP_BIND": "127.0.0.1",
        "DAP_SESSION": "mcp-e2e",
    })
    java_home = find_java_home()
    if java_home:
        env["JAVA_HOME"] = java_home
    else:
        step("JAVA_HOME not found; relying on java from PATH")

    with tempfile.TemporaryDirectory(prefix="dap-mcp-e2e-") as directory:
        source = write_csv(directory)
        log_path = pathlib.Path(directory) / "adapter-stderr.log"
        log = open(log_path, "w+", encoding="utf-8")
        process = subprocess.Popen(
            [sys.executable, str(ADAPTER)],
            cwd=str(ROOT),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=log,
            text=True,
            encoding="utf-8",
            bufsize=1,
            env=env,
        )
        failed = True
        try:
            client = Mcp(process, deadline)
            scenario(client, source, base)
            check(health(base), "engine health check failed after the scenario")
            failed = False
        finally:
            step("shutting the engine down")
            cleanup_error = None
            log.flush()
            launched = launched_engine_pids(log_path)
            stopped, body = shutdown(base)
            if stopped:
                step("shutdown ok: %s" % (body,))
            else:
                step("WARNING: /v1/shutdown failed: %s" % (body,))

            terminated = []
            if not stopped:
                for pid in launched:
                    if terminate_engine(pid):
                        terminated.append(pid)
                        step("terminated launched engine pid %d" % pid)

            down = wait_engine_down(base, 10)
            if not down and launched:
                for pid in launched:
                    if pid not in terminated and terminate_engine(pid):
                        terminated.append(pid)
                        step("terminated launched engine pid %d" % pid)
                down = wait_engine_down(base, 5)
            if not down:
                if failed:
                    print("mcp-e2e: WARNING: engine is still running after cleanup",
                          file=sys.stderr)
                else:
                    cleanup_error = Failure("engine still answers /v1/health after cleanup")
            elif not stopped and not terminated and not failed:
                cleanup_error = Failure("/v1/shutdown failed: %s" % (body,))

            stop_adapter(process)
            # The adapter may have launched the child just before it was stopped.
            if health(base):
                shutdown(base)
                wait_engine_down(base, 10)
            if health(base) and failed:
                print("mcp-e2e: WARNING: engine is still running after cleanup",
                      file=sys.stderr)
            elif health(base) and cleanup_error is None:
                cleanup_error = Failure("engine still answers /v1/health after cleanup")

            log.flush()
            log.close()
            if failed:
                text = log_path.read_text(encoding="utf-8", errors="replace")
                if text.strip():
                    print("--- adapter stderr ---", file=sys.stderr)
                    print(text[-4000:], file=sys.stderr)
                    print("----------------------", file=sys.stderr)
            log_path.unlink(missing_ok=True)
            if cleanup_error is not None:
                raise cleanup_error

    step("PASS")


def main(argv):
    parser = argparse.ArgumentParser(description="MCP adapter end-to-end check on dap-native")
    parser.add_argument("--port", type=int, default=7923,
                        help="preferred loopback port (default: 7923; "
                             "a free port is used if busy)")
    parser.add_argument("--timeout", type=float, default=120.0,
                        help="whole-run timeout in seconds (default: 120)")
    args = parser.parse_args(argv)
    try:
        run(args.port, args.timeout)
    except Failure as error:
        print("mcp-e2e: FAIL: %s" % (error,), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("mcp-e2e: interrupted", file=sys.stderr)
        return 130
    except Exception:  # noqa: BLE001 - a crash is still a test failure
        traceback.print_exc()
        print("mcp-e2e: FAIL: unexpected exception", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
