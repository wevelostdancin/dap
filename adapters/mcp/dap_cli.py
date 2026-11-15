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
import importlib.util
import os
import pathlib
import re
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent

CLIENT_CONFIGS = {
    "hermes": pathlib.Path(".hermes") / "config.yaml",
    "dsh": pathlib.Path(".dsh") / "profiles" / "desktop" / "cordis.patch.yml",
}


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
        print("dap-mcp: install --client dsh is not implemented yet", file=sys.stderr)
        return 2
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


def _cmd_doctor(_args: argparse.Namespace, _home: pathlib.Path) -> int:
    print("dap-mcp: doctor is not implemented yet", file=sys.stderr)
    return 2


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
