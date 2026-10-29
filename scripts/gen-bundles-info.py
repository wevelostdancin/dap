#!/usr/bin/env python3
"""Генерирует bundles.info + config.ini для vendored-рантайма DAP.

Рантайм — копия KNIME-платформы внутри DAP; установленный KNIME не нужен.
"""
import os
import sys
import zipfile

ROOT = os.path.expanduser(os.environ.get("DAP_ROOT", "~/Projects/dap"))
RUNTIME = os.path.expanduser(os.environ.get("DAP_RUNTIME", os.path.join(ROOT, "runtime", "knime")))
PLUGINS = os.path.join(RUNTIME, "plugins")
START_BUNDLES = {
    "com.dap.engine",
    "org.apache.felix.scr",
    "org.eclipse.core.runtime",
    "org.eclipse.equinox.common",
    "org.eclipse.equinox.event",
    "org.eclipse.equinox.p2.reconciler.dropins",
    "org.eclipse.equinox.simpleconfigurator",
    "org.eclipse.osgi",
}


def read_manifest(path: str) -> str | None:
    if os.path.isdir(path):
        mf = os.path.join(path, "META-INF", "MANIFEST.MF")
        if os.path.isfile(mf):
            with open(mf, encoding="utf-8", errors="replace") as fh:
                return fh.read()
        return None
    if path.endswith(".jar"):
        try:
            with zipfile.ZipFile(path) as z:
                return z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        except KeyError:
            return None
    return None


def parse_headers(text: str) -> dict[str, str]:
    lines = text.replace("\r\n", "\n").split("\n")
    headers: dict[str, str] = {}
    key = None
    for line in lines:
        if line.startswith(" ") and key:
            headers[key] += line[1:]
            continue
        if not line.strip():
            continue
        k, _, v = line.partition(":")
        key = k.strip()
        headers[key] = v.strip()
    return headers


def main() -> int:
    entries = []
    for name in sorted(os.listdir(PLUGINS)):
        path = os.path.join(PLUGINS, name)
        mf = read_manifest(path)
        if not mf:
            print(f"skip (no manifest): {name}", file=sys.stderr)
            continue
        h = parse_headers(mf)
        bsn = h.get("Bundle-SymbolicName", "").split(";")[0].strip()
        ver = h.get("Bundle-Version", "0.0.0").strip()
        if not bsn:
            print(f"skip (no BSN): {name}", file=sys.stderr)
            continue
        fragment = h.get("Fragment-Host") is not None
        started = bsn in START_BUNDLES
        entries.append((bsn, ver, path, 4, started, fragment))

    conf_dir = os.path.join(RUNTIME, "configuration")
    os.makedirs(conf_dir, exist_ok=True)
    with open(os.path.join(conf_dir, "bundles.info"), "w", encoding="utf-8") as fh:
        fh.write("#encoding=UTF-8\n#version=1\n")
        for bsn, ver, loc, lvl, started, _frag in entries:
            fh.write(f"{bsn},{ver},{loc},{lvl},{'true' if started else 'false'}\n")

    simple = next((e for e in entries if e[0] == "org.eclipse.equinox.simpleconfigurator"), None)
    framework = next((e for e in entries if e[0] == "org.eclipse.osgi"), None)
    compat = next((e for e in entries if e[0] == "org.eclipse.osgi.compatibility.state"), None)
    if not simple or not framework:
        print("ERROR: need simpleconfigurator and org.eclipse.osgi bundles", file=sys.stderr)
        return 1

    ini = [
        "osgi.framework=file:" + framework[2],
        "osgi.bundles=reference:file:" + simple[2] + "@1:start",
        "osgi.bundles.defaultStartLevel=4",
        "org.eclipse.equinox.simpleconfigurator.configUrl=file:" + os.path.join(conf_dir, "bundles.info"),
        "eclipse.ignoreApp=false",
        "osgi.noShutdown=false",
        "osgi.clean=true",
    ]
    if compat:
        ini.insert(1, "osgi.framework.extensions=reference:file:" + compat[2])
    with open(os.path.join(conf_dir, "config.ini"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(ini) + "\n")
    print(f"bundles.info: {len(entries)} bundles")
    print(f"config.ini: framework={framework[0]}, simpleconfigurator={simple[0]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
