#!/usr/bin/env python3
"""MCP-адаптер DAP: stdio, JSON-RPC 2.0, только стандартная библиотека.

Тонкий транспорт над HTTP-движком DAP (/v1). Все вычисления — в движке;
адаптер ищет узлы, собирает workflow, запускает задания и читает таблицы.

Если движок не отвечает на /v1/health, адаптер один раз запускает
"java -Xmx${DAP_HEAP:-2g} -jar engine-native/target/dap-native.jar" и ждёт health
до 60 с. Запущенный дочерний процесс опрашивается: если он умер или health не
наступил, адаптер гасит его и возвращает ошибку с хвостом service.log.
Уже запущенный сервис не перезапускается и не останавливается; dap_shutdown
останавливает движок явно.

Переменные окружения: DAP_HOME, DAP_PORT, DAP_SESSION, DAP_BIND, DAP_HEAP.

Транспорт: по одному JSON-объекту на строку в stdin, ответы — строки в stdout.
Логи и трассировки — только в stderr, чтобы не ломать поток протокола.
"""

from __future__ import annotations

import importlib.util
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

# ---------------------------------------------------------------- конфигурация

DAP_HOME = os.path.expanduser(os.environ.get("DAP_HOME") or "~/Projects/dap")
PORT = int(os.environ.get("DAP_PORT") or "7800")
BIND = os.environ.get("DAP_BIND") or "127.0.0.1"
HOST = "127.0.0.1" if BIND in ("0.0.0.0", "::", "[::]") else BIND
BASE = f"http://{HOST}:{PORT}"
SESSION = (os.environ.get("DAP_SESSION") or "default").strip() or "default"
LOG = os.path.join(DAP_HOME, "service.log")
TIMEOUT = 600
WARMUP_WAIT = 15.0
START_WAIT = 60
NATIVE_JAR = os.path.join(DAP_HOME, "engine-native", "target", "dap-native.jar")
HEAP = (os.environ.get("DAP_HEAP") or "").strip() or "2g"
_CHILD: subprocess.Popen | None = None
# Boot generation of the engine we talked to last, and a pending restart notice.
_GENERATION: str | None = None
_RESTART_PENDING = False
# A stale engine is replaced at most once per adapter process: if the jar on
# disk still reports another version, the adapter attaches and warns instead of
# killing the engine on every tool call. The warning is printed once as well.
_REPLACED = False
_WARNED_STALE = False
# progressToken of the tools/call in flight (params._meta.progressToken), if any.
_PROGRESS_TOKEN = None
# How often dap_run polls GET /v1/jobs/{id} while it forwards job progress.
PROGRESS_POLL = 0.2

# --------------------------------------------------------------- sibling modules

HERE = os.path.dirname(os.path.abspath(__file__))


def _load_sibling(name: str, filename: str):
    """Load a sibling module by path, so the adapter runs as a script and as a module."""
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, filename))
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {filename}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RESOURCES = _load_sibling("dap_mcp_resources", "resources.py")
RECIPES = _load_sibling("dap_mcp_recipes", "recipes.py")


class DapError(Exception):
    """Ошибка обращения к DAP; превращается в JSON-RPC error."""

    def __init__(self, message: str, code: int = -32000, data=None) -> None:
        super().__init__(message)
        self.code = code
        self.data = data


# --------------------------------------------------------------------- сервис

def probe() -> dict | None:
    """GET /v1/health: parsed body, or None only when nothing answers on the port.

    A peer that answers with a non-200 status or an unparsable body is a broken
    engine, not an absent one: raise DapError(data.status) so ensure_service
    never launches a second jar on an occupied port.
    """
    try:
        with urllib.request.urlopen(f"{BASE}/v1/health", timeout=2) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:400]
        raise DapError(
            f"DAP health отвечает HTTP {e.code}: {detail}",
            data={"status": e.code, "body": detail, "base": BASE},
        ) from e
    except (urllib.error.URLError, TimeoutError, OSError):
        return None
    if status != 200:
        detail = raw.decode("utf-8", errors="replace")[:400]
        raise DapError(f"DAP health отвечает HTTP {status}: {detail}",
                       data={"status": status, "body": detail, "base": BASE})
    try:
        return json.loads(raw.decode("utf-8")) if raw else {}
    except ValueError as e:
        detail = raw.decode("utf-8", errors="replace")[:400]
        raise DapError(
            f"DAP health вернул битый JSON (HTTP {status}): {detail!r}",
            data={"status": status, "body": detail, "base": BASE},
        ) from e


def _port_busy() -> bool:
    """True when something already listens on DAP_PORT, even if /health fails."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(0.5)
        return sock.connect_ex((HOST, PORT)) == 0


def health() -> bool:
    """True when /v1/health answers 200; also records the boot generation."""
    body = probe()
    if body is None:
        return False
    _note_generation(body.get("generation"))
    return True


def _note_generation(generation) -> None:
    """Remember the engine boot id; a change means the engine restarted under us."""
    global _GENERATION, _RESTART_PENDING
    if not generation:
        return
    if _GENERATION is not None and generation != _GENERATION:
        _RESTART_PENDING = True
    _GENERATION = generation


def _restart_note() -> str:
    """Prefix for the next engine error after a restart; clears the pending note."""
    global _RESTART_PENDING
    if not _RESTART_PENDING:
        return ""
    _RESTART_PENDING = False
    return "engine restarted (idle shutdown or crash); workflows lost: "


def _restart_data(note: str, data):
    """Mark the error envelope as a restart without dropping existing fields."""
    if not note:
        return data
    result = dict(data) if isinstance(data, dict) else {}
    result["restarted"] = True
    return result


VERSION_FILE = os.path.join(DAP_HOME, "VERSION")
STOP_WAIT = 15.0
JAVA_CHECK_TIMEOUT = 15


def expected_version() -> str | None:
    """Версия сборки на диске (DAP_HOME/VERSION); None, если файла нет."""
    try:
        with open(VERSION_FILE, "r", encoding="utf-8") as stream:
            return stream.read().strip() or None
    except OSError:
        return None


def _allow_stale_engine() -> bool:
    """DAP_ALLOW_STALE_ENGINE=1 — осознанно подключиться к движку другой сборки."""
    return (os.environ.get("DAP_ALLOW_STALE_ENGINE") or "").strip().lower() in (
        "1", "true", "yes", "on")


def _version_tuple(text: str) -> tuple:
    """1.2.0 -> (1, 2, 0); нечисловые хвосты игнорируются."""
    parts = []
    for chunk in str(text).split("."):
        digits = "".join(ch for ch in chunk if ch.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


def _stale_reason(body: dict) -> str | None:
    """Почему отвечающий движок — не сборка с диска; None, если это она или понять нельзя."""
    running = str(body.get("version") or "").strip()
    expected = expected_version()
    if not running or not expected or running == expected:
        return None
    if _version_tuple(running) > _version_tuple(expected):
        # Движок новее файла VERSION: заменять нечем, jar на диске старше.
        return None
    return f"engine {running} is running, build on disk is {expected}"


def _stop_stale_engine(body: dict, reason: str) -> None:
    """Погасить движок старой сборки, чтобы ensure_service поднял текущий jar."""
    print(f"dap-mcp: replacing stale {reason}", file=sys.stderr, flush=True)
    try:
        with open(LOG, "ab") as log:
            log.write(f"dap-mcp: replacing stale {reason}\n".encode("utf-8"))
    except OSError:
        pass
    try:
        call("POST", "/v1/shutdown", timeout=10)
    except DapError:
        pass
    deadline = time.monotonic() + STOP_WAIT
    while time.monotonic() < deadline:
        if probe() is None:
            return
        time.sleep(0.2)
    raise DapError(
        f"движок {body.get('version')} не остановился за {STOP_WAIT:g} с; "
        f"остановите процесс на {BASE} или задайте DAP_ALLOW_STALE_ENGINE=1",
        code=-32001,
        data={"base": BASE, "version": body.get("version"), "expected": expected_version()},
    )


def _java_blocker(java: str) -> str | None:
    """Почему java не запускается; None, если запускается."""
    try:
        done = subprocess.run([java, "-version"], capture_output=True,
                              timeout=JAVA_CHECK_TIMEOUT)
    except (OSError, subprocess.SubprocessError) as exc:
        return f"java не запускается ({java}): {exc}"
    if done.returncode == 0:
        return None
    raw = done.stderr or done.stdout or b""
    lines = raw.decode("utf-8", "replace").strip().splitlines()
    detail = lines[0] if lines else f"exit code {done.returncode}"
    return f"java не запускается ({java}): {detail}"


def _launch_blocker() -> str | None:
    """Почему текущую сборку нельзя запустить; None, если можно.

    Проверяется до гашения движка старой сборки: иначе он уже остановлен, а
    поднять вместо него нечего и порт остаётся пустым.
    """
    if not os.path.isfile(NATIVE_JAR):
        return (f"нет jar {NATIVE_JAR}; соберите: cd engine-native && "
                "./mvnw -DskipTests package")
    return _java_blocker(_java())


def _java() -> str:
    """JAVA_HOME/bin/java, если он есть, иначе java из PATH."""
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", "java")
        if os.path.isfile(candidate):
            return candidate
    return "java"


def _launch_native(env: dict, log) -> subprocess.Popen:
    cmd = [_java(), f"-Xmx{HEAP}", "-jar", NATIVE_JAR]
    try:
        return subprocess.Popen(
            cmd,
            cwd=DAP_HOME,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=log,
            start_new_session=True,
            env=env,
        )
    except OSError as e:
        raise DapError(
            f"не удалось запустить нативный движок ({cmd[0]}): {e}",
            code=-32001,
            data={"base": BASE, "jar": NATIVE_JAR},
        )


def _log_tail(limit: int = 4000) -> str:
    """Последние строки service.log — без исключений, лог может быть пустым."""
    try:
        with open(LOG, "rb") as stream:
            stream.seek(0, os.SEEK_END)
            size = stream.tell()
            stream.seek(max(0, size - limit))
            return stream.read().decode("utf-8", errors="replace").strip()
    except OSError:
        return ""


def _startup_error(message: str, data: dict) -> DapError:
    tail = _log_tail()
    if tail:
        message = f"{message}; хвост {LOG}:\n{tail}"
        data = dict(data, tail=tail)
    return DapError(message, code=-32001, data=data)


def _stop_child(proc: subprocess.Popen) -> None:
    """Погасить дочерний процесс: terminate, затем kill; ошибки не пробрасываем."""
    if proc is None or proc.poll() is not None:
        return
    try:
        proc.terminate()
    except OSError:
        return
    try:
        proc.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        proc.kill()
    except OSError:
        return
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def _wait_for_warmup() -> None:
    """A first call should not race the startup warm-up: wait until it stops running."""
    deadline = time.monotonic() + WARMUP_WAIT
    while True:
        body = probe()
        if body is None:
            return
        if body.get("warmup") not in ("pending", "running"):
            return
        if time.monotonic() >= deadline:
            print(f"dap-mcp: warm-up still running after {WARMUP_WAIT:g}s; continuing",
                  file=sys.stderr, flush=True)
            return
        time.sleep(0.05)


def ensure_service() -> None:
    """Поднять движок, если он ещё не запущен; движок старой сборки заменить один раз."""
    global _REPLACED, _WARNED_STALE
    body = probe()
    if body is not None:
        reason = _stale_reason(body)
        if reason is None or _allow_stale_engine():
            _note_generation(body.get("generation"))
            _wait_for_warmup()
            return
        if _REPLACED:
            if not _WARNED_STALE:
                _WARNED_STALE = True
                print(f"dap-mcp: {reason}; attaching anyway, already replaced once",
                      file=sys.stderr, flush=True)
            _note_generation(body.get("generation"))
            _wait_for_warmup()
            return
        blocker = _launch_blocker()
        if blocker is not None:
            raise DapError(
                f"{reason}, но заменить его нечем: {blocker.rstrip('.')}. "
                "Движок оставлен как есть; задайте JAVA_HOME (JDK 21) или "
                "DAP_ALLOW_STALE_ENGINE=1, чтобы подключиться к нему осознанно.",
                code=-32001,
                data={"base": BASE, "version": body.get("version"),
                      "expected": expected_version(), "blocker": blocker},
            )
        _stop_stale_engine(body, reason)
        _REPLACED = True
    if _port_busy():
        raise DapError(
            f"порт {HOST}:{PORT} занят, но /v1/health не отвечает; проверьте процесс "
            "на этом порту или задайте другой DAP_PORT",
            code=-32001, data={"base": BASE, "port": PORT},
        )
    _reap_child()
    env = dict(os.environ)
    env.update({"DAP_HOME": DAP_HOME, "DAP_PORT": str(PORT), "DAP_BIND": BIND})
    if not os.path.isfile(NATIVE_JAR):
        raise DapError(
            f"движок DAP не отвечает на {BASE}/v1/health, а {NATIVE_JAR} не найден; "
            "соберите jar: cd engine-native && ./mvnw -DskipTests package",
            code=-32001,
            data={"base": BASE, "jar": NATIVE_JAR, "dapHome": DAP_HOME},
        )
    with open(LOG, "ab") as log:
        child = _launch_native(env, log)
        try:
            print(f"dap-mcp: engine pid={child.pid}", file=sys.stderr, flush=True)
        except OSError:
            pass
    try:
        deadline = time.monotonic() + START_WAIT
        while time.monotonic() < deadline:
            if health():
                global _CHILD
                _CHILD = child
                _wait_for_warmup()
                return
            code = child.poll()
            if code is not None:
                raise _startup_error(
                    f"движок DAP завершился при запуске (код выхода {code})",
                    {"base": BASE, "log": LOG, "exitCode": code},
                )
            time.sleep(1)
        raise _startup_error(
            f"движок DAP не поднялся за {START_WAIT:g} с",
            {"base": BASE, "log": LOG},
        )
    except BaseException:
        _stop_child(child)
        raise


def call(method: str, path: str, body=None, timeout: int = TIMEOUT):
    """HTTP-запрос к /v1. Ошибки движка — DapError с телом ответа."""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"{BASE}{path}", data=data, method=method)
    req.add_header("X-DAP-Session", SESSION)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return json.loads(raw.decode("utf-8")) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"error": raw or f"HTTP {e.code}"}
        message = payload.get("error") if isinstance(payload, dict) else None
        if not message and isinstance(payload, dict) and payload:
            message = json.dumps(payload, ensure_ascii=False)[:400]
        data = {"status": e.code, "body": payload}
        # Surface the engine's machine-readable envelope at the top of error.data;
        # the raw body stays for clients that already read data.body.
        if isinstance(payload, dict):
            if payload.get("code"):
                data["code"] = payload["code"]
            if payload.get("hint"):
                data["hint"] = payload["hint"]
        raise DapError(str(message or f"HTTP {e.code}"), data=data)
    except urllib.error.URLError as e:
        raise DapError(
            f"DAP недоступен по {BASE}: {e.reason}",
            code=-32001,
            data={"base": BASE},
        )


# ---------------------------------------------------------------------- схемы

def _obj(props: dict, required=None) -> dict:
    schema = {"type": "object", "properties": props}
    if required:
        schema["required"] = list(required)
    return schema


def _str(desc: str) -> dict:
    return {"type": "string", "description": desc}


def _int(desc: str, default=None) -> dict:
    prop = {"type": "integer", "description": desc}
    if default is not None:
        prop["default"] = default
    return prop


def _obj_prop(desc: str) -> dict:
    return {"type": "object", "description": desc}


_WF = "идентификатор workflow (выдаёт dap_workflow_create, вид wf-1)"
_NODE = "идентификатор узла внутри workflow (выдаёт dap_add_node, вид 0:1)"
_SETTINGS = "плоский объект настроек по configKey из dap_node_dialog_schema"
_TABLE_FORMATS = ("json", "objects", "csv", "markdown")
_DATASET_ACTIONS = ("list", "add", "show", "refresh", "remove")
# Catalog dataset names: [A-Za-z0-9._-]{1,64} (the engine enforces the same rule).
_CATALOG_NAME = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

# Compact-table arguments shared by every tool that returns a table. Cells are
# capped by default (200 chars) so a single huge value cannot flood the context.
_COMPACT_TABLE_PROPS = {
    "tableFormat": {
        "type": "string", "enum": list(_TABLE_FORMATS), "default": "json",
        "description": (
            "формат таблицы: json (по умолчанию), objects, csv или markdown; "
            "не путать с форматом входа/отчёта в аргументе format"
        ),
    },
    "columns": {
        "type": ["array", "string"], "items": {"type": "string"},
        "description": (
            "проекция: имена колонок массивом или строкой через запятую; "
            "применяется ко всем возвращаемым таблицам"
        ),
    },
    "maxCellChars": _int("обрезать значение ячейки до N символов с многоточием", 200),
    "includeColumns": {
        "type": "boolean", "default": True,
        "description": "false — не возвращать список колонок (только данные)",
    },
}

# CSV/Excel read options shared by every one-call tool that can ingest a file.
_CSV_PROPS = {
    "header": {"type": "boolean", "description": "CSV: первая строка — заголовок (по умолчанию true)"},
    "delimiter": _str("CSV: разделитель; по умолчанию автоопределение (, ; TAB |)"),
    "encoding": _str("CSV: кодировка — auto (по умолчанию), utf-8, utf-16, latin-1, cp1251, cp866, koi8-r"),
    "allVarchar": {"type": "boolean", "description": "CSV: все колонки как строки"},
    "decimalComma": {"type": "boolean", "description": "CSV: «5 449,72» -> число (пробелы/NBSP убрать, запятую -> точку)"},
    "sheet": _str("Excel: имя листа или 0-based индекс; по умолчанию первый лист"),
}


TOOLS = [
    {
        "name": "dap_search_nodes",
        "description": "Поиск узлов DAP по имени или категории. Возвращает id, имя, категорию, тип, tier и порты.",
        "inputSchema": _obj(
            {
                "query": _str("подстрока имени узла, например 'reader' или 'filter'"),
                "category": _str("категория каталога, например /io/read"),
                "limit": _int("сколько узлов вернуть (по умолчанию — весь каталог)", 200),
            }
        ),
    },
    {
        "name": "dap_node_details",
        "description": "Полное описание узла из каталога: порты, тип, tier, список configKey и XML-схема настроек.",
        "inputSchema": _obj({"id": _str("id узла из каталога (dap_search_nodes)")}, ["id"]),
    },
    {
        "name": "dap_node_dialog_schema",
        "description": (
            "Схема настроек узла: подписи полей, типы, умолчания, configKey и допустимые значения. "
            "Если переданы workflow и node, варианты считаются по реальным входным таблицам этого узла."
        ),
        "inputSchema": _obj(
            {
                "id": _str("id узла из каталога"),
                "workflow": _str("опционально: workflow для динамических вариантов"),
                "node": _str("опционально: узел внутри workflow для динамических вариантов"),
            },
            ["id"],
        ),
    },
    {
        "name": "dap_workflow_create",
        "description": "Создать пустой workflow в текущей сессии. Возвращает id, имя и сессию.",
        "inputSchema": _obj({"name": _str("имя workflow, по умолчанию workflow-N")}),
    },
    {
        "name": "dap_list_workflows",
        "description": "Список workflow текущей сессии с числом узлов.",
        "inputSchema": _obj({}),
    },
    {
        "name": "dap_delete_workflow",
        "description": "Удалить workflow и освободить его ресурсы.",
        "inputSchema": _obj({"workflow": _str(_WF)}, ["workflow"]),
    },
    {
        "name": "dap_add_node",
        "description": (
            "Добавить узел из каталога в workflow. Возвращает идентификатор узла (например 0:1); "
            "настройки передаются сразу либо позже через dap_configure_node."
        ),
        "inputSchema": _obj(
            {
                "workflow": _str(_WF),
                "factory": _str("id узла из каталога (dap_search_nodes)"),
                "settings": _obj_prop(_SETTINGS),
            },
            ["workflow", "factory"],
        ),
    },
    {
        "name": "dap_configure_node",
        "description": "Изменить настройки узла: переданные ключи накладываются поверх текущих.",
        "inputSchema": _obj(
            {"workflow": _str(_WF), "node": _str(_NODE), "settings": _obj_prop(_SETTINGS)},
            ["workflow", "node", "settings"],
        ),
    },
    {
        "name": "dap_connect",
        "description": (
            "Соединить выходной порт узла с входным портом другого узла. "
            "Индексы портов данных, с 0 (скрытый порт переменных движок учитывает сам)."
        ),
        "inputSchema": _obj(
            {
                "workflow": _str(_WF),
                "from": _str("узел-источник"),
                "fromPort": _int("индекс выходного порта данных", 0),
                "to": _str("узел-приёмник"),
                "toPort": _int("индекс входного порта данных", 0),
            },
            ["workflow", "from", "to"],
        ),
    },
    {
        "name": "dap_disconnect",
        "description": "Разорвать соединение между выходным и входным портом данных.",
        "inputSchema": _obj(
            {
                "workflow": _str(_WF),
                "from": _str("узел-источник"),
                "fromPort": _int("индекс выходного порта данных", 0),
                "to": _str("узел-приёмник"),
                "toPort": _int("индекс входного порта данных", 0),
            },
            ["workflow", "from", "to"],
        ),
    },
    {
        "name": "dap_remove_node",
        "description": "Удалить узел из workflow. Узел с входящими соединениями движок удалять не даёт.",
        "inputSchema": _obj({"workflow": _str(_WF), "node": _str(_NODE)}, ["workflow", "node"]),
    },
    {
        "name": "dap_run",
        "description": (
            "Выполнить workflow синхронно и дождаться конца. Возвращает ok, durationMs "
            "и состояние каждого узла с сообщениями об ошибках."
        ),
        "inputSchema": _obj(
            {"workflow": _str(_WF), "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка")},
            ["workflow"],
        ),
    },
    {
        "name": "dap_run_async",
        "description": (
            "Запустить workflow асинхронно (HTTP 202). Возвращает id задания, состояние и statusUrl; "
            "дальше — dap_job_status / dap_job_cancel."
        ),
        "inputSchema": _obj(
            {"workflow": _str(_WF), "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка")},
            ["workflow"],
        ),
    },
    {
        "name": "dap_job_status",
        "description": (
            "Состояние задания: queued, running, succeeded, failed, cancelled или timeout. "
            "Для завершённого задания возвращает result с состояниями узлов."
        ),
        "inputSchema": _obj({"job": _str("идентификатор задания, вид job-1")}, ["job"]),
    },
    {
        "name": "dap_job_cancel",
        "description": "Отменить активное задание. Возвращает ok и текущее состояние задания.",
        "inputSchema": _obj({"job": _str("идентификатор задания")}, ["job"]),
    },
    {
        "name": "dap_list_jobs",
        "description": "Список заданий текущей сессии, новые сверху.",
        "inputSchema": _obj({}),
    },
    {
        "name": "dap_get_table",
        "description": (
            "Прочитать выходную таблицу узла: колонки с типами, число строк и строки. "
            "Пагинация (offset/nextOffset/truncated), проекция (columns), фильтр (where) "
            "и компактные форматы (objects/csv/markdown) экономят контекст агента."
        ),
        "inputSchema": _obj(
            {
                "workflow": _str(_WF),
                "node": _str(_NODE),
                "port": _int("индекс выходного порта", 0),
                "limit": _int("сколько строк вернуть", 20),
                "offset": _int("пропустить N строк после фильтра (>= 0)", 0),
                "columns": {
                    "type": ["array", "string"],
                    "items": {"type": "string"},
                    "description": "проекция: имена колонок массивом или строкой через запятую",
                },
                "where": _str(
                    "SQL-предикат над выходными колонками, например \"total > 10\"; "
                    "запрещены ';', '--', '/*', SELECT, FROM и read_*"
                ),
                "format": {
                    "type": "string",
                    "enum": list(_TABLE_FORMATS),
                    "description": "json (по умолчанию), objects (строки-объекты), csv, markdown",
                    "default": "json",
                },
                "maxCellChars": _int(
                    "обрезать значение ячейки до N символов с многоточием", 200),
                "includeColumns": {
                    "type": "boolean",
                    "description": "вернуть список колонок (false — только data)",
                    "default": True,
                },
            },
            ["workflow", "node"],
        ),
    },
    {
        "name": "dap_profile_table",
        "description": (
            "Профиль выходной таблицы узла: строки, пропуски и уникальные значения по каждой колонке, "
            "min/max/mean по числовым."
        ),
        "inputSchema": _obj({"workflow": _str(_WF), "node": _str(_NODE)}, ["workflow", "node"]),
    },
    {
        "name": "dap_ingest",
        "description": (
            "Один раз прочитать CSV/Excel/JSON/Parquet и сохранить в Parquet-кэш. "
            "Повторный вызов для неизменённого файла возвращает тот же путь без перезаписи "
            "(cached=true). Возвращает path, rows, columns и cached. "
            "Поддерживаются кодировки CSV (auto, utf-8, utf-16, latin-1, cp1251, cp866, koi8-r) "
            "и выбор листа Excel."
        ),
        "inputSchema": _obj(
            {
                "file": _str("путь к файлу CSV, XLSX, JSON или Parquet"),
                "format": _str("csv, excel, json или parquet; по умолчанию по расширению"),
                **_CSV_PROPS,
                "quote": _str("CSV: символ кавычки, по умолчанию двойная кавычка"),
                "escape": _str("CSV: символ escape, по умолчанию двойная кавычка"),
                "ignoreErrors": {"type": "boolean", "description": "CSV: пропускать битые строки"},
                "nullPadding": {"type": "boolean", "description": "CSV: дополнять короткие строки NULL"},
                "strictMode": {"type": "boolean", "description": "CSV: строгий разбор (по умолчанию true)"},
                "nullStrings": {"type": "array", "items": {"type": "string"}, "description": "CSV: строки, которые считать NULL, например ['NA']"},
            },
            ["file"],
        ),
    },
    {
        "name": "dap_dataset",
        "description": (
            "Каталог датасетов: list (по умолчанию), add (file или dataset + описание), "
            "show, refresh (переингест и version+1) и remove. Имя из каталога можно "
            "передавать как 'dataset' в dap_analyze/dap_profile/dap_report/dap_explore."
        ),
        "inputSchema": _obj(
            {
                "action": {
                    "type": "string",
                    "enum": list(_DATASET_ACTIONS),
                    "description": "list, add, show, refresh или remove",
                    "default": "list",
                },
                "name": _str("имя датасета: [A-Za-z0-9._-]{1,64} (кроме list)"),
                "file": _str("путь к CSV/Excel/JSON/Parquet для add"),
                "dataset": _str("путь к готовому Parquet-датасету для add"),
                "format": _str("формат входного файла для add: csv, excel, json или parquet"),
                "description": _str("описание датасета"),
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "теги для поиска в каталоге",
                },
                "owner": _str("владелец или команда"),
            },
        ),
    },
    {
        "name": "dap_save_workflow",
        "description": "Сохранить workflow в каталог на диске (файл workflow.json).",
        "inputSchema": _obj(
            {"workflow": _str(_WF), "dir": _str("каталог назначения на диске")},
            ["workflow", "dir"],
        ),
    },
    {
        "name": "dap_load_workflow",
        "description": "Создать workflow из сохранённого каталога (workflow.json).",
        "inputSchema": _obj(
            {"dir": _str("каталог с workflow.json"), "name": _str("необязательное имя")},
            ["dir"],
        ),
    },
    {
        "name": "dap_recipe_list",
        "description": (
            "Список сохранённых рецептов из $DAP_HOME/recipes: имя, описание, теги, "
            "объявленные параметры и признак параметра dataset."
        ),
        "inputSchema": _obj({}),
    },
    {
        "name": "dap_recipe_save",
        "description": (
            "Сохранить пайплайн как рецепт: workflow JSON (dap-native-workflow/1) или id "
            "workflow движка пишется в $DAP_HOME/recipes/<name>.json с блоком recipe. "
            "Имя не может содержать разделители пути."
        ),
        "inputSchema": _obj(
            {
                "name": _str("имя рецепта (файл <name>.json в библиотеке; без разделителей пути)"),
                "workflow": {
                    "type": ["object", "string"],
                    "description": (
                        "workflow JSON (dap-native-workflow/1) или id workflow, "
                        "сохранённого в движке"
                    ),
                },
                "description": _str("описание для библиотеки рецептов"),
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "теги для поиска в библиотеке",
                },
            },
            ["name", "workflow"],
        ),
    },
    {
        "name": "dap_recipe_run",
        "description": (
            "Загрузить сохранённый рецепт, применить dataset и params к настройкам узлов, "
            "выполнить workflow и вернуть таблицу выходного узла. Сам рецепт не меняется."
        ),
        "inputSchema": _obj(
            {
                "name": _str("имя сохранённого рецепта (dap_recipe_list)"),
                "dataset": _str(
                    "путь к файлу данных: привязывается к reader-узлу рецепта "
                    "(recipe.dataset или первый reader с настройкой file)"
                ),
                "params": _obj_prop("переопределения настроек: имя параметра -> значение"),
                "limit": _int("сколько строк таблицы вернуть", 20),
                **_COMPACT_TABLE_PROPS,
                "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка"),
            },
            ["name"],
        ),
    },
    {
        "name": "dap_sql",
        "description": (
            "Добавить узел dap.sql_query в workflow, выполнить его и вернуть таблицу. "
            "Принимает один read-only SELECT/WITH ('sql') или батч 'queries' — по узлу на "
            "запрос, один запуск и таблица на запрос. С 'explain' узел не выполняется: "
            "возвращается оценка /estimate (rows, operators, plan). DDL/DML и несколько "
            "стейтментов отклоняются до обращения к движку. Если передан node, его таблица "
            "доступна в SQL под именем node id (в кавычках, например \"1:1\")."
        ),
        "inputSchema": _obj(
            {
                "workflow": _str(_WF),
                "node": _str("опционально: узел-источник, чья таблица доступна в SQL"),
                "sql": _str("один SELECT или WITH; DDL/DML и несколько стейтментов запрещены"),
                "queries": {
                    "type": "array",
                    "description": "батч запросов: по узлу dap.sql_query на элемент",
                    "items": _obj(
                        {
                            "sql": _str("один read-only SELECT или WITH"),
                            "limit": _int("cap строк этого узла", 20),
                            "node": _str("опционально: узел-источник для этого запроса"),
                        },
                        ["sql"],
                    ),
                },
                "explain": {
                    "type": "boolean",
                    "description": "не выполнять: вернуть оценку /estimate построенного узла",
                    "default": False,
                },
                "limit": _int("сколько строк таблицы вернуть", 20),
                **_COMPACT_TABLE_PROPS,
                "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка"),
            },
            ["workflow"],
        ),
    },
    {
        "name": "dap_workflow_build",
        "description": (
            "Собрать workflow одним вызовом: узлы, связи, запуск и таблицы выходов. "
            "Заменяет последовательность create/add_node/connect/run/get_table. "
            "Ключи узлов (key) используются в edges и outputs; без key — индекс в nodes."
        ),
        "inputSchema": _obj(
            {
                "name": _str("имя workflow"),
                "nodes": {
                    "type": "array",
                    "description": "узлы: key?, factory, settings?",
                    "items": _obj(
                        {
                            "key": _str("локальный ключ узла для edges/outputs"),
                            "factory": _str("id узла каталога, например dap.csv_reader"),
                            "settings": _obj_prop("настройки узла"),
                        },
                        ["factory"],
                    ),
                },
                "edges": {
                    "type": "array",
                    "description": "связи между ключами узлов",
                    "items": _obj(
                        {
                            "from": _str("ключ источника"),
                            "to": _str("ключ приёмника"),
                            "fromPort": _int("порт источника", 0),
                            "toPort": _int("порт приёмника", 0),
                        },
                        ["from", "to"],
                    ),
                },
                "run": {"type": "boolean", "description": "запустить после сборки", "default": False},
                "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка"),
                "outputs": {
                    "type": "array",
                    "description": "ключи узлов, таблицы которых вернуть",
                    "items": {"type": "string"},
                },
                "limit": _int("строк на таблицу выхода", 20),
                **_COMPACT_TABLE_PROPS,
            },
            ["nodes"],
        ),
    },
    {
        "name": "dap_analyze",
        "description": (
            "Анализ файла или датасета одним вызовом: ingest (для file), чтение, "
            "опциональный SQL (таблица входа доступна как src), таблица и профиль. "
            "Временный workflow удаляется."
        ),
        "inputSchema": _obj(
            {
                "file": _str("путь к CSV/Excel/JSON/Parquet; конвертируется в Parquet-кэш"),
                "format": _str("формат входного файла: csv, excel, json или parquet; по умолчанию по расширению"),
                "dataset": _str(
                    "путь к готовому Parquet-датасету (из dap_ingest) или имя из "
                    "каталога датасетов (dap_dataset action=list)"),
                "sql": _str("read-only SELECT/WITH; вход доступен как src"),
                "profile": {"type": "boolean", "description": "вернуть профиль источника", "default": True},
                "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка"),
                "limit": _int("строк таблицы", 20),
                **_COMPACT_TABLE_PROPS,
                **_CSV_PROPS,
            },
        ),
    },
    {
        "name": "dap_explore",
        "description": (
            "Разведочный анализ одним вызовом: ingest (для file), POST /v1/explore — "
            "профиль, качество, связи, групповые различия, гипотезы и рекомендации. "
            "Возвращает source, cached и report."
        ),
        "inputSchema": _obj(
            {
                "file": _str("путь к CSV/Excel/JSON/Parquet; конвертируется в Parquet-кэш"),
                "dataset": _str(
                    "путь к готовому Parquet-датасету или имя из каталога датасетов"),
                "format": _str("формат входного файла: csv, excel, json или parquet"),
                "sql": _str("read-only SELECT/WITH; вход доступен как src"),
                "maxColumns": _int("сколько колонок профилировать (по умолчанию движка)"),
                "sampleRows": _int("сколько строк сэмплировать (по умолчанию движка)"),
                **_CSV_PROPS,
            },
        ),
    },
    {
        "name": "dap_estimate",
        "description": (
            "Оценить стоимость запроса до запуска: ingest (для file), временный workflow "
            "parquet_reader (+ sql_query при sql), GET /estimate. Возвращает rows, operators, "
            "plan и columns без материализации таблицы. Временный workflow удаляется."
        ),
        "inputSchema": _obj(
            {
                "file": _str("путь к CSV/Excel/JSON/Parquet; конвертируется в Parquet-кэш"),
                "dataset": _str(
                    "путь к готовому Parquet-датасету или имя из каталога датасетов"),
                "format": _str("формат входного файла: csv, excel, json или parquet"),
                "sql": _str("read-only SELECT/WITH; вход доступен как src"),
                "limit": _int("cap строк узла sql_query (как в dap_sql)"),
                **_CSV_PROPS,
            },
        ),
    },
    {
        "name": "dap_report",
        "description": (
            "Отчёт одним вызовом: ingest (для file), чтение, опциональный SQL (вход как src) "
            "или батч queries[] (таблица на свой вход отчёта), markdown_report в формате "
            "md/html/pdf/pptx и опциональный график. Возвращает таблицы, profile, пути "
            "report/chart и размеры bytes. Временный workflow удаляется."
        ),
        "inputSchema": _obj(
            {
                "file": _str("путь к CSV/Excel/JSON/Parquet; конвертируется в Parquet-кэш"),
                "format": _str(
                    "формат отчёта: md (по умолчанию), html, pdf или pptx; устаревшие "
                    "значения csv/excel/json/parquet трактуются как формат входа"
                ),
                "dataset": _str(
                    "путь к готовому Parquet-датасету (из dap_ingest) или имя из "
                    "каталога датасетов (dap_dataset action=list)"),
                "sql": _str("read-only SELECT/WITH; вход доступен как src"),
                "queries": {
                    "type": "array",
                    "description": (
                        "несколько таблиц: по узлу dap.sql_query на элемент, каждый "
                        "подключён к своему входу отчёта (sections input=i)"
                    ),
                    "items": _obj(
                        {
                            "sql": _str("один read-only SELECT или WITH"),
                            "title": _str("заголовок секции таблицы"),
                            "limit": _int("строк этой таблицы", 20),
                        },
                        ["sql"],
                    ),
                },
                **_CSV_PROPS,
                "report": _str("путь к файлу отчёта (обязательный)"),
                "chart": _str("путь к графику .svg/.png; без него график не строится"),
                "chartType": {
                    "type": "string",
                    "enum": ["bar", "line", "scatter", "histogram"],
                    "description": "тип графика",
                    "default": "bar",
                },
                "x": _str("колонка X: категория для bar, число для line/scatter/histogram"),
                "y": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "числовые колонки Y: bar — одна, line/scatter — одна или несколько, histogram — без Y",
                },
                "title": _str("заголовок отчёта и графика"),
                "profile": {
                    "type": "boolean",
                    "description": "добавить секцию профиля в отчёт и вернуть profile",
                    "default": True,
                },
                "limit": _int("строк таблицы и отчёта", 20),
                **_COMPACT_TABLE_PROPS,
                "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка"),
            },
            ["report"],
        ),
    },
    {
        "name": "dap_profile",
        "description": (
            "Компактный профиль файла или датасета одним вызовом: ingest (для file), чтение, "
            "профиль без таблицы. Временный workflow удаляется."
        ),
        "inputSchema": _obj(
            {
                "file": _str("путь к CSV/Excel/JSON/Parquet; конвертируется в Parquet-кэш"),
                "format": _str("формат входного файла: csv, excel, json или parquet; по умолчанию по расширению"),
                "dataset": _str(
                    "путь к готовому Parquet-датасету (из dap_ingest) или имя из "
                    "каталога датасетов (dap_dataset action=list)"),
                **_CSV_PROPS,
                "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка"),
            },
        ),
    },
    {
        "name": "dap_shutdown",
        "description": "Остановить движок DAP на этом порту. Следующий вызов инструмента поднимет его заново.",
        "inputSchema": _obj({}),
    },
]


# -------------------------------------------------------------------- вызовы

def _seg(value) -> str:
    return urllib.parse.quote(str(value), safe=":")


def _need(args: dict, key: str):
    value = args.get(key)
    if value is None or value == "":
        raise DapError(f"обязательный аргумент '{key}' не задан", code=-32602)
    return value


def _wf(args: dict) -> str:
    return _seg(_need(args, "workflow"))


def _node_url(args: dict) -> str:
    return f"/v1/workflows/{_wf(args)}/nodes/{_seg(_need(args, 'node'))}"


def tool_search_nodes(args: dict):
    query = urllib.parse.urlencode(
        {
            "q": args.get("query") or "",
            "category": args.get("category") or "",
            "limit": int(args.get("limit", 200)),
        }
    )
    return call("GET", f"/v1/nodes?{query}")


def tool_node_details(args: dict):
    return call("GET", f"/v1/nodes/{_seg(_need(args, 'id'))}")


def tool_node_dialog_schema(args: dict):
    node_id = _seg(_need(args, "id"))
    workflow = args.get("workflow")
    node = args.get("node")
    if workflow and node:
        return call("GET", f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(node)}/dialog")
    return call("GET", f"/v1/nodes/{node_id}/dialog")


def tool_workflow_create(args: dict):
    return call("POST", "/v1/workflows", {"name": args.get("name") or "workflow"})


def tool_list_workflows(_args: dict):
    return call("GET", "/v1/workflows")


def tool_delete_workflow(args: dict):
    return call("DELETE", f"/v1/workflows/{_wf(args)}")


def tool_add_node(args: dict):
    body = {"factory": _need(args, "factory"), "settings": args.get("settings")}
    return call("POST", f"/v1/workflows/{_wf(args)}/nodes", body)


def tool_configure_node(args: dict):
    body = {"settings": _need(args, "settings")}
    return call("PATCH", _node_url(args), body)


def tool_connect(args: dict):
    body = {
        "from": _need(args, "from"),
        "fromPort": int(args.get("fromPort", 0)),
        "to": _need(args, "to"),
        "toPort": int(args.get("toPort", 0)),
    }
    return call("POST", f"/v1/workflows/{_wf(args)}/connect", body)


def tool_disconnect(args: dict):
    body = {
        "from": _need(args, "from"),
        "fromPort": int(args.get("fromPort", 0)),
        "to": _need(args, "to"),
        "toPort": int(args.get("toPort", 0)),
    }
    return call("POST", f"/v1/workflows/{_wf(args)}/disconnect", body)


def tool_remove_node(args: dict):
    return call("DELETE", _node_url(args))


def _timeout_body(args: dict) -> dict:
    body = {}
    if args.get("timeoutSec") is not None:
        body["timeoutSec"] = int(args["timeoutSec"])
    return body


_JOB_TERMINAL = ("succeeded", "failed", "cancelled", "timeout")


def _job_progress(status: dict):
    """(progress, total, message) from job.progress, or None when the job has none."""
    progress = status.get("progress")
    if not isinstance(progress, dict):
        return None
    done = progress.get("nodesDone")
    percent = progress.get("percent")
    value = done if isinstance(done, int) and not isinstance(done, bool) else percent
    if not isinstance(value, int) or isinstance(value, bool):
        return None
    total = progress.get("nodesTotal")
    total = total if isinstance(total, int) and not isinstance(total, bool) else None
    current = progress.get("currentNode")
    return value, total, (str(current) if current else None)


def _poll_job(job: str) -> dict:
    """Poll GET /v1/jobs/{id} until terminal, forwarding job.progress as notifications."""
    seen = None
    deadline = time.monotonic() + TIMEOUT
    while True:
        status = call("GET", f"/v1/jobs/{_seg(job)}")
        status = status if isinstance(status, dict) else {}
        update = _job_progress(status)
        if update is not None and update != seen:
            seen = update
            _emit_progress(update[0], update[1], update[2])
        state = status.get("state")
        if state in _JOB_TERMINAL:
            result = status.get("result")
            return result if state == "succeeded" and isinstance(result, dict) else status
        if time.monotonic() >= deadline:
            raise DapError(
                f"задание {job} не завершилось за {TIMEOUT} с",
                data={"job": job, "state": state},
            )
        time.sleep(PROGRESS_POLL)


def tool_run(args: dict):
    if _PROGRESS_TOKEN is None:
        return call("POST", f"/v1/workflows/{_wf(args)}/run", _timeout_body(args))
    # A client that asked for progress gets an async job: the adapter polls it and
    # translates job.progress into notifications/progress.
    body = _timeout_body(args)
    body["async"] = True
    created = call("POST", f"/v1/workflows/{_wf(args)}/run", body)
    job = created.get("id") if isinstance(created, dict) else None
    if not job:
        return created
    return _poll_job(str(job))


def tool_run_async(args: dict):
    return call("POST", f"/v1/workflows/{_wf(args)}/jobs", _timeout_body(args))


def tool_job_status(args: dict):
    return call("GET", f"/v1/jobs/{_seg(_need(args, 'job'))}")


def tool_job_cancel(args: dict):
    return call("DELETE", f"/v1/jobs/{_seg(_need(args, 'job'))}")


def tool_list_jobs(_args: dict):
    return call("GET", "/v1/jobs")


# ------------------------------------------------------------------ get_table

_DEFAULT_MAX_CELL_CHARS = 200
# A filter is one SQL predicate over the output columns. Anything that could start
# a second statement or a file scan is rejected here, before the HTTP call.
_FILTER_FRAGMENTS = (";", "--", "/*")
_FILTER_WORDS = re.compile(r"\b(select|from)\b", re.IGNORECASE)
_QUOTED_SPAN = re.compile(r"'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"")


def _offset_arg(args: dict) -> int:
    value = args.get("offset")
    if value is None or value == "":
        return 0
    try:
        offset = int(value)
    except (TypeError, ValueError):
        raise DapError("'offset' должен быть целым числом >= 0", code=-32602)
    if offset < 0:
        raise DapError("'offset' должен быть >= 0", code=-32602)
    return offset


def _columns_arg(args: dict) -> list[str] | None:
    """Column projection: an array of names or one comma-separated string."""
    value = args.get("columns")
    if value is None:
        return None
    if isinstance(value, str):
        names = value.split(",")
    elif isinstance(value, list):
        names = [str(item) for item in value]
    else:
        raise DapError(
            "'columns' должен быть массивом имён или строкой через запятую", code=-32602)
    names = [name.strip() for name in names if str(name).strip()]
    return names or None


def _where_arg(args: dict) -> str | None:
    """One SQL predicate over the output columns, validated before the HTTP call."""
    value = args.get("where")
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    # String literals are data, not SQL: check only the code around them.
    code = _QUOTED_SPAN.sub(" ", text)
    for fragment in _FILTER_FRAGMENTS:
        if fragment in code:
            raise DapError(
                f"'where': запрещён фрагмент {fragment!r} в предикате",
                code=-32602, data={"code": "invalid_filter"},
            )
    if _FILTER_WORDS.search(code) or "read_" in code.lower():
        raise DapError(
            "'where' принимает только предикат над выходными колонками; "
            "SELECT, FROM и read_* запрещены",
            code=-32602, data={"code": "invalid_filter"},
        )
    return text


def _max_cell_chars(args: dict) -> int:
    value = args.get("maxCellChars")
    if value is None:
        return _DEFAULT_MAX_CELL_CHARS
    try:
        chars = int(value)
    except (TypeError, ValueError):
        raise DapError("'maxCellChars' должен быть целым числом >= 0", code=-32602)
    if chars < 0:
        raise DapError("'maxCellChars' должен быть >= 0", code=-32602)
    return chars


def _cell_text(value, max_chars: int) -> str:
    text = "" if value is None else str(value)
    if len(text) > max_chars:
        return text[:max_chars - 1] + "\u2026" if max_chars else ""
    return text


def _column_names(table: dict) -> list[str]:
    names = []
    for column in table.get("columns") or []:
        if isinstance(column, dict):
            names.append(str(column.get("name", "")))
        else:
            names.append(str(column))
    return names


def _csv_cell(text: str) -> str:
    if any(char in text for char in ',"\n\r'):
        return '"' + text.replace('"', '""') + '"'
    return text


def _csv_text(names: list[str], rows: list[list[str]]) -> str:
    lines = [",".join(_csv_cell(name) for name in names)]
    lines.extend(",".join(_csv_cell(cell) for cell in row) for row in rows)
    return "\n".join(lines) + "\n"


def _markdown_cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def _markdown_text(names: list[str], rows: list[list[str]]) -> str:
    lines = ["| " + " | ".join(_markdown_cell(name) for name in names) + " |",
             "| " + " | ".join("---" for _ in names) + " |"]
    lines.extend("| " + " | ".join(_markdown_cell(cell) for cell in row) + " |"
                 for row in rows)
    return "\n".join(lines) + "\n"


def _page_meta(table: dict, offset: int, limit: int) -> tuple[int, int | None]:
    """(offset, nextOffset): the engine's paging fields, computed when absent."""
    data = table.get("data")
    count = len(data) if isinstance(data, list) else 0
    current = table.get("offset")
    current = current if isinstance(current, int) and not isinstance(current, bool) else offset
    if "nextOffset" in table:
        following = table["nextOffset"]
    elif "truncated" in table:
        following = current + count if table["truncated"] else None
    else:
        total = table.get("rows")
        if isinstance(total, int) and not isinstance(total, bool):
            following = current + count if current + count < total else None
        else:
            following = current + count if count == limit else None
    if not isinstance(following, int) or isinstance(following, bool) or following <= current:
        following = None
    return current, following


def _format_table(table: dict, fmt: str, offset: int, limit: int,
                  max_chars: int, include_columns: bool, meta: bool = True) -> dict:
    if not isinstance(table, dict):
        table = {"data": table if isinstance(table, list) else []}
    names = _column_names(table)
    data = table.get("data")
    data = data if isinstance(data, list) else []
    rows = []
    for row in data:
        values = list(row) if isinstance(row, (list, tuple)) else [row]
        rows.append([_cell_text(values[index] if index < len(values) else None, max_chars)
                     for index in range(len(names) or len(values))])
    if not names:
        names = [str(index) for index in range(len(rows[0]) if rows else 0)]
    current, following = _page_meta(table, offset, limit)
    total = table.get("rows")
    total = total if isinstance(total, int) and not isinstance(total, bool) else current + len(rows)
    paging = {"offset": current, "nextOffset": following, "truncated": following is not None} if meta else {}
    if fmt == "csv":
        return dict({"format": "csv", "text": _csv_text(names, rows), "rows": total}, **paging)
    if fmt == "markdown":
        return dict({"format": "markdown", "text": _markdown_text(names, rows), "rows": total},
                    **paging)
    if fmt == "objects":
        result = {"rows": total,
                  "data": [dict(zip(names, row)) for row in rows]}
        if include_columns:
            result["columns"] = table.get("columns")
        return dict(result, **paging)
    result = dict(table)
    result["rows"] = total
    result["data"] = rows
    result.update(paging)
    if not include_columns:
        result.pop("columns", None)
    return result


def _project_columns(table: dict, names: list[str]) -> dict:
    """Project a raw table onto named columns, preserving the requested order."""
    columns = table.get("columns") if isinstance(table, dict) else None
    if not isinstance(columns, list):
        return table
    positions = {}
    for index, column in enumerate(columns):
        name = column.get("name") if isinstance(column, dict) else column
        positions.setdefault(str(name), index)
    order = [positions[name] for name in names if name in positions]
    if not order:
        return table
    result = dict(table)
    result["columns"] = [columns[index] for index in order]
    data = table.get("data")
    if isinstance(data, list):
        result["data"] = [
            [row[index] if index < len(row) else None for index in order]
            if isinstance(row, (list, tuple)) else row for row in data
        ]
    return result


def _tool_table(table, args) -> dict:
    """Cap cells in any tabular tool response; optional compact format/projection.

    Unlike dap_get_table, one-call tools are not paged: keep the original response
    shape (no offset/nextOffset/truncated) while still applying the same caps.
    """
    fmt = str(args.get("tableFormat") or "json").strip().lower()
    if fmt not in _TABLE_FORMATS:
        raise DapError(
            "'tableFormat' должен быть один из: " + ", ".join(_TABLE_FORMATS), code=-32602)
    columns = _columns_arg(args)
    if columns:
        table = _project_columns(table, columns)
    return _format_table(table, fmt, 0, _table_limit(args), _max_cell_chars(args),
                         args.get("includeColumns") is not False, meta=False)


def tool_get_table(args: dict):
    fmt = str(args.get("format") or "json").strip().lower()
    if fmt not in _TABLE_FORMATS:
        raise DapError(
            "'format' должен быть один из: " + ", ".join(_TABLE_FORMATS), code=-32602)
    offset = _offset_arg(args)
    columns = _columns_arg(args)
    where = _where_arg(args)
    max_chars = _max_cell_chars(args)
    include_columns = args.get("includeColumns") is not False
    limit = _table_limit(args)
    query = {"port": int(args.get("port", 0)), "limit": limit}
    if offset:
        query["offset"] = offset
    if columns:
        query["columns"] = ",".join(columns)
    if where:
        query["where"] = where
    table = call("GET", f"{_node_url(args)}/table?{urllib.parse.urlencode(query)}")
    return _format_table(table, fmt, offset, limit, max_chars, include_columns)


def tool_profile_table(args: dict):
    return call("GET", f"{_node_url(args)}/profile")


def _csv_body(args: dict, body: dict) -> dict:
    for key in ("header", "delimiter", "encoding", "allVarchar", "decimalComma",
                "sheet", "quote", "escape", "ignoreErrors", "nullPadding",
                "strictMode", "nullStrings"):
        if args.get(key) is not None:
            body[key] = args[key]
    return body


def tool_ingest(args: dict):
    body = {"file": _need(args, "file")}
    if args.get("format"):
        body["format"] = args["format"]
    return call("POST", "/v1/datasets", _csv_body(args, body))


def tool_dataset(args: dict):
    """Dataset catalog CRUD: one tool, an action enum, the engine does the work."""
    action = str(args.get("action") or "list").strip().lower()
    if action not in _DATASET_ACTIONS:
        raise DapError(
            "'action' должен быть один из: " + ", ".join(_DATASET_ACTIONS), code=-32602)
    name = None
    if action != "list":
        name = str(_need(args, "name")).strip()
        if not _CATALOG_NAME.match(name):
            raise DapError(
                "'name' датасета: [A-Za-z0-9._-]{1,64}", code=-32602)
    body = None
    if action == "add":
        has_file = args.get("file") is not None and args.get("file") != ""
        has_dataset = args.get("dataset") is not None and args.get("dataset") != ""
        if not has_file and not has_dataset:
            raise DapError("для action=add нужен 'file' или 'dataset'", code=-32602)
        if has_file and has_dataset:
            raise DapError("укажите либо 'file', либо 'dataset', не оба", code=-32602)
        tags = args.get("tags")
        if tags is not None and (not isinstance(tags, list)
                                 or any(not isinstance(tag, str) for tag in tags)):
            raise DapError("'tags' должен быть массивом строк", code=-32602)
        body = {"name": name}
        body["file" if has_file else "dataset"] = (
            args["file"] if has_file else args["dataset"])
        for key in ("format", "description", "tags", "owner"):
            if args.get(key) is not None and args.get(key) != "":
                body[key] = args[key]
    ensure_service()
    if action == "list":
        return call("GET", "/v1/catalog/datasets")
    if action == "add":
        return call("POST", "/v1/catalog/datasets", body)
    if action == "show":
        return call("GET", f"/v1/catalog/datasets/{_seg(name)}")
    if action == "refresh":
        return call("POST", f"/v1/catalog/datasets/{_seg(name)}/refresh")
    return call("DELETE", f"/v1/catalog/datasets/{_seg(name)}")


def tool_save_workflow(args: dict):
    return call("POST", f"/v1/workflows/{_wf(args)}/save", {"dir": _need(args, "dir")})


def tool_load_workflow(args: dict):
    body = {"dir": _need(args, "dir")}
    if args.get("name"):
        body["name"] = args["name"]
    return call("POST", "/v1/workflows/load", body)


# -------------------------------------------------------------------- recipes

def _recipe_error(failure: RECIPES.RecipeError) -> DapError:
    return DapError(str(failure), code=-32602, data=failure.data)


def _workflow_document(workflow) -> dict:
    """A workflow JSON object, or the document of a workflow id saved by the engine."""
    if isinstance(workflow, dict):
        return workflow
    if isinstance(workflow, str) and workflow.strip():
        directory = tempfile.mkdtemp(prefix="dap-recipe-save-")
        try:
            call("POST", f"/v1/workflows/{_seg(workflow)}/save", {"dir": directory})
            with open(os.path.join(directory, "workflow.json"), "r", encoding="utf-8") as stream:
                return json.load(stream)
        finally:
            shutil.rmtree(directory, ignore_errors=True)
    raise DapError(
        "workflow должен быть объектом workflow JSON или id workflow",
        code=-32602,
    )


def tool_recipe_list(_args: dict):
    return {"recipes": RECIPES.list_recipes(DAP_HOME)}


def tool_recipe_save(args: dict):
    name = _need(args, "name")
    document = _workflow_document(_need(args, "workflow"))
    try:
        saved = RECIPES.save_recipe(
            DAP_HOME,
            name,
            document,
            description=args.get("description"),
            tags=args.get("tags"),
        )
    except RECIPES.RecipeError as failure:
        raise _recipe_error(failure)
    return {
        "name": saved["recipe"]["name"],
        "path": RECIPES.recipe_path(DAP_HOME, name),
        "recipe": saved["recipe"],
        "nodes": len(saved["nodes"]),
    }


def tool_recipe_run(args: dict):
    name = _need(args, "name")
    try:
        document = RECIPES.load_recipe(DAP_HOME, name)
        bound, applied = RECIPES.bind_recipe(
            document, args.get("dataset"), args.get("params")
        )
    except RECIPES.RecipeError as failure:
        raise _recipe_error(failure)
    output = RECIPES.output_node(bound)
    position = RECIPES.node_position(bound, output) if output else None
    if position is None:
        raise DapError(f"рецепт {name}: не найден выходной узел", code=-32602)
    directory = tempfile.mkdtemp(prefix="dap-recipe-run-")
    try:
        with open(os.path.join(directory, "workflow.json"), "w", encoding="utf-8") as stream:
            json.dump(bound, stream, ensure_ascii=False)
        loaded = call("POST", "/v1/workflows/load", {"dir": directory, "name": str(name)})
        workflow = loaded.get("id") if isinstance(loaded, dict) else None
        if not workflow:
            raise DapError(f"движок не вернул id workflow для рецепта {name}")
        try:
            nodes = (call("GET", f"/v1/workflows/{_seg(workflow)}/nodes") or {}).get("nodes") or []
            if position >= len(nodes):
                raise DapError(
                    f"загруженный рецепт {name}: узлов {len(nodes)}, ожидался хотя бы {position + 1}"
                )
            node = nodes[position].get("node")
            call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
            query = urllib.parse.urlencode(
                {"port": 0, "limit": int(args.get("limit", 20))}
            )
            table = call(
                "GET",
                f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(node)}/table?{query}",
            )
        finally:
            try:
                call("DELETE", f"/v1/workflows/{_seg(workflow)}")
            except DapError:
                pass
    finally:
        shutil.rmtree(directory, ignore_errors=True)
    return {
        "recipe": name,
        "workflow": workflow,
        "node": node,
        "table": _tool_table(table, args),
        "applied": applied,
    }


_SQL_QUERY_KEYS = ("sql", "limit", "node")


def _sql_limit(value, where: str = "'limit'") -> int:
    try:
        limit = int(value)
    except (TypeError, ValueError):
        raise DapError(f"{where} должен быть целым числом от 1 до 1000", code=-32602)
    if limit < 1 or limit > 1000:
        raise DapError(f"{where} должен быть от 1 до 1000", code=-32602)
    return limit


def _sql_plans(args: dict) -> tuple[list[dict], bool]:
    """One plan per sql_query node to add; the second value marks a single 'sql'."""
    single = args.get("sql")
    batch = args.get("queries")
    if single is not None and batch is not None:
        raise DapError("укажите либо 'sql', либо 'queries', не оба", code=-32602)
    if single is None and batch is None:
        raise DapError("нужен 'sql' или 'queries'", code=-32602)
    default_limit = None
    if args.get("limit") is not None:
        default_limit = _sql_limit(args["limit"])
    if batch is None:
        return [{"sql": _read_only_sql(single), "limit": default_limit,
                 "node": args.get("node")}], True
    if not isinstance(batch, list) or not batch:
        raise DapError("'queries' должен быть непустым массивом", code=-32602)
    plans = []
    for index, item in enumerate(batch):
        if not isinstance(item, dict):
            raise DapError(f"queries[{index}] должен быть объектом", code=-32602)
        unknown = set(item) - set(_SQL_QUERY_KEYS)
        if unknown:
            raise DapError(
                f"queries[{index}]: неизвестные ключи: {', '.join(sorted(unknown))}",
                code=-32602,
            )
        sql = item.get("sql")
        if sql is None or (isinstance(sql, str) and not sql.strip()):
            raise DapError(f"queries[{index}]: обязательный аргумент 'sql' не задан",
                           code=-32602)
        limit = None if item.get("limit") is None else _sql_limit(
            item["limit"], f"queries[{index}].limit")
        plans.append({"sql": _read_only_sql(sql), "limit": limit,
                      "node": item.get("node")})
    return plans, False


def _add_sql_node(workflow: str, plan: dict) -> str:
    settings = {"sql": plan["sql"]}
    if plan["limit"] is not None:
        settings["limit"] = plan["limit"]
    upstream = plan["node"]
    if upstream:
        settings["inputs"] = [str(upstream)]
    created = call("POST", f"/v1/workflows/{_seg(workflow)}/nodes",
                   {"factory": "dap.sql_query", "settings": settings})
    node = created.get("node") if isinstance(created, dict) else None
    if not node:
        raise DapError("движок не вернул id узла dap.sql_query")
    if upstream:
        call("POST", f"/v1/workflows/{_seg(workflow)}/connect",
             {"from": str(upstream), "fromPort": 0, "to": node, "toPort": 0})
    return str(node)


def _estimate_node(workflow: str, node: str, body=None, run: bool = True) -> dict:
    # /estimate reads the node plan the run built; the run itself does not materialize
    # a table, so this stays cheap (EXPLAIN only, no /table). A batch runs once.
    if run:
        call("POST", f"/v1/workflows/{_seg(workflow)}/run", body or {})
    estimate = call("GET", f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(node)}/estimate")
    return estimate if isinstance(estimate, dict) else {}


def _sql_table(workflow: str, node: str, limit: int) -> dict:
    query = urllib.parse.urlencode({"port": 0, "limit": limit})
    return call("GET", f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(node)}/table?{query}")


def tool_sql(args: dict):
    workflow = _need(args, "workflow")
    explain = args.get("explain", False)
    if not isinstance(explain, bool):
        raise DapError("'explain' должен быть boolean", code=-32602)
    plans, single = _sql_plans(args)
    default_limit = 20
    if args.get("limit") is not None:
        default_limit = _sql_limit(args["limit"])
    if explain:
        # EXPLAIN only: build the node(s) and read /estimate, never run the workflow.
        if single:
            node = _add_sql_node(workflow, plans[0])
            return {"workflow": workflow, "node": node,
                    "estimate": _estimate_node(workflow, node, _timeout_body(args))}
        nodes = [_add_sql_node(workflow, plan) for plan in plans]
        call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
        estimates = [
            {"index": index, "node": node,
             "estimate": _estimate_node(workflow, node, run=False)}
            for index, node in enumerate(nodes)
        ]
        return {"workflow": workflow, "estimates": estimates}
    nodes = [_add_sql_node(workflow, plan) for plan in plans]
    call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
    if single:
        limit = plans[0]["limit"] or default_limit
        return {"workflow": workflow, "node": nodes[0],
                "table": _tool_table(_sql_table(workflow, nodes[0], limit), args)}
    return {
        "workflow": workflow,
        "tables": [
            {"index": index, "node": node,
             "table": _tool_table(
                 _sql_table(workflow, node, plan["limit"] or default_limit), args)}
            for index, (plan, node) in enumerate(zip(plans, nodes))
        ],
    }


def tool_workflow_build(args: dict):
    nodes = args.get("nodes")
    if not isinstance(nodes, list) or not nodes:
        raise DapError("'nodes' должен быть непустым массивом", code=-32602)
    keys = []
    for index, spec in enumerate(nodes):
        if not isinstance(spec, dict) or not spec.get("factory"):
            raise DapError("каждый узел требует 'factory'", code=-32602)
        unknown = set(spec) - {"key", "factory", "settings"}
        if unknown:
            raise DapError(f"неизвестные ключи узла: {', '.join(sorted(unknown))}", code=-32602)
        key = spec.get("key")
        key = str(index) if key is None else str(key)
        if not key.strip():
            raise DapError("'key' не может быть пустым", code=-32602)
        if key in keys:
            raise DapError(f"дублирующийся ключ узла: {key}", code=-32602)
        keys.append(key)
    edges = args.get("edges") or []
    if not isinstance(edges, list):
        raise DapError("'edges' должен быть массивом", code=-32602)
    for edge in edges:
        if not isinstance(edge, dict):
            raise DapError("edge должен быть объектом", code=-32602)
        for side in ("from", "to"):
            if edge.get(side) is None or str(edge.get(side)) not in keys:
                raise DapError(f"edge ссылается на неизвестный узел: {edge.get(side)}", code=-32602)
    outputs = args.get("outputs") or []
    if not isinstance(outputs, list):
        raise DapError("'outputs' должен быть массивом ключей", code=-32602)
    if len(outputs) > 50:
        raise DapError("слишком много outputs: максимум 50", code=-32602)
    run = args.get("run", False)
    if not isinstance(run, bool):
        raise DapError("'run' должен быть boolean", code=-32602)
    if outputs and not run:
        raise DapError("'outputs' требует run=true: иначе таблицы не материализованы", code=-32602)
    for key in outputs:
        if str(key) not in keys:
            raise DapError(f"outputs ссылается на неизвестный узел: {key}", code=-32602)
    limit = int(args.get("limit") or 20)
    if limit < 1 or limit > 1000:
        raise DapError("'limit' должен быть от 1 до 1000", code=-32602)
    ensure_service()
    created = call("POST", "/v1/workflows", {"name": args.get("name") or "workflow"})
    workflow = created.get("id") if isinstance(created, dict) else None
    if not workflow:
        raise DapError("движок не вернул id workflow")
    try:
        mapping = {}
        for index, spec in enumerate(nodes):
            key = str(index) if spec.get("key") is None else str(spec.get("key"))
            node = call("POST", f"/v1/workflows/{_seg(workflow)}/nodes",
                        {"factory": spec["factory"], "settings": spec.get("settings")})
            node_id = node.get("node") if isinstance(node, dict) else None
            if not node_id:
                raise DapError(f"движок не вернул id узла для '{key}'")
            mapping[key] = node_id
        for edge in edges:
            call("POST", f"/v1/workflows/{_seg(workflow)}/connect", {
                "from": mapping[str(edge["from"])], "fromPort": int(edge.get("fromPort") or 0),
                "to": mapping[str(edge["to"])], "toPort": int(edge.get("toPort") or 0)})
        result = {"workflow": workflow, "nodes": mapping}
        if run:
            result["run"] = call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
        if outputs:
            tables = {}
            for key in outputs:
                raw = call(
                    "GET", f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(mapping[str(key)])}/table?limit={limit}")
                tables[str(key)] = _tool_table(raw, args)
            result["tables"] = tables
        return result
    except Exception:
        try:
            call("DELETE", f"/v1/workflows/{_seg(workflow)}")
        except Exception:
            pass
        raise


def tool_analyze(args: dict):
    has_file = args.get("file") is not None and args.get("file") != ""
    has_dataset = args.get("dataset") is not None and args.get("dataset") != ""
    if not has_file and not has_dataset:
        raise DapError("нужен 'file' или 'dataset'", code=-32602)
    if has_file and has_dataset:
        raise DapError("укажите либо 'file', либо 'dataset', не оба", code=-32602)
    sql = args.get("sql")
    if sql:
        try:
            sql = RECIPES.validate_sql(str(sql))
        except RECIPES.RecipeError as failure:
            raise _recipe_error(failure)
    limit = int(args.get("limit") or 20)
    if limit < 1 or limit > 1000:
        raise DapError("'limit' должен быть от 1 до 1000", code=-32602)
    with_profile = args.get("profile") is not False
    stages = 4 if with_profile else 3
    ensure_service()
    ingested = {}
    ingest_cached = False
    if has_file:
        source, ingest_cached, ingested = _source(args, has_file)
    else:
        source = _resolve_dataset(args["dataset"])
    _emit_progress(1, stages, "ingest")
    created = call("POST", "/v1/workflows", {"name": "analyze"})
    workflow = created.get("id") if isinstance(created, dict) else None
    if not workflow:
        raise DapError("движок не вернул id workflow")
    try:
        reader = call("POST", f"/v1/workflows/{_seg(workflow)}/nodes",
                      {"factory": "dap.parquet_reader", "settings": {"file": source}})
        reader_id = reader.get("node") if isinstance(reader, dict) else None
        if not reader_id:
            raise DapError("движок не вернул id узла parquet_reader")
        node_id = reader_id
        if sql:
            query = call("POST", f"/v1/workflows/{_seg(workflow)}/nodes",
                         {"factory": "dap.sql_query", "settings": {"sql": sql, "inputs": ["src"]}})
            node_id = query.get("node") if isinstance(query, dict) else None
            if not node_id:
                raise DapError("движок не вернул id узла dap.sql_query")
            call("POST", f"/v1/workflows/{_seg(workflow)}/connect",
                 {"from": reader_id, "fromPort": 0, "to": node_id, "toPort": 0})
        run = call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
        _emit_progress(2, stages, "run")
        table = call("GET",
                     f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(node_id)}/table?limit={limit}")
        _emit_progress(3, stages, "table")
        result = {"workflow": workflow, "node": node_id, "source": source,
                  "cached": ingest_cached, "run": run, "table": _tool_table(table, args)}
        result.update(_ingest_details(ingested))
        if with_profile:
            result["profile"] = call(
                "GET", f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(reader_id)}/profile")
            _emit_progress(4, stages, "profile")
        return result
    finally:
        try:
            call("DELETE", f"/v1/workflows/{_seg(workflow)}")
        except Exception as failure:  # noqa: BLE001 - cleanup must not hide the result
            print(f"dap-mcp: cannot delete analyze workflow {workflow}: {failure}",
                  file=sys.stderr, flush=True)


def _positive_int(args: dict, key: str) -> int | None:
    value = args.get(key)
    if value is None or value == "":
        return None
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise DapError(f"'{key}' должен быть целым числом > 0", code=-32602)
    if number < 1:
        raise DapError(f"'{key}' должен быть > 0", code=-32602)
    return number


def tool_explore(args: dict):
    """One POST /v1/explore: profile, anomalies, relationships and hypotheses."""
    has_file, _ = _source_args(args)
    sql = args.get("sql")
    sql = _read_only_sql(sql) if sql else None
    max_columns = _positive_int(args, "maxColumns")
    sample_rows = _positive_int(args, "sampleRows")
    ensure_service()
    source, cached, ingested = _source(args, has_file)
    _emit_progress(1, 2, "ingest")
    body = {"dataset": source}
    if sql:
        body["sql"] = sql
    if max_columns is not None:
        body["maxColumns"] = max_columns
    if sample_rows is not None:
        body["sampleRows"] = sample_rows
    report = call("POST", "/v1/explore", body)
    _emit_progress(2, 2, "explore")
    result = {"source": source, "cached": cached, "report": report}
    result.update(_ingest_details(ingested))
    return result


def tool_estimate(args: dict):
    """Rows/operators/plan of a node's EXPLAIN without materializing it."""
    has_file, _ = _source_args(args)
    sql = args.get("sql")
    sql = _read_only_sql(sql) if sql else None
    limit = _table_limit(args) if args.get("limit") is not None else None
    ensure_service()
    source, cached, _ = _source(args, has_file)
    _emit_progress(1, 2, "ingest")
    workflow = _new_workflow("estimate")
    try:
        reader = _add_node(workflow, "dap.parquet_reader", {"file": source})
        node = reader
        if sql:
            settings = {"sql": sql, "inputs": ["src"]}
            if limit is not None:
                settings["limit"] = limit
            node = _add_node(workflow, "dap.sql_query", settings)
            _connect(workflow, reader, node)
        call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
        estimate = call(
            "GET", f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(node)}/estimate")
        _emit_progress(2, 2, "estimate")
        estimate = estimate if isinstance(estimate, dict) else {}
        result = {
            "source": source,
            "cached": cached,
            "node": node,
            "rows": estimate.get("rows"),
            "operators": estimate.get("operators") or [],
            "plan": estimate.get("plan"),
        }
        if estimate.get("columns") is not None:
            result["columns"] = estimate["columns"]
        return result
    finally:
        _drop_workflow(workflow, "estimate")


# --------------------------------------------------------------- один вызов

_CHART_TYPES = ("bar", "line", "scatter", "histogram")
_REPORT_FORMATS = ("md", "html", "pdf", "pptx")
# dap_report's 'format' used to mean the input file format; keep those values working.
_SOURCE_FORMATS = ("csv", "excel", "json", "parquet")


def _report_format(args: dict) -> tuple[str, str | None]:
    """(report format, legacy input file format) from the shared 'format' argument."""
    raw = args.get("format")
    if raw is None or str(raw).strip() == "":
        return "md", None
    value = str(raw).strip().lower()
    if value in _REPORT_FORMATS:
        return value, None
    if value in _SOURCE_FORMATS:
        return "md", value
    raise DapError(
        "'format' должен быть один из: " + ", ".join(_REPORT_FORMATS)
        + " (или формат входа: " + ", ".join(_SOURCE_FORMATS) + ")",
        code=-32602,
    )


def _source_args(args: dict) -> tuple[bool, bool]:
    """Validate that exactly one of file/dataset is given, before any HTTP call."""
    has_file = args.get("file") is not None and args.get("file") != ""
    has_dataset = args.get("dataset") is not None and args.get("dataset") != ""
    if not has_file and not has_dataset:
        raise DapError("нужен 'file' или 'dataset'", code=-32602)
    if has_file and has_dataset:
        raise DapError("укажите либо 'file', либо 'dataset', не оба", code=-32602)
    return has_file, has_dataset


def _resolve_dataset(value) -> str:
    """A dataset path, or a catalog name resolved through /v1/catalog/datasets/{name}.

    A value that exists on disk is a path and is returned untouched; a value that
    looks like a catalog name is looked up, and a 404 falls back to the old
    behaviour (the value is used as a path) so old callers keep working.
    """
    text = str(value)
    if os.path.exists(os.path.expanduser(text)):
        return text
    if not _CATALOG_NAME.match(text):
        return text
    try:
        entry = call("GET", f"/v1/catalog/datasets/{_seg(text)}")
    except DapError as failure:
        if (failure.data or {}).get("status") == 404:
            return text
        raise
    path = entry.get("path") if isinstance(entry, dict) else None
    return str(path) if path else text


_UNSET = object()


def _source(args: dict, has_file: bool, source_format=_UNSET) -> tuple[str, bool, dict]:
    """Ingest the file (once, through the engine cache) or take the dataset path."""
    if not has_file:
        return _resolve_dataset(args["dataset"]), False, {}
    body = {"file": args["file"]}
    format_value = args.get("format") if source_format is _UNSET else source_format
    if format_value:
        body["format"] = format_value
    ingested = call("POST", "/v1/datasets", _csv_body(args, body))
    details = ingested if isinstance(ingested, dict) else {}
    path = details.get("path")
    if not path:
        raise DapError("движок не вернул путь Parquet-кэша")
    return str(path), bool(details.get("cached")), details


def _ingest_details(ingested: dict) -> dict:
    """Optional sheet/encoding metadata to surface in one-call tool results."""
    return {key: ingested[key] for key in ("sheet", "sheets", "encoding")
            if ingested.get(key) is not None}


def _read_only_sql(value):
    try:
        return RECIPES.validate_sql(str(value))
    except RECIPES.RecipeError as failure:
        raise _recipe_error(failure)


def _table_limit(args: dict, default: int = 20) -> int:
    value = args.get("limit")
    limit = default if value is None else int(value)
    if limit < 1 or limit > 1000:
        raise DapError("'limit' должен быть от 1 до 1000", code=-32602)
    return limit


def _new_workflow(name: str) -> str:
    created = call("POST", "/v1/workflows", {"name": name})
    workflow = created.get("id") if isinstance(created, dict) else None
    if not workflow:
        raise DapError("движок не вернул id workflow")
    return str(workflow)


def _add_node(workflow: str, factory: str, settings: dict) -> str:
    created = call("POST", f"/v1/workflows/{_seg(workflow)}/nodes",
                   {"factory": factory, "settings": settings})
    node = created.get("node") if isinstance(created, dict) else None
    if not node:
        raise DapError(f"движок не вернул id узла {factory}")
    return str(node)


def _connect(workflow: str, source: str, target: str, to_port: int = 0) -> None:
    call("POST", f"/v1/workflows/{_seg(workflow)}/connect",
         {"from": source, "fromPort": 0, "to": target, "toPort": to_port})


def _drop_workflow(workflow: str, what: str) -> None:
    try:
        call("DELETE", f"/v1/workflows/{_seg(workflow)}")
    except Exception as failure:  # noqa: BLE001 - cleanup must not hide the result
        print(f"dap-mcp: cannot delete {what} workflow {workflow}: {failure}",
              file=sys.stderr, flush=True)


def _file_bytes(path: str) -> int | None:
    try:
        return os.path.getsize(path)
    except OSError:
        return None


def _report_queries(args: dict, limit: int) -> list[dict] | None:
    """Validated queries[] ([{sql, title, limit}]) or None for the single-sql path."""
    queries = args.get("queries")
    if queries is None:
        return None
    if args.get("sql") is not None:
        raise DapError("укажите либо 'sql', либо 'queries', не оба", code=-32602)
    if not isinstance(queries, list) or not queries:
        raise DapError("'queries' должен быть непустым массивом", code=-32602)
    plans = []
    for index, item in enumerate(queries):
        if not isinstance(item, dict):
            raise DapError(f"queries[{index}] должен быть объектом", code=-32602)
        unknown = set(item) - {"sql", "title", "limit"}
        if unknown:
            raise DapError(
                f"queries[{index}]: неизвестные ключи: {', '.join(sorted(unknown))}",
                code=-32602,
            )
        sql = item.get("sql")
        if sql is None or (isinstance(sql, str) and not sql.strip()):
            raise DapError(f"queries[{index}]: обязательный аргумент 'sql' не задан",
                           code=-32602)
        item_limit = limit
        if item.get("limit") is not None:
            item_limit = _sql_limit(item["limit"], f"queries[{index}].limit")
        title = item.get("title")
        plans.append({"sql": _read_only_sql(sql),
                      "title": str(title) if title else None,
                      "limit": item_limit})
    return plans


def tool_report(args: dict):
    has_file, _ = _source_args(args)
    report = args.get("report")
    if report is None or str(report).strip() == "":
        raise DapError("обязательный аргумент 'report' не задан", code=-32602)
    report = str(report)
    fmt, legacy_source_format = _report_format(args)
    chart = args.get("chart")
    chart = str(chart) if chart is not None and str(chart).strip() else None
    chart_type = str(args.get("chartType") or "bar")
    if chart_type not in _CHART_TYPES:
        raise DapError(
            "'chartType' должен быть один из: " + ", ".join(_CHART_TYPES), code=-32602)
    limit = _table_limit(args)
    with_profile = args.get("profile") is not False
    stages = 4 if with_profile else 3
    title = str(args["title"]) if args.get("title") else None
    y = args.get("y")
    if y is not None:
        if not isinstance(y, list) or any(not str(item).strip() for item in y):
            raise DapError("'y' должен быть массивом непустых имён колонок", code=-32602)
        y = [str(item) for item in y]
    queries = _report_queries(args, limit)
    sql = None
    if queries is None:
        sql = args.get("sql")
        sql = _read_only_sql(sql) if sql else None
    ensure_service()
    source, cached, ingested = _source(args, has_file, legacy_source_format)
    _emit_progress(1, stages, "ingest")
    workflow = _new_workflow("report")
    try:
        reader = _add_node(workflow, "dap.parquet_reader", {"file": source})
        if queries is None:
            node = reader
            if sql:
                node = _add_node(workflow, "dap.sql_query",
                                 {"sql": sql, "inputs": ["src"]})
                _connect(workflow, reader, node)
            sections = [{"kind": "table", "limit": limit}]
            if with_profile:
                sections.append({"kind": "profile"})
            table_nodes = [node]
            table_limits = [limit]
        else:
            # One sql node per query, each on its own report input port.
            table_nodes = []
            for query in queries:
                node = _add_node(workflow, "dap.sql_query",
                                 {"sql": query["sql"], "inputs": ["src"]})
                _connect(workflow, reader, node)
                table_nodes.append(node)
            sections = []
            for index, query in enumerate(queries):
                section = {"kind": "table", "input": index, "limit": query["limit"]}
                if query["title"]:
                    section["title"] = query["title"]
                sections.append(section)
            if with_profile:
                sections.append({"kind": "profile", "input": 0})
            table_limits = [query["limit"] for query in queries]
        report_settings = {"file": report, "format": fmt, "sections": sections}
        if title:
            report_settings["title"] = title
        report_node = _add_node(workflow, "dap.markdown_report", report_settings)
        for port, node in enumerate(table_nodes):
            _connect(workflow, node, report_node, port)
        if chart:
            chart_settings = {
                "file": chart,
                "format": "png" if chart.lower().endswith(".png") else "svg",
                "type": chart_type,
            }
            if args.get("x"):
                chart_settings["x"] = str(args["x"])
            if y:
                chart_settings["y"] = y
            if title:
                chart_settings["title"] = title
            chart_node = _add_node(workflow, "dap.chart", chart_settings)
            _connect(workflow, report_node, chart_node)
        call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
        _emit_progress(2, stages, "run")
        tables = []
        for index, (node, node_limit) in enumerate(zip(table_nodes, table_limits)):
            tables.append({
                "index": index,
                "node": node,
                "table": call(
                    "GET",
                    f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(node)}/table"
                    f"?limit={node_limit}"),
            })
        _emit_progress(3, stages, "table")
        result = {"source": source, "cached": cached, "format": fmt,
                  "report": report}
        result.update(_ingest_details(ingested))
        if queries is None:
            result["node"] = table_nodes[0]
            result["table"] = _tool_table(tables[0]["table"], args)
        else:
            result["nodes"] = table_nodes
            result["tables"] = [
                dict(entry, table=_tool_table(entry["table"], args)) for entry in tables
            ]
        if with_profile:
            result["profile"] = call(
                "GET",
                f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(table_nodes[0])}/profile")
            _emit_progress(4, stages, "profile")
        if chart:
            result["chart"] = chart
        sizes = {}
        for key, path in (("report", report), ("chart", chart)):
            if path:
                size = _file_bytes(path)
                if size is not None:
                    sizes[key] = size
        if sizes:
            result["bytes"] = sizes
        return result
    finally:
        _drop_workflow(workflow, "report")


def tool_profile(args: dict):
    has_file, _ = _source_args(args)
    ensure_service()
    source, cached, ingested = _source(args, has_file)
    _emit_progress(1, 3, "ingest")
    workflow = _new_workflow("profile")
    try:
        reader = _add_node(workflow, "dap.parquet_reader", {"file": source})
        call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
        _emit_progress(2, 3, "run")
        profile = call(
            "GET", f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(reader)}/profile")
        _emit_progress(3, 3, "profile")
        result = {"source": source, "cached": cached, "profile": profile}
        result.update(_ingest_details(ingested))
        if isinstance(profile, dict):
            result["rows"] = profile.get("rows")
            columns = profile.get("columns")
            if isinstance(columns, list):
                result["columns"] = len(columns)
        return result
    finally:
        _drop_workflow(workflow, "profile")


# ------------------------------------------------------------------ resources

def _resource_error(message: str, uri: str | None = None) -> DapError:
    return DapError(message, code=-32602, data={"uri": uri} if uri else None)


def read_resource(uri: str) -> str:
    """Return the JSON text of one MCP resource; engine errors propagate as DapError."""
    try:
        kind, name = RESOURCES.parse_uri(uri)
    except RESOURCES.ResourceError as failure:
        raise _resource_error(str(failure), uri)
    if kind == "catalog":
        ensure_service()
        return RESOURCES.render_catalog(call("GET", "/v1/nodes?limit=1000"))
    if kind == "datasets":
        ensure_service()
        return RESOURCES.render_datasets(call("GET", "/v1/catalog/datasets"))
    if kind == "dataset":
        ensure_service()
        return RESOURCES.render_dataset(
            call("GET", f"/v1/catalog/datasets/{_seg(name)}"), name)
    if kind == "recipes":
        return RESOURCES.render_recipes(RECIPES.list_recipes(DAP_HOME))
    try:
        document = RECIPES.load_recipe(DAP_HOME, name)
    except RECIPES.RecipeError as failure:
        raise DapError(str(failure), code=-32602, data=failure.data or {"name": name})
    return RESOURCES.render_recipe(document, name)


def _reap_child() -> None:
    """Дождаться собственного дочернего процесса после shutdown (без зомби)."""
    global _CHILD
    if _CHILD is None:
        return
    try:
        _CHILD.wait(timeout=5)
    except subprocess.TimeoutExpired:
        _stop_child(_CHILD)
    _CHILD = None


def tool_shutdown(_args: dict):
    if not health():
        _reap_child()
        return {"ok": True, "alreadyStopped": True}
    try:
        return call("POST", "/v1/shutdown", timeout=10)
    except TimeoutError as exc:
        raise DapError(
            f"движок не ответил на /v1/shutdown за 10 с: {exc}",
            code=-32001, data={"base": BASE, "timeout": 10},
        ) from exc
    finally:
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and health():
            time.sleep(0.05)
        _reap_child()


HANDLERS = {
    "dap_search_nodes": tool_search_nodes,
    "dap_node_details": tool_node_details,
    "dap_node_dialog_schema": tool_node_dialog_schema,
    "dap_workflow_create": tool_workflow_create,
    "dap_list_workflows": tool_list_workflows,
    "dap_delete_workflow": tool_delete_workflow,
    "dap_add_node": tool_add_node,
    "dap_configure_node": tool_configure_node,
    "dap_connect": tool_connect,
    "dap_disconnect": tool_disconnect,
    "dap_remove_node": tool_remove_node,
    "dap_run": tool_run,
    "dap_run_async": tool_run_async,
    "dap_job_status": tool_job_status,
    "dap_job_cancel": tool_job_cancel,
    "dap_list_jobs": tool_list_jobs,
    "dap_get_table": tool_get_table,
    "dap_profile_table": tool_profile_table,
    "dap_ingest": tool_ingest,
    "dap_dataset": tool_dataset,
    "dap_save_workflow": tool_save_workflow,
    "dap_load_workflow": tool_load_workflow,
    "dap_recipe_list": tool_recipe_list,
    "dap_recipe_save": tool_recipe_save,
    "dap_recipe_run": tool_recipe_run,
    "dap_sql": tool_sql,
    "dap_workflow_build": tool_workflow_build,
    "dap_analyze": tool_analyze,
    "dap_explore": tool_explore,
    "dap_estimate": tool_estimate,
    "dap_report": tool_report,
    "dap_profile": tool_profile,
    "dap_shutdown": tool_shutdown,
}

PROTOCOL_VERSION = "2024-11-05"


# ----------------------------------------------------------------------- MCP

def _write(payload: dict) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def _emit_progress(progress, total=None, message: str | None = None) -> None:
    """notifications/progress for the tools/call in flight; a no-op without a token."""
    token = _PROGRESS_TOKEN
    if token is None:
        return
    params = {"progressToken": token, "progress": progress}
    if total is not None:
        params["total"] = total
    if message:
        params["message"] = message
    _write({"jsonrpc": "2.0", "method": "notifications/progress", "params": params})


def respond(msg_id, result) -> None:
    _write({"jsonrpc": "2.0", "id": msg_id, "result": result})


def fail(msg_id, code: int, message: str, data=None) -> None:
    error = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    _write({"jsonrpc": "2.0", "id": msg_id, "error": error})


def handle(msg: dict) -> None:
    global _PROGRESS_TOKEN
    method = msg.get("method")
    msg_id = msg.get("id")
    params = msg.get("params") or {}

    # Уведомления (без id) ответа не ждут.
    if method == "notifications/initialized" or method == "notifications/cancelled":
        return

    if method == "initialize":
        version = params.get("protocolVersion") or PROTOCOL_VERSION
        respond(
            msg_id,
            {
                "protocolVersion": version,
                "capabilities": {
                    "tools": {"listChanged": False},
                    "resources": {"listChanged": False},
                    "prompts": {"listChanged": False},
                },
                "serverInfo": {"name": "dap-mcp", "version": "1.3.1"},
            },
        )
        return

    if method == "ping":
        respond(msg_id, {})
        return

    if method == "prompts/list":
        respond(msg_id, {"prompts": RESOURCES.list_prompts()})
        return

    if method == "prompts/get":
        name = params.get("name")
        if not isinstance(name, str) or not name.strip():
            fail(msg_id, -32602, "prompts/get: 'name' is required")
            return
        try:
            prompt = RESOURCES.render_prompt(name, params.get("arguments"))
        except RESOURCES.PromptError as e:
            fail(msg_id, -32602, str(e), e.data)
            return
        respond(
            msg_id,
            {
                "description": prompt["description"],
                "messages": [
                    {"role": "user", "content": {"type": "text", "text": prompt["text"]}}
                ],
            },
        )
        return

    if method == "resources/list":
        respond(msg_id, {"resources": RESOURCES.list_resources()})
        return

    if method == "resources/read":
        uri = params.get("uri")
        if not isinstance(uri, str) or not uri.strip():
            fail(msg_id, -32602, "resources/read: 'uri' is required")
            return
        try:
            text = read_resource(uri)
        except DapError as e:
            note = _restart_note() if e.code in (-32000, -32001) else ""
            fail(msg_id, e.code, note + str(e), _restart_data(note, e.data))
        except Exception as e:  # noqa: BLE001 — граница протокола
            fail(msg_id, -32603, f"{type(e).__name__}: {e}")
        else:
            respond(
                msg_id,
                {"contents": [{"uri": uri, "mimeType": "application/json", "text": text}]},
            )
        return

    if method == "tools/list":
        respond(msg_id, {"tools": TOOLS})
        return

    if method == "tools/call":
        name = params.get("name")
        args = params.get("arguments") or {}
        handler = HANDLERS.get(name)
        if handler is None:
            fail(msg_id, -32602, f"неизвестный инструмент: {name}")
            return
        if not isinstance(args, dict):
            fail(msg_id, -32602, "arguments должен быть объектом")
            return
        meta = params.get("_meta")
        _PROGRESS_TOKEN = meta.get("progressToken") if isinstance(meta, dict) else None
        try:
            if name not in ("dap_shutdown", "dap_analyze", "dap_estimate",
                            "dap_explore", "dap_dataset", "dap_workflow_build",
                            "dap_report", "dap_profile"):
                ensure_service()
            result = handler(args)
        except DapError as e:
            note = _restart_note()
            fail(msg_id, e.code, note + str(e), _restart_data(note, e.data))
        except (ValueError, TypeError) as e:
            fail(msg_id, -32602, f"неверные аргументы: {e}")
        except Exception as e:  # noqa: BLE001 — граница протокола
            fail(msg_id, -32603, f"{type(e).__name__}: {e}")
        else:
            respond(
                msg_id,
                {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}]},
            )
        finally:
            _PROGRESS_TOKEN = None
        return

    if msg_id is None:
        return
    fail(msg_id, -32601, f"метод не поддерживается: {method}")


def main() -> None:
    for stream in (sys.stdin, sys.stdout):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        msg_id = None
        try:
            msg = json.loads(line)
        except json.JSONDecodeError as e:
            fail(None, -32700, f"невалидный JSON: {e.msg}")
            continue
        if not isinstance(msg, dict):
            fail(None, -32600, "ожидается JSON-RPC объект")
            continue
        try:
            handle(msg)
        except BrokenPipeError:
            return
        except Exception as e:  # noqa: BLE001 — процесс не должен падать из-за одного запроса
            fail(msg.get("id"), -32603, f"{type(e).__name__}: {e}")


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, BrokenPipeError):
        pass
