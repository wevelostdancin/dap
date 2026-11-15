#!/usr/bin/env python3
"""Regenerate the SQL database fixtures used by the dap.db_reader tests and goldens.

- fixture.db     SQLite database (Python stdlib sqlite3, always available)
- fixture.duckdb DuckDB database (needs the built engine jar: make build-native)

Both files hold the same logical table, so the sqlite and duckdb drivers of
dap.db_reader can be checked against the same expected rows:

    items(id INTEGER, name TEXT, score REAL, created TEXT)
      1 alpha 1.5 2026-01-01
      2 beta  2.5 2026-01-02
      3 gamma NULL 2026-01-03

Usage: python3 tests/goldens-native/data/make_db_fixtures.py
"""

from __future__ import annotations

import os
import pathlib
import shutil
import sqlite3
import subprocess
import sys

DATA = pathlib.Path(__file__).resolve().parent
ROOT = DATA.parent.parent.parent
JAR = ROOT / "engine-native" / "target" / "dap-native.jar"
ROWS = [(1, "alpha", 1.5, "2026-01-01"),
        (2, "beta", 2.5, "2026-01-02"),
        (3, "gamma", None, "2026-01-03")]


def make_sqlite() -> pathlib.Path:
    path = DATA / "fixture.db"
    if path.exists():
        path.unlink()
    connection = sqlite3.connect(path)
    try:
        connection.execute("CREATE TABLE items (id INTEGER, name TEXT, score REAL, created TEXT)")
        connection.executemany("INSERT INTO items VALUES (?, ?, ?, ?)", ROWS)
        connection.commit()
    finally:
        connection.close()
    return path


def java_binary() -> str | None:
    home = (os.environ.get("DAP_JDK") or os.environ.get("JAVA_HOME")
            or str(pathlib.Path.home() / "Projects/tools/jdk-21.0.13+9/Contents/Home"))
    candidate = pathlib.Path(home) / "bin" / "java"
    return str(candidate) if candidate.is_file() else None


def make_duckdb() -> pathlib.Path | None:
    path = DATA / "fixture.duckdb"
    if path.exists():
        path.unlink()
    java = java_binary()
    if not JAR.is_file() or java is None:
        print("skip fixture.duckdb: run make build-native first", file=sys.stderr)
        return None
    subprocess.check_call([java, "-cp", str(JAR),
                           str(DATA / "DuckDbFixture.java"), str(path)])
    return path


def main() -> int:
    print("wrote", make_sqlite())
    duckdb = make_duckdb()
    if duckdb is not None:
        print("wrote", duckdb)
    return 0


if __name__ == "__main__":
    sys.exit(main())
