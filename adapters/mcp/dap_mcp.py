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
import shutil
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
START_WAIT = 60
NATIVE_JAR = os.path.join(DAP_HOME, "engine-native", "target", "dap-native.jar")
HEAP = (os.environ.get("DAP_HEAP") or "").strip() or "2g"
_CHILD: subprocess.Popen | None = None
# Boot generation of the engine we talked to last, and a pending restart notice.
_GENERATION: str | None = None
_RESTART_PENDING = False

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
    """GET /v1/health: the parsed body, or None when the engine does not answer."""
    try:
        with urllib.request.urlopen(f"{BASE}/v1/health", timeout=2) as r:
            if r.status != 200:
                return None
            raw = r.read()
            return json.loads(raw.decode("utf-8")) if raw else {}
    except Exception:
        return None


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
    return "engine restarted; workflows lost: "


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


def ensure_service() -> None:
    """Поднять движок, если он ещё не запущен; чужой сервис не трогаем."""
    if health():
        return
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


TOOLS = [
    {
        "name": "dap_search_nodes",
        "description": "Поиск узлов DAP по имени или категории. Возвращает id, имя, категорию, тип, tier и порты.",
        "inputSchema": _obj(
            {
                "query": _str("подстрока имени узла, например 'reader' или 'filter'"),
                "category": _str("категория каталога, например /io/read"),
                "limit": _int("сколько узлов вернуть", 20),
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
        "description": "Прочитать выходную таблицу узла: колонки с типами, число строк и первые N строк.",
        "inputSchema": _obj(
            {
                "workflow": _str(_WF),
                "node": _str(_NODE),
                "port": _int("индекс выходного порта", 0),
                "limit": _int("сколько строк вернуть", 20),
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
            "(cached=true). Возвращает path, rows, columns и cached."
        ),
        "inputSchema": _obj(
            {
                "file": _str("путь к файлу CSV, XLSX, JSON или Parquet"),
                "format": _str("csv, excel, json или parquet; по умолчанию по расширению"),
            },
            ["file"],
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
                "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка"),
            },
            ["name"],
        ),
    },
    {
        "name": "dap_sql",
        "description": (
            "Добавить узел dap.sql_query в workflow, выполнить его и вернуть таблицу. "
            "Принимает один read-only SELECT/WITH; DDL/DML и несколько стейтментов "
            "отклоняются до обращения к движку. Если передан node, его таблица доступна "
            "в SQL под именем node id (в кавычках, например \"1:1\")."
        ),
        "inputSchema": _obj(
            {
                "workflow": _str(_WF),
                "node": _str("опционально: узел-источник, чья таблица доступна в SQL"),
                "sql": _str("один SELECT или WITH; DDL/DML и несколько стейтментов запрещены"),
                "limit": _int("сколько строк таблицы вернуть", 20),
                "timeoutSec": _int("лимит исполнения, по умолчанию настройка движка"),
            },
            ["workflow", "sql"],
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
                "outputs": {
                    "type": "array",
                    "description": "ключи узлов, таблицы которых вернуть",
                    "items": {"type": "string"},
                },
                "limit": _int("строк на таблицу выхода", 20),
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
                "dataset": _str("путь к готовому Parquet-датасету (из dap_ingest)"),
                "sql": _str("read-only SELECT/WITH; вход доступен как src"),
                "profile": {"type": "boolean", "description": "вернуть профиль источника", "default": True},
                "limit": _int("строк таблицы", 20),
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
            "limit": int(args.get("limit", 20)),
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


def tool_run(args: dict):
    return call("POST", f"/v1/workflows/{_wf(args)}/run", _timeout_body(args))


def tool_run_async(args: dict):
    return call("POST", f"/v1/workflows/{_wf(args)}/jobs", _timeout_body(args))


def tool_job_status(args: dict):
    return call("GET", f"/v1/jobs/{_seg(_need(args, 'job'))}")


def tool_job_cancel(args: dict):
    return call("DELETE", f"/v1/jobs/{_seg(_need(args, 'job'))}")


def tool_list_jobs(_args: dict):
    return call("GET", "/v1/jobs")


def tool_get_table(args: dict):
    query = urllib.parse.urlencode(
        {"port": int(args.get("port", 0)), "limit": int(args.get("limit", 20))}
    )
    return call("GET", f"{_node_url(args)}/table?{query}")


def tool_profile_table(args: dict):
    return call("GET", f"{_node_url(args)}/profile")


def tool_ingest(args: dict):
    body = {"file": _need(args, "file")}
    if args.get("format"):
        body["format"] = args["format"]
    return call("POST", "/v1/datasets", body)


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
        "table": table,
        "applied": applied,
    }


def tool_sql(args: dict):
    workflow = _need(args, "workflow")
    try:
        sql = RECIPES.validate_sql(_need(args, "sql"))
    except RECIPES.RecipeError as failure:
        raise _recipe_error(failure)
    settings = {"sql": sql}
    if args.get("limit") is not None:
        settings["limit"] = int(args["limit"])
    upstream = args.get("node")
    if upstream:
        settings["inputs"] = [str(upstream)]
    created = call(
        "POST",
        f"/v1/workflows/{_seg(workflow)}/nodes",
        {"factory": "dap.sql_query", "settings": settings},
    )
    node = created.get("node") if isinstance(created, dict) else None
    if not node:
        raise DapError("движок не вернул id узла dap.sql_query")
    if upstream:
        call(
            "POST",
            f"/v1/workflows/{_seg(workflow)}/connect",
            {"from": str(upstream), "fromPort": 0, "to": node, "toPort": 0},
        )
    call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
    query = urllib.parse.urlencode({"port": 0, "limit": int(args.get("limit", 20))})
    table = call(
        "GET",
        f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(node)}/table?{query}",
    )
    return {"workflow": workflow, "node": node, "table": table}


def tool_workflow_build(args: dict):
    nodes = args.get("nodes")
    if not isinstance(nodes, list) or not nodes:
        raise DapError("'nodes' должен быть непустым массивом", code=-32602)
    keys = []
    for index, spec in enumerate(nodes):
        if not isinstance(spec, dict) or not spec.get("factory"):
            raise DapError("каждый узел требует 'factory'", code=-32602)
        key = str(spec.get("key") or index)
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
            if edge.get(side) not in keys:
                raise DapError(f"edge ссылается на неизвестный узел: {edge.get(side)}", code=-32602)
    outputs = args.get("outputs") or []
    for key in outputs:
        if key not in keys:
            raise DapError(f"outputs ссылается на неизвестный узел: {key}", code=-32602)
    created = call("POST", "/v1/workflows", {"name": args.get("name") or "workflow"})
    workflow = created.get("id") if isinstance(created, dict) else None
    if not workflow:
        raise DapError("движок не вернул id workflow")
    mapping = {}
    for index, spec in enumerate(nodes):
        key = str(spec.get("key") or index)
        node = call("POST", f"/v1/workflows/{_seg(workflow)}/nodes",
                    {"factory": spec["factory"], "settings": spec.get("settings")})
        node_id = node.get("node") if isinstance(node, dict) else None
        if not node_id:
            raise DapError(f"движок не вернул id узла для '{key}'")
        mapping[key] = node_id
    for edge in edges:
        call("POST", f"/v1/workflows/{_seg(workflow)}/connect", {
            "from": mapping[edge["from"]], "fromPort": int(edge.get("fromPort", 0)),
            "to": mapping[edge["to"]], "toPort": int(edge.get("toPort", 0))})
    result = {"workflow": workflow, "nodes": mapping}
    if args.get("run"):
        result["run"] = call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
    if outputs:
        tables = {}
        limit = int(args.get("limit", 20))
        for key in outputs:
            tables[key] = call("GET", f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(mapping[key])}/table?limit={limit}")
        result["tables"] = tables
    return result


def tool_analyze(args: dict):
    source = args.get("file") or args.get("dataset")
    if not source:
        raise DapError("нужен 'file' или 'dataset'", code=-32602)
    sql = args.get("sql")
    if sql:
        try:
            sql = RECIPES.validate_sql(str(sql))
        except RECIPES.RecipeError as failure:
            raise _recipe_error(failure)
    cached = None
    if args.get("file"):
        ingested = call("POST", "/v1/datasets", {"file": args["file"]})
        cached = ingested.get("path") if isinstance(ingested, dict) else None
        if not cached:
            raise DapError("движок не вернул путь Parquet-кэша")
        source = cached
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
            settings = {"sql": sql, "inputs": ["src"]}
            if args.get("limit") is not None:
                settings["limit"] = int(args["limit"])
            query = call("POST", f"/v1/workflows/{_seg(workflow)}/nodes",
                         {"factory": "dap.sql_query", "settings": settings})
            node_id = query.get("node") if isinstance(query, dict) else None
            if not node_id:
                raise DapError("движок не вернул id узла dap.sql_query")
            call("POST", f"/v1/workflows/{_seg(workflow)}/connect",
                 {"from": reader_id, "fromPort": 0, "to": node_id, "toPort": 0})
        run = call("POST", f"/v1/workflows/{_seg(workflow)}/run", _timeout_body(args))
        table = call("GET",
                     f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(node_id)}/table?limit={int(args.get('limit', 20))}")
        result = {"workflow": workflow, "node": node_id, "source": source,
                  "cached": bool(args.get("file") and cached), "run": run, "table": table}
        if args.get("profile", True):
            result["profile"] = call(
                "GET", f"/v1/workflows/{_seg(workflow)}/nodes/{_seg(reader_id)}/profile")
        return result
    finally:
        try:
            call("DELETE", f"/v1/workflows/{_seg(workflow)}")
        except Exception:
            pass


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
        return call("POST", "/v1/shutdown")
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
    "dap_save_workflow": tool_save_workflow,
    "dap_load_workflow": tool_load_workflow,
    "dap_recipe_list": tool_recipe_list,
    "dap_recipe_save": tool_recipe_save,
    "dap_recipe_run": tool_recipe_run,
    "dap_sql": tool_sql,
    "dap_workflow_build": tool_workflow_build,
    "dap_analyze": tool_analyze,
    "dap_shutdown": tool_shutdown,
}

PROTOCOL_VERSION = "2024-11-05"


# ----------------------------------------------------------------------- MCP

def _write(payload: dict) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def respond(msg_id, result) -> None:
    _write({"jsonrpc": "2.0", "id": msg_id, "result": result})


def fail(msg_id, code: int, message: str, data=None) -> None:
    error = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    _write({"jsonrpc": "2.0", "id": msg_id, "error": error})


def handle(msg: dict) -> None:
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
                "serverInfo": {"name": "dap-mcp", "version": "1.1.0"},
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
            fail(msg_id, e.code, note + str(e), e.data)
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
        try:
            if name != "dap_shutdown":
                ensure_service()
            result = handler(args)
        except DapError as e:
            fail(msg_id, e.code, _restart_note() + str(e), e.data)
        except (ValueError, TypeError) as e:
            fail(msg_id, -32602, f"неверные аргументы: {e}")
        except Exception as e:  # noqa: BLE001 — граница протокола
            fail(msg_id, -32603, f"{type(e).__name__}: {e}")
        else:
            respond(
                msg_id,
                {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}]},
            )
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
