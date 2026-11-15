# MCP — подключение движка к клиенту

DAP общается с агентом по [Model Context Protocol](https://modelcontextprotocol.io)
через stdio. Один процесс адаптера = один движок на `127.0.0.1:$DAP_PORT` и одна
сессия `X-DAP-Session`.

## Точка входа

```bash
scripts/dap-mcp
```

Обёртка сама находит корень репозитория, выставляет `DAP_HOME` и запускает
`adapters/mcp/dap_cli.py run` (Python 3.10+, только стандартная библиотека).
Клиент запускает её как дочерний процесс и говорит по stdin/stdout JSON-RPC 2.0.

Требования на машине:

- JDK 21 — `DAP_JDK` или `JAVA_HOME`;
- собранный jar: `make build-native` (`engine-native/target/dap-native.jar`);
- Python 3.10+ в `PATH` (или `PYTHON=/path/to/python3`).

## Подключение одной командой

`dap-mcp` — CLI адаптера (Python, только stdlib). Подкоманды: `run` (stdio,
по умолчанию), `install`, `doctor`, `version`.

```bash
python3 adapters/mcp/dap_cli.py install --client hermes   # ~/.hermes/config.yaml
python3 adapters/mcp/dap_cli.py install --client dsh      # ~/.dsh/profiles/desktop/cordis.patch.yml
python3 adapters/mcp/dap_cli.py doctor --client hermes
python3 adapters/mcp/dap_cli.py doctor --json
python3 adapters/mcp/dap_cli.py version                   # adapter=… engine=… api=…
```

`install` идемпотентен: повторный запуск не меняет файл (mtime сохраняется) и не
плодит записи; перед записью делается бэкап `<config>.bak-<YYYYmmdd-HHMMSS>`;
`--dry-run` печатает результат и ничего не пишет; `--force` создаёт отсутствующий
конфиг клиента (без него — код 2); `--config PATH` — явный путь к конфигу;
`--home PATH` — DAP home (по умолчанию `$DAP_HOME` или корень репозитория), а
клиентские конфиги ищутся в `$HOME`. Все пути в записи абсолютные: оба клиента
чистят окружение дочернего процесса. Существующие MCP-серверы и комментарии
сохраняются — обновляется только запись `dap`. Нестандартный YAML (flow-style,
якоря) не переписывается: печатается фрагмент и код 2.

`doctor` проверяет Python >= 3.10, jar, `/v1/health`, прямой stdio-прогон
(`initialize` + `tools/list`), запись `dap` в конфиге клиента и свободный порт;
код 0 — только когда все проверки PASS, `--json` печатает список. Если движок не
запущен, `doctor` поднимает его через адаптер (initialize + первый tool call,
как при реальном вызове) и ждёт `/v1/health` до 60 с, поэтому PASS означает
рабочую установку «с нуля».

## Дистрибутив

```bash
make dist          # dist/: jar, adapters/mcp, scripts/dap-mcp, VERSION, SHA256SUMS
make install-e2e   # установка из dist/ во временный HOME + stdio preflight
```

`dist/` самодостаточен: распакуйте в `~/.dap` и задайте `DAP_HOME=~/.dap` — CLI и
адаптер окажутся в `~/.dap`, jar в `~/.dap/engine-native/target/dap-native.jar`.

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `DAP_HOME` | корень репозитория | где лежит jar и пишется `service.log` |
| `DAP_PORT` | `7800` | порт движка |
| `DAP_SESSION` | `default` | сессия агента (изоляция workflow и jobs) |
| `DAP_BIND` | `127.0.0.1` | адрес движка; `0.0.0.0`/`::` для запросов заменяются на loopback |
| `DAP_HEAP` | `2g` | `-Xmx` для jar |
| `DAP_IDLE_TIMEOUT_SEC` | `900` | простой, после которого движок завершается; `0` — выключено |
| `DAP_QUERY_TIMEOUT_SEC` | `60` | предел материализации `/table` и `/profile`, с; `0` — выключено |
| `DAP_MAX_SESSIONS` | `64` | максимум открытых сессионных БД, LRU-эвикция |
| `DAP_SESSION_TTL_SEC` | `1800` | простой сессии до эвикции, с; `0` — выключено |
| `DAP_MAX_RETAINED_JOBS` | `200` | cap терминальных заданий на сессию |
| `DAP_DUCKDB_EXTENSION_DIR` | `$DAP_HOME/build/duckdb-ext` | каталог расширений DuckDB; сеть только при `--install-extensions` |
| `DAP_CACHE_DIR` | `$DAP_HOME/build/cache` | каталог Parquet-кэша `dap_ingest` |
| `JAVA_HOME` | — | откуда брать `bin/java` |

Адаптер выставляет `DAP_HOME`, поэтому кэш ingest по умолчанию лежит в
`$DAP_HOME/build/cache`. Без `DAP_HOME` используется `~/.dap/cache`, а если
каталог нельзя создать (sandbox) — `$TMPDIR/dap-cache`.

### Рестарт движка и ошибки

Адаптер запоминает `generation` из `GET /v1/health` при каждой проверке и, если
она изменилась (движок перезапустился, например после `dap_shutdown`), добавляет
к следующей ошибке префикс `engine restarted; workflows lost: ` — вместо
молчаливого 404 на потерянный workflow.

Тело ошибки движка `{"error","code","hint"}` адаптер передаёт в JSON-RPC
`error.data`: `status` (HTTP-код), `code` и `hint` (если есть) лежат на верхнем
уровне `data`, а исходное тело — в `data.body` (для совместимости).

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

Всего 22 инструмента, их состав не менялся. Каталог движка — 44 узла `dap.*`:
`dap_search_nodes` и `dap_node_dialog_schema` видят их все, включая узлы волн P2 и P3
(`type_cast`, `duplicate_filter`, `pivot`, `window`, `concatenate`, `describe`,
`value_counts`, `correlation`, `histogram`, `quantile`, `outlier_detect`, `resample`,
`rolling`, `diff`, `date_range`, `excel_writer`). `dap_ingest`
один раз конвертирует CSV/Excel/JSON/Parquet в Parquet-кэш и возвращает `path`,
`rows`, `columns` и `cached`; `path` можно сразу передать в `dap.parquet_reader`
через `dap_add_node`.

Типовой проход агента: `dap_search_nodes` → `dap_node_dialog_schema` →
`dap_workflow_create` → `dap_add_node` → `dap_connect` → `dap_run` →
`dap_get_table`/`dap_profile_table` → `dap_save_workflow`. Для повторяющегося
файла перед `dap_add_node` вызывается `dap_ingest`.

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

## Если конфиг нестандартный

`install` не переписывает YAML, который не понимает (flow-style, якоря, теги):
печатает первые 20 строк конфига и выходит с кодом 2. Добавьте запись вручную.

Hermes (`~/.hermes/config.yaml`):

```yaml
mcp_servers:
  dap:
    command: /absolute/path/to/python3
    args:
      - /absolute/path/to/dap/adapters/mcp/dap_mcp.py
    env:
      DAP_HOME: /absolute/path/to/dap
      DAP_PORT: "7800"
      DAP_HEAP: 2g
      JAVA_HOME: /absolute/path/to/jdk-21
    cwd: /absolute/path/to/dap
    enabled: true
    timeout: 300
    connect_timeout: 60
    tools:
      resources: true
      prompts: true
```

DSH (`~/.dsh/profiles/desktop/cordis.patch.yml`):

```yaml
- id: mcp-manager
  config:
    servers:
      dap:
        transport: stdio
        command: /absolute/path/to/python3
        args:
          - /absolute/path/to/dap/adapters/mcp/dap_mcp.py
        env:
          DAP_HOME: /absolute/path/to/dap
          DAP_PORT: "7800"
          DAP_HEAP: 2g
          JAVA_HOME: /absolute/path/to/jdk-21
        cwd: /absolute/path/to/dap
        enabled: true
        startupTimeoutMs: 60000
        toolCallTimeoutMs: 300000
        reconnect: true
```

После ручной правки DSH перезапустите; `dap-mcp doctor` проверяет сервер напрямую
через stdio и не зависит от CLI клиента.

## Проверка

```bash
make check                 # JUnit + goldens + MCP launch + MCP e2e + lint
make install-e2e           # установка из dist/ во временный HOME + stdio preflight
python3 tests/mcp_e2e.py   # полный сценарий через stdio
```
