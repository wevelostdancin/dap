# MCP — подключение движка к клиенту

DAP общается с агентом по [Model Context Protocol](https://modelcontextprotocol.io)
через stdio. Один процесс адаптера = один движок на `127.0.0.1:$DAP_PORT` и одна
сессия `X-DAP-Session`.

## Точка входа

```bash
scripts/dap-mcp
```

Обёртка сама находит корень репозитория, выставляет `DAP_HOME` и запускает
`adapters/mcp/dap_mcp.py` (Python 3.10+, только стандартная библиотека). Клиент
запускает её как дочерний процесс и говорит по stdin/stdout JSON-RPC 2.0.

Требования на машине:

- JDK 21 — `DAP_JDK` или `JAVA_HOME`;
- собранный jar: `make build-native` (`engine-native/target/dap-native.jar`);
- Python 3.10+ в `PATH` (или `PYTHON=/path/to/python3`).

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `DAP_HOME` | корень репозитория | где лежит jar и пишется `service.log` |
| `DAP_PORT` | `7800` | порт движка |
| `DAP_SESSION` | `default` | сессия агента (изоляция workflow и jobs) |
| `DAP_BIND` | `127.0.0.1` | адрес движка; `0.0.0.0`/`::` для запросов заменяются на loopback |
| `DAP_HEAP` | `2g` | `-Xmx` для jar |
| `DAP_IDLE_TIMEOUT_SEC` | `900` | простой, после которого движок завершается; `0` — выключено |
| `JAVA_HOME` | — | откуда брать `bin/java` |

## Жизненный цикл

1. Первый `tools/call` → адаптер проверяет `GET /v1/health`; если движка нет —
   запускает jar и ждёт health до 60 с.
2. Движок работает, пока есть запросы. После `DAP_IDLE_TIMEOUT_SEC` простоя (и при
   отсутствии активных jobs) он завершается сам.
3. `dap_shutdown` останавливает движок явно; следующий `tools/call` поднимает его
   снова.
4. Если движок уже был запущен до адаптера, адаптер им не управляет: `dap_shutdown`
   остановит его, но перезапуск сделает следующий вызов.

## Протокол

- `initialize` → `protocolVersion: "2024-11-05"`, `serverInfo.name: "dap-mcp"`;
- `notifications/initialized` → без ответа;
- `tools/list` → массив инструментов `dap_*`;
- `tools/call` → `{"content":[{"type":"text","text":"<json>"}]}`.

Ошибки — JSON-RPC `error`:

| Код | Смысл |
|---|---|
| `-32000` | движок вернул ошибку (`data.status`, `data.body`) |
| `-32001` | движок недоступен/упал/не поднялся; `data.log`, `data.tail` |
| `-32602` | неверные аргументы инструмента |

Логи и трассировки — только в stderr, stdout занят протоколом.

## Инструменты

| Группа | Инструменты |
|---|---|
| каталог | `dap_search_nodes`, `dap_node_details`, `dap_node_dialog_schema` |
| workflow | `dap_workflow_create`, `dap_list_workflows`, `dap_delete_workflow`, `dap_save_workflow`, `dap_load_workflow` |
| граф | `dap_add_node`, `dap_configure_node`, `dap_connect`, `dap_disconnect`, `dap_remove_node` |
| запуск | `dap_run`, `dap_run_async`, `dap_job_status`, `dap_job_cancel`, `dap_list_jobs` |
| данные | `dap_get_table`, `dap_profile_table`, `dap_ingest` |
| сервис | `dap_shutdown` |

Типовой проход агента: `dap_search_nodes` → `dap_node_dialog_schema` →
`dap_workflow_create` → `dap_add_node` → `dap_connect` → `dap_run` →
`dap_get_table`/`dap_profile_table` → `dap_save_workflow`.

## Как подключить в DSH

DSH подключает MCP-серверы плагином: плагин запускает `scripts/dap-mcp` как
дочерний процесс, выполняет `initialize`, получает `tools/list` и регистрирует
инструменты через `ctx.tools.register()`, а вызовы пробрасывает в `tools/call`.

Скелет (псевдокод, точный API — в dsh-plugin-guide):

```ts
// plugin entry
const child = spawn("scripts/dap-mcp", { stdio: ["pipe", "pipe", "inherit"] });
// 1) send initialize, wait for result
// 2) send notifications/initialized
// 3) tools/list -> for each tool: ctx.tools.register({ name, description, parameters, execute })
//    execute(args) -> write {jsonrpc, id, method:"tools/call", params:{name, arguments:args}}
//                     and resolve with the text content parsed as JSON
// 4) on dispose: kill child (the engine is stopped by dap_shutdown or idle timeout)
```

Требования к транспорту: по одному JSON-объекту на строку, без лишнего вывода в
stdout, ответы сопоставляются по `id`.

## Безопасность

- Движок слушает loopback и не имеет аутентификации: любой локальный процесс может
  читать/писать файлы, доступные процессу jar. Не выставляйте порт наружу.
- `dap_save_workflow`/`dap_load_workflow` принимают произвольный локальный путь —
  это осознанный дизайн локального инструмента.

## Проверка

```bash
make check                 # JUnit + goldens + MCP launch + MCP e2e + lint
python3 tests/mcp_e2e.py   # полный сценарий через stdio
```
