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

import json
import os
import subprocess
import sys
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


class DapError(Exception):
    """Ошибка обращения к DAP; превращается в JSON-RPC error."""

    def __init__(self, message: str, code: int = -32000, data=None) -> None:
        super().__init__(message)
        self.code = code
        self.data = data


# --------------------------------------------------------------------- сервис

def health() -> bool:
    try:
        with urllib.request.urlopen(f"{BASE}/v1/health", timeout=2) as r:
            return r.status == 200
    except Exception:
        return False


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
        raise DapError(str(message or f"HTTP {e.code}"), data={"status": e.code, "body": payload})
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


def tool_save_workflow(args: dict):
    return call("POST", f"/v1/workflows/{_wf(args)}/save", {"dir": _need(args, "dir")})


def tool_load_workflow(args: dict):
    body = {"dir": _need(args, "dir")}
    if args.get("name"):
        body["name"] = args["name"]
    return call("POST", "/v1/workflows/load", body)


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
    "dap_save_workflow": tool_save_workflow,
    "dap_load_workflow": tool_load_workflow,
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
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "dap-mcp", "version": "1.0.0"},
            },
        )
        return

    if method == "ping":
        respond(msg_id, {})
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
            fail(msg_id, e.code, str(e), e.data)
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
