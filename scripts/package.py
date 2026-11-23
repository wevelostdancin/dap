#!/usr/bin/env python3
"""Build the self-contained DAP archive (Python 3 standard library only).

    python3 scripts/package.py --skip-python
    python3 scripts/package.py --jar path/to/dap-native.jar --version 1.3.0

The staging directory is kept under build/package/ so it can be inspected and
used directly; the tar.gz and its .sha256 are written next to it.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import shutil
import sys
import tarfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
LAUNCHER_ENGINE = (
    '#!/bin/sh\n'
    'exec "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/dap" engine "$@"\n'
)
LAUNCHER_MCP = (
    '#!/bin/sh\n'
    'exec "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/dap" mcp "$@"\n'
)
# adapters/mcp/dap_cli.py resolves the jar and java the way the repository does.
COMPAT_JAR = "engine-native/target/dap-native.jar"


def platform_tag(system: str, machine: str) -> str:
    """Return the os-arch tag used in the archive name."""
    os_tag = {"Darwin": "macos", "Linux": "linux"}.get(system)
    arch = {
        "arm64": "arm64", "aarch64": "arm64",
        "x86_64": "x64", "amd64": "x64",
    }.get(machine)
    if not os_tag or not arch:
        raise ValueError(f"unsupported platform: {system} {machine}")
    return f"{os_tag}-{arch}"


def archive_name(version: str, system: str, machine: str) -> str:
    """Return the tar.gz file name for a version and host platform."""
    return f"dap-{version}-{platform_tag(system, machine)}.tar.gz"


def checksum_lines(root: pathlib.Path) -> list[str]:
    """Return sha256sum-style lines for every regular file under root.

    SHA256SUMS itself and symlinks are excluded; paths are POSIX and sorted so
    the result is stable across filesystems and Python versions.
    """
    lines = []
    for directory, subdirs, filenames in os.walk(root):
        subdirs.sort()
        for filename in sorted(filenames):
            if filename == "SHA256SUMS":
                continue
            path = pathlib.Path(directory) / filename
            if path.is_symlink() or not path.is_file():
                continue
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            name = path.relative_to(root).as_posix()
            lines.append(f"{digest}  {name}")
    return sorted(lines, key=lambda line: line.split("  ", 1)[1])


def strip_pycache(root: pathlib.Path) -> None:
    """Remove bytecode caches so the checksums describe immutable files."""
    for directory in sorted(root.rglob("__pycache__"), reverse=True):
        if directory.is_dir() and not directory.is_symlink():
            shutil.rmtree(directory)


def layout(root: pathlib.Path) -> dict[str, pathlib.Path]:
    """Paths that make up one unpacked DAP distribution."""
    return {
        "bin/dap": root / "bin" / "dap",
        "bin/dap-engine": root / "bin" / "dap-engine",
        "bin/dap-mcp": root / "bin" / "dap-mcp",
        "lib/dap-native.jar": root / "lib" / "dap-native.jar",
        "runtime": root / "runtime",
        "python": root / "python",
        "adapters/mcp": root / "adapters" / "mcp",
        "recipes": root / "recipes",
        "examples": root / "examples",
        "VERSION": root / "VERSION",
        "LICENSE": root / "LICENSE",
        "NOTICE": root / "NOTICE",
        "README.md": root / "README.md",
        "SHA256SUMS": root / "SHA256SUMS",
    }


def _resolve(value: str) -> pathlib.Path:
    path = pathlib.Path(value).expanduser()
    return path if path.is_absolute() else ROOT / path


def _sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _write_executable(path: pathlib.Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def build(args: argparse.Namespace) -> pathlib.Path:
    jar = _resolve(args.jar)
    out = _resolve(args.out)
    python_runtime = _resolve(args.python_runtime)
    version = args.version or (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    if not version:
        raise SystemExit("package: empty version (check VERSION)")
    if not jar.is_file():
        raise SystemExit(f"package: jar not found: {jar}")

    name = f"dap-{version}-{platform_tag(args.os, args.arch)}"
    stage = (out / name).resolve()
    if stage.parent != out.resolve() or not stage.name.startswith("dap-"):
        raise SystemExit(f"package: refusing unexpected staging path: {stage}")
    if stage.exists():
        if not args.keep:
            shutil.rmtree(stage)
        else:
            print(f"package: reusing {stage}")
    stage.mkdir(parents=True, exist_ok=True)

    paths = layout(stage)
    for key in ("bin", "lib", "adapters/mcp", "recipes", "examples"):
        (stage / key).mkdir(parents=True, exist_ok=True)

    shutil.copyfile(ROOT / "scripts" / "dap.sh", paths["bin/dap"])
    paths["bin/dap"].chmod(0o755)
    _write_executable(paths["bin/dap-engine"], LAUNCHER_ENGINE)
    _write_executable(paths["bin/dap-mcp"], LAUNCHER_MCP)
    shutil.copyfile(jar, paths["lib/dap-native.jar"])

    if not args.skip_runtime:
        runtime = out / "runtime"
        if not (runtime / "bin" / "java").is_file():
            raise SystemExit(f"package: runtime missing: {runtime} (run make runtime)")
        shutil.copytree(runtime, paths["runtime"], symlinks=True, dirs_exist_ok=True)
    if not args.skip_python:
        if not (python_runtime / "bin" / "python3").is_file():
            raise SystemExit(
                f"package: python runtime missing: {python_runtime} "
                "(run make python-runtime or use --skip-python)"
            )
        shutil.copytree(python_runtime, paths["python"], symlinks=True, dirs_exist_ok=True)

    for source in sorted((ROOT / "adapters" / "mcp").glob("*.py")):
        shutil.copyfile(source, paths["adapters/mcp"] / source.name)
    readme = ROOT / "adapters" / "mcp" / "README.md"
    if readme.is_file():
        shutil.copyfile(readme, paths["adapters/mcp"] / readme.name)
    for source in sorted((ROOT / "recipes").glob("*.json")):
        shutil.copyfile(source, paths["recipes"] / source.name)
    examples = ROOT / "examples"
    if examples.is_dir():
        shutil.copytree(examples, paths["examples"], symlinks=True, dirs_exist_ok=True)
    for filename in ("VERSION", "LICENSE", "NOTICE", "README.md"):
        shutil.copyfile(ROOT / filename, paths[filename])

    # dap_cli.py doctor checks $DAP_HOME/engine-native/target/dap-native.jar.
    compat = stage / COMPAT_JAR
    compat.parent.mkdir(parents=True, exist_ok=True)
    if compat.is_symlink() or compat.exists():
        compat.unlink()
    compat.symlink_to("../../lib/dap-native.jar")

    strip_pycache(stage)
    paths["SHA256SUMS"].write_text(
        "".join(f"{line}\n" for line in checksum_lines(stage)), encoding="utf-8"
    )

    archive = out / f"{name}.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        tar.add(stage, arcname=stage.name)
    checksum_file = out / f"{archive.name}.sha256"
    checksum_file.write_text(
        f"{_sha256_file(archive)}  {archive.name}\n", encoding="utf-8"
    )
    return archive


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build a self-contained DAP archive")
    parser.add_argument("--jar", default="engine-native/target/dap-native.jar")
    parser.add_argument("--version", help="version tag (default: VERSION)")
    parser.add_argument("--out", default="build/package", help="output directory")
    parser.add_argument("--skip-runtime", action="store_true", help="do not bundle jlink JRE")
    parser.add_argument("--skip-python", action="store_true", help="do not bundle Python")
    parser.add_argument("--python-runtime", default="build/tools/python",
                        help="prepared Python runtime directory")
    parser.add_argument("--keep", action="store_true",
                        help="reuse the existing staging directory instead of recreating it")
    parser.add_argument("--os", default=os.uname().sysname, help=argparse.SUPPRESS)
    parser.add_argument("--arch", default=os.uname().machine, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if os.environ.get("DAP_BUNDLE_PYTHON") == "0" and not args.skip_python:
        print("package: WARN DAP_BUNDLE_PYTHON=0, using system Python", file=sys.stderr)
        args.skip_python = True
    print(build(args))
    return 0


if __name__ == "__main__":
    sys.exit(main())
