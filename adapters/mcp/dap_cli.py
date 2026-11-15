#!/usr/bin/env python3
"""dap-mcp — one-command connection of DAP to MCP clients.

Subcommands:

    run      stdio MCP server (default); delegates to adapters/mcp/dap_mcp.py
    install  add or update the DAP entry in a client config (hermes|dsh)
    doctor   preflight the installation (engine, jar, tools/list, client config)
    version  print adapter, engine and API contract versions

Python 3.10+ standard library only: no PyYAML, no third-party packages.

    python3 adapters/mcp/dap_cli.py version
    python3 adapters/mcp/dap_cli.py install --client hermes [--dry-run]
    python3 adapters/mcp/dap_cli.py doctor --client dsh

Client configs are found under $HOME (or under --home when that directory
already holds a client config directory, as in the install e2e); an explicit
--config wins over both. Generated entries always carry absolute paths because
both clients scrub the child environment.
"""
from __future__ import annotations

import argparse
import collections
import importlib.util
import json
import os
import pathlib
import re
import select
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent

CLIENT_CONFIGS = {
    "hermes": pathlib.Path(".hermes") / "config.yaml",
    "dsh": pathlib.Path(".dsh") / "profiles" / "desktop" / "cordis.patch.yml",
}

# The adapter declares 28 tools: 22 core ones, the recipe and dap_sql tools of
# the adaptability stage, and the one-call dap_analyze/dap_workflow_build tools.
# test_min_tools_matches_declared_tools keeps this honest.
MIN_TOOLS = 28
PREFLIGHT_TIMEOUT = 10.0
ENGINE_START_TIMEOUT = 60.0
PROTOCOL_VERSION = "2024-11-05"

Check = collections.namedtuple("Check", "name ok detail")


# ------------------------------------------------------------------- helpers

def _load_sibling(name: str, filename: str):
    """Load a sibling module by path, so the CLI works from any cwd."""
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    if spec is None or spec.loader is None:
        raise SystemExit(f"dap-mcp: cannot load {filename}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _load_adapter():
    return _load_sibling("dap_mcp", "dap_mcp.py")


def _load_config():
    return _load_sibling("dap_mcp_config", "mcp_config.py")


def _resolve_home(raw: str | None) -> pathlib.Path:
    """DAP home: --home, then $DAP_HOME, then the repository root."""
    if raw:
        return pathlib.Path(raw).expanduser().resolve()
    env = (os.environ.get("DAP_HOME") or "").strip()
    if env:
        return pathlib.Path(env).expanduser().resolve()
    return ROOT


def _read_version(home: pathlib.Path) -> str:
    try:
        version = (home / "VERSION").read_text(encoding="utf-8").strip()
    except OSError:
        return "unknown"
    return version or "unknown"


def _read_api_version(home: pathlib.Path) -> str:
    try:
        text = (home / "api" / "openapi.yaml").read_text(encoding="utf-8")
    except OSError:
        return "unknown"
    match = re.search(r"^  version:\s*(\S+)", text, re.M)
    if not match:
        return "unknown"
    return match.group(1).strip("\"'") or "unknown"


def _client_config_path(client: str, explicit: str | None, home_flag: str | None) -> pathlib.Path:
    if explicit:
        return pathlib.Path(explicit).expanduser()
    base = pathlib.Path.home()
    if home_flag:
        candidate = pathlib.Path(home_flag).expanduser()
        if any((candidate / path.parts[0]).exists() for path in CLIENT_CONFIGS.values()):
            base = candidate
    return base / CLIENT_CONFIGS[client]


# -------------------------------------------------------------------- entry

def _python() -> str:
    """Absolute interpreter path: both clients scrub the child environment."""
    if sys.executable and os.path.isabs(sys.executable):
        return sys.executable
    found = shutil.which("python3") or shutil.which("python")
    return found or "python3"


def _java_home() -> str:
    for key in ("JAVA_HOME", "DAP_JDK"):
        value = (os.environ.get(key) or "").strip()
        if value and (pathlib.Path(value) / "bin" / "java").is_file():
            return value
    candidate = pathlib.Path.home() / "Projects" / "tools" / "jdk-21.0.13+9" / "Contents" / "Home"
    if (candidate / "bin" / "java").is_file():
        return str(candidate)
    return ""


def _entry_env(home: pathlib.Path) -> dict:
    env = {
        "DAP_HOME": str(home),
        "DAP_PORT": (os.environ.get("DAP_PORT") or "7800").strip(),
        "DAP_HEAP": (os.environ.get("DAP_HEAP") or "2g").strip(),
    }
    java_home = _java_home()
    if java_home:
        env["JAVA_HOME"] = java_home
    return env


def _adapter_path(home: pathlib.Path) -> str:
    return str(home / "adapters" / "mcp" / "dap_mcp.py")


def _hermes_entry(home: pathlib.Path) -> dict:
    return {
        "command": _python(),
        "args": [_adapter_path(home)],
        "env": _entry_env(home),
        "cwd": str(home),
        "enabled": True,
        "timeout": 300,
        "connect_timeout": 60,
        "tools": {"resources": True, "prompts": True},
    }


def _dsh_entry(home: pathlib.Path) -> dict:
    return {
        "transport": "stdio",
        "command": _python(),
        "args": [_adapter_path(home)],
        "env": _entry_env(home),
        "cwd": str(home),
        "enabled": True,
        "startupTimeoutMs": 60000,
        "toolCallTimeoutMs": 300000,
        "reconnect": True,
    }


# ------------------------------------------------------------------ commands

def _cmd_run(_args: argparse.Namespace, home: pathlib.Path) -> int:
    os.environ["DAP_HOME"] = str(home)
    _load_adapter().main()
    return 0


def _cmd_version(_args: argparse.Namespace, home: pathlib.Path) -> int:
    version = _read_version(home)
    print(f"adapter={version} engine={version} api={_read_api_version(home)}")
    return 0


def _print_fragment(path: pathlib.Path, limit: int = 20) -> None:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        print(f"dap-mcp: cannot read {path}: {exc}", file=sys.stderr)
        return
    shown = min(limit, len(lines))
    print(f"--- {path} (first {shown} of {len(lines)} lines) ---", file=sys.stderr)
    for number, line in enumerate(lines[:limit], start=1):
        print(f"{number:4d}  {line}", file=sys.stderr)


def _cmd_install(args: argparse.Namespace, home: pathlib.Path) -> int:
    config_module = _load_config()
    config_path = _client_config_path(args.client, args.config, getattr(args, "home", None))
    if not config_path.is_file() and not args.force:
        print(
            f"dap-mcp: client config not found: {config_path} (use --force to create it)",
            file=sys.stderr,
        )
        return 2
    if args.client == "hermes":
        writer = config_module.write_hermes
        entry = _hermes_entry(home)
    else:
        writer = config_module.write_dsh
        entry = _dsh_entry(home)
    try:
        text = writer(config_path, entry, dry_run=args.dry_run)
    except config_module.ConfigError as exc:
        print(f"dap-mcp: {exc}", file=sys.stderr)
        _print_fragment(config_path)
        return 2
    if args.dry_run:
        sys.stdout.write(text)
        return 0
    print(f"dap-mcp: installed dap into {config_path}")
    return 0


def _cmd_doctor(args: argparse.Namespace, home: pathlib.Path) -> int:
    os.environ["DAP_HOME"] = str(home)
    checks = doctor(getattr(args, "client", None), home)
    if getattr(args, "json", False):
        payload = [
            {"name": check.name, "ok": check.ok, "detail": check.detail}
            for check in checks
        ]
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        for check in checks:
            status = "PASS" if check.ok else "FAIL"
            print(f"{status}  {check.name:<11} {check.detail}")
    return 0 if all(check.ok for check in checks) else 1


# -------------------------------------------------------------------- doctor

def _port() -> int:
    raw = (os.environ.get("DAP_PORT") or "7800").strip()
    try:
        port = int(raw)
    except ValueError:
        return 7800
    return port if 1 <= port <= 65535 else 7800


def _engine_healthy() -> bool:
    url = f"http://127.0.0.1:{_port()}/v1/health"
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return response.status == 200
    except (OSError, ValueError):
        return False


def _check_python() -> Check:
    version = ".".join(str(part) for part in sys.version_info[:3])
    if sys.version_info >= (3, 10):
        return Check("python", True, version)
    return Check("python", False, f"{version} (need >= 3.10)")


def _check_jar(home: pathlib.Path) -> Check:
    jar = home / "engine-native" / "target" / "dap-native.jar"
    if jar.is_file():
        return Check("jar", True, str(jar))
    return Check("jar", False, f"missing {jar} (run make build-native)")


def _check_engine(home: pathlib.Path) -> Check:
    port = _port()
    if _engine_healthy():
        return Check("engine", True, f"healthy at 127.0.0.1:{port}")
    started, detail = _start_engine(home)
    if started:
        return Check("engine", True, f"{detail} at 127.0.0.1:{port}")
    return Check("engine", False, detail)


def _adapter_stderr(stream) -> str:
    try:
        stream.seek(0)
        return stream.read().strip()[-1500:]
    except (OSError, ValueError):
        return ""


def _start_engine(home: pathlib.Path, timeout: float = ENGINE_START_TIMEOUT):
    """Start the engine the way the first tool call would; return (ok, detail).

    Spawns the adapter, runs initialize and one tools/call (dap_search_nodes),
    which triggers the adapter's ensure_service(), then stops the adapter. The
    engine it started keeps running for the next real call.
    """
    adapter = pathlib.Path(_adapter_path(home))
    if not adapter.is_file():
        return False, f"adapter not found: {adapter}"
    environment = dict(os.environ, DAP_HOME=str(home))
    stderr = tempfile.TemporaryFile(mode="w+", encoding="utf-8")
    process = subprocess.Popen(
        [_python(), str(adapter)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=stderr,
        cwd=str(home),
        env=environment,
        text=True,
        bufsize=1,
    )
    deadline = time.monotonic() + timeout
    error = ""
    try:
        _send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {"name": "dap-mcp-doctor", "version": _read_version(home)},
                },
            },
        )
        _read_reply(process, 1, min(deadline, time.monotonic() + PREFLIGHT_TIMEOUT))
        _send(process, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
        _send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": {"name": "dap_search_nodes", "arguments": {}},
            },
        )
        _read_reply(process, 2, deadline)
    except Exception as exc:  # noqa: BLE001 - every failure is a failed check
        error = f"{type(exc).__name__}: {exc}"
    finally:
        _stop_process(process)
        tail = _adapter_stderr(stderr)
        stderr.close()
    if error:
        detail = f"cannot start engine: {error}"
        if tail:
            detail = f"{detail}; adapter stderr: {tail}"
        return False, detail
    if _engine_healthy():
        return True, "started by doctor"
    detail = "adapter returned but /v1/health is still down"
    if tail:
        detail = f"{detail}; adapter stderr: {tail}"
    return False, detail


def _check_port() -> Check:
    port = _port()
    if _engine_healthy():
        return Check("port", True, f"{port} in use by DAP")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", port))
    except OSError:
        return Check("port", False, f"{port} is in use by another process")
    finally:
        sock.close()
    return Check("port", True, f"{port} free")


def _check_config(client: str | None) -> Check:
    module = _load_config()
    names = [client] if client else sorted(CLIENT_CONFIGS)
    passed: list[str] = []
    failed: list[str] = []
    for name in names:
        path = _client_config_path(name, None, None)
        if not path.is_file():
            failed.append(f"{name}: config not found ({path})")
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            failed.append(f"{name}: cannot read {path}: {exc}")
            continue
        if module.has_entry(name, text):
            passed.append(f"{name}: dap entry in {path}")
        else:
            failed.append(f"{name}: no dap entry in {path}")
    if failed:
        return Check("config", False, "; ".join(failed))
    return Check("config", True, "; ".join(passed))


def _send(process, message: dict) -> None:
    process.stdin.write(json.dumps(message) + "\n")
    process.stdin.flush()


def _read_reply(process, message_id: int, deadline: float) -> dict:
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("stdio preflight timed out")
        ready, _, _ = select.select([process.stdout], [], [], remaining)
        if not ready:
            raise TimeoutError("stdio preflight timed out")
        line = process.stdout.readline()
        if not line:
            raise RuntimeError("adapter closed stdout before replying")
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("id") != message_id:
            continue
        if "error" in message:
            raise RuntimeError(f"JSON-RPC error: {message['error']}")
        result = message.get("result")
        return result if isinstance(result, dict) else {}


def _stop_process(process) -> None:
    try:
        process.stdin.close()
    except (OSError, ValueError):
        pass
    try:
        process.wait(timeout=1)
        return
    except subprocess.TimeoutExpired:
        pass
    process.terminate()
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=3)


def stdio_preflight(home: pathlib.Path, timeout: float = PREFLIGHT_TIMEOUT) -> int:
    """Run initialize then tools/list over stdio; return the number of tools.

    The adapter is started directly, so the check does not depend on any client
    CLI being installed or runnable.
    """
    adapter = pathlib.Path(_adapter_path(home))
    if not adapter.is_file():
        raise FileNotFoundError(f"adapter not found: {adapter}")
    environment = dict(os.environ, DAP_HOME=str(home))
    process = subprocess.Popen(
        [_python(), str(adapter)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        cwd=str(home),
        env=environment,
        text=True,
        bufsize=1,
    )
    deadline = time.monotonic() + timeout
    try:
        _send(
            process,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {"name": "dap-mcp-doctor", "version": _read_version(home)},
                },
            },
        )
        _read_reply(process, 1, deadline)
        _send(process, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
        _send(process, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        result = _read_reply(process, 2, deadline)
    finally:
        _stop_process(process)
    tools = result.get("tools")
    if not isinstance(tools, list):
        raise RuntimeError("tools/list returned no tools array")
    return len(tools)


def _check_preflight(home: pathlib.Path) -> Check:
    try:
        count = stdio_preflight(home)
    except Exception as exc:  # noqa: BLE001 - every failure is a failed check
        return Check("preflight", False, f"{type(exc).__name__}: {exc}")
    if count >= MIN_TOOLS:
        return Check("preflight", True, f"tools/list returned {count} tools")
    return Check("preflight", False, f"tools/list returned {count} tools (need >= {MIN_TOOLS})")


EXTENSIONS = ("sqlite_scanner", "postgres_scanner", "mysql_scanner", "httpfs")


def _extension_dir(home: pathlib.Path) -> pathlib.Path:
    raw = (os.environ.get("DAP_DUCKDB_EXTENSION_DIR") or "").strip()
    if raw:
        return pathlib.Path(raw).expanduser()
    return home / "build" / "duckdb-ext"


def _check_extensions(home: pathlib.Path) -> Check:
    """DuckDB extensions are optional: a missing set is a hint, never a failure."""
    directory = _extension_dir(home)
    present, missing = [], []
    for name in EXTENSIONS:
        if directory.is_dir() and any(directory.rglob(name + "*.duckdb_extension")):
            present.append(name)
        else:
            missing.append(name)
    if not missing:
        return Check("extensions", True, "installed: " + ", ".join(present))
    return Check("extensions", True,
                 "optional: missing " + ", ".join(missing) + " in " + str(directory)
                 + "; run make extensions")


def doctor(client: str | None, home: pathlib.Path) -> list:
    """Run every check; the order is stable for humans and for --json."""
    return [
        _check_python(),
        _check_jar(home),
        _check_engine(home),
        _check_preflight(home),
        _check_config(client),
        _check_extensions(home),
        _check_port(),
    ]


# --------------------------------------------------------------------- entry


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "--home",
        default=argparse.SUPPRESS,
        metavar="PATH",
        help="DAP home (default: $DAP_HOME or the repository root)",
    )

    parser = argparse.ArgumentParser(
        prog="dap-mcp",
        description="Connect DAP to MCP clients and run the stdio server.",
        parents=[common],
    )
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("run", parents=[common], help="run the stdio MCP server (default)")

    install = sub.add_parser("install", parents=[common], help="add DAP to a client config")
    install.add_argument("--client", required=True, choices=sorted(CLIENT_CONFIGS))
    install.add_argument("--config", metavar="PATH", help="explicit client config path")
    install.add_argument("--dry-run", action="store_true", help="print the result, write nothing")
    install.add_argument("--force", action="store_true", help="create a missing client config")

    doctor = sub.add_parser("doctor", parents=[common], help="preflight the installation")
    doctor.add_argument("--client", choices=sorted(CLIENT_CONFIGS))
    doctor.add_argument("--json", action="store_true", help="print checks as JSON")

    sub.add_parser("version", parents=[common], help="print adapter, engine and API versions")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:  # --help and argparse errors
        return int(exc.code or 0)
    home = _resolve_home(getattr(args, "home", None))
    command = args.command or "run"
    handlers = {
        "run": _cmd_run,
        "install": _cmd_install,
        "doctor": _cmd_doctor,
        "version": _cmd_version,
    }
    return handlers[command](args, home)


if __name__ == "__main__":
    sys.exit(main())
