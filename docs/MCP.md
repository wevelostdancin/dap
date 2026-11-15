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
(`initialize` + `tools/list`), запись `dap` в конфиге клиента, набор расширений
DuckDB (необязательный: отсутствие даёт подсказку `make extensions`, а не FAIL) и
свободный порт; код 0 — только когда все проверки PASS, `--json` печатает список. Если движок не
запущен, `doctor` поднимает его через адаптер (initialize + первый tool call,
как при реальном вызове) и ждёт `/v1/health` до 60 с, поэтому PASS означает
рабочую установку «с нуля».

## Дистрибутив

```bash
make dist          # dist/: jar, adapters/mcp, scripts/dap-mcp, VERSION, SHA256SUMS
make install-e2e   # установка из dist/ во временный HOME + stdio preflight
make extensions    # sqlite/postgres/httpfs в $DAP_HOME/build/duckdb-ext (сеть один раз)
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

1. Первый `tools/call` (или `resources/read` каталога) → адаптер проверяет
   `GET /v1/health`; если движка нет — запускает jar и ждёт health до 60 с.
2. Движок работает, пока есть запросы. После `DAP_IDLE_TIMEOUT_SEC` простоя (и при
   отсутствии активных jobs) он завершается сам.
3. `dap_shutdown` останавливает движок явно; следующий `tools/call` поднимает его
   снова.
4. Если движок уже был запущен до адаптера, адаптер им не управляет: `dap_shutdown`
   остановит его, но перезапуск сделает следующий вызов.

## Протокол

- `initialize` → `protocolVersion: "2024-11-05"`, `serverInfo.name: "dap-mcp"`,
  capabilities `tools`, `resources` и `prompts` (у всех `listChanged: false`);
- `notifications/initialized` → без ответа;
- `tools/list` → массив инструментов `dap_*`;
- `tools/call` → `{"content":[{"type":"text","text":"<json>"}]}`;
- `resources/list` / `resources/read` → каталог узлов и библиотека рецептов;
- `prompts/list` / `prompts/get` → готовые user-сообщения для типовых анализов.

Ошибки — JSON-RPC `error`:

| Код | Смысл |
|---|---|
| `-32000` | движок вернул ошибку (`data.status`, `data.body`) |
| `-32001` | движок недоступен/упал/не поднялся; `data.log`, `data.tail` |
| `-32602` | неверные аргументы инструмента; неизвестный URI ресурса, рецепт или промпт; отсутствует обязательный аргумент |

Логи и трассировки — только в stderr, stdout занят протоколом.

## Инструменты

| Группа | Инструменты |
|---|---|
| каталог | `dap_search_nodes`, `dap_node_details`, `dap_node_dialog_schema` |
| workflow | `dap_workflow_create`, `dap_list_workflows`, `dap_delete_workflow`, `dap_save_workflow`, `dap_load_workflow` |
| граф | `dap_add_node`, `dap_configure_node`, `dap_connect`, `dap_disconnect`, `dap_remove_node` |
| запуск | `dap_run`, `dap_run_async`, `dap_job_status`, `dap_job_cancel`, `dap_list_jobs` |
| данные | `dap_get_table`, `dap_profile_table`, `dap_ingest` |
| рецепты | `dap_recipe_list`, `dap_recipe_save`, `dap_recipe_run` |
| SQL | `dap_sql` |
| сервис | `dap_shutdown` |

Всего 26 инструментов: 22 базовых плюс инструменты рецептов и `dap_sql`. Каталог
движка — 52 узла `dap.*`: `dap_search_nodes` и `dap_node_dialog_schema` видят их все,
включая узлы волн P2–P4 (`type_cast`, `duplicate_filter`, `pivot`, `window`,
`concatenate`, `describe`, `value_counts`, `correlation`, `histogram`, `quantile`,
`outlier_detect`, `resample`, `rolling`, `diff`, `date_range`, `excel_writer`,
`sql_query`, `db_reader`, `file_list`, `hypothesis_test`, `linear_regression`,
`markdown_report`, `chart`, `data_quality`). `dap_ingest`
один раз конвертирует CSV/Excel/JSON/Parquet в Parquet-кэш и возвращает `path`,
`rows`, `columns` и `cached`; `path` можно сразу передать в `dap.parquet_reader`
через `dap_add_node`.

Типовой проход агента: `dap_search_nodes` → `dap_node_dialog_schema` →
`dap_workflow_create` → `dap_add_node` → `dap_connect` → `dap_run` →
`dap_get_table`/`dap_profile_table` → `dap_save_workflow`. Для повторяющегося
файла перед `dap_add_node` вызывается `dap_ingest`.

Для адаптации пайплайна под задачу вместо ручной сборки графа есть три пути:
`dap_sql` (один read-only SQL по таблице узла), `dap_recipe_run` (сохранённый
пайплайн с параметрами) и промпты `analyze_csv`/`quality_check`/`profile_report`
(готовый план в три шага). Сценарий приёмки — ingest, ресурс `dap://recipes`,
рецепт или `dap_sql` — укладывается в три tool call и проверяется
`make mcp-recipe-e2e`.

## Ресурсы

Адаптер объявляет capability `resources` и отдаёт три вида URI:

| URI | Содержимое |
|---|---|
| `dap://catalog` | JSON каталога: `{"count":52,"nodes":[{"id","name","category","type","tier"}]}` |
| `dap://recipes` | JSON библиотеки: `{"recipes":[{"name","description","tags","params","dataset"}]}` |
| `dap://recipes/{name}` | workflow JSON рецепта (`dap-native-workflow/1`) с блоком `recipe` |

`resources/list` возвращает `dap://catalog` и `dap://recipes`; имена рецептов
берутся из `dap://recipes`. `resources/read` возвращает `contents[0]` с
`mimeType: application/json`. Чтение `dap://catalog` поднимает движок (как первый
tool call), библиотека рецептов читается с диска без движка. Неизвестный URI или
имя рецепта — JSON-RPC `-32602` с `data.uri` / `data.name`.

## Промпты

Адаптер объявляет capability `prompts`; `prompts/list` отдаёт три промпта, а
`prompts/get` — одно user-сообщение с готовым планом и подставленными аргументами:

| Промпт | Аргументы | Что делает |
|---|---|---|
| `analyze_csv` | `path` (обязательный), `question` | ingest → profile → опционально `dap_sql` под вопрос |
| `quality_check` | `path` | ingest → profile → SQL-проверки NULL/дублей/диапазонов |
| `profile_report` | `path`, `output` (по умолчанию `profile.md`) | ingest → `dap.markdown_report` → pass-through таблица |

Пропущенный обязательный аргумент — JSON-RPC `-32602` с `data.missing`.

## Рецепты

Рецепт — сохранённый workflow (`dap-native-workflow/1`), в который добавлен блок
`recipe`; файлы лежат в `$DAP_HOME/recipes/<name>.json`:

```json
{
  "format": "dap-native-workflow/1",
  "nodes": [
    {"id": "src", "factory": "dap.parquet_reader", "settings": {"file": "/data/sales.parquet"}},
    {"id": "q", "factory": "dap.sql_query", "settings": {"sql": "SELECT region, SUM(amount) AS total FROM src GROUP BY region", "inputs": ["src"]}},
    {"id": "report", "factory": "dap.markdown_report", "settings": {"file": "report.md", "title": "Sales summary", "sections": [{"kind": "table"}]}}
  ],
  "connections": [{"from": "src", "to": "q"}, {"from": "q", "to": "report"}],
  "recipe": {
    "name": "sales_summary",
    "description": "Aggregate sales by region and write a Markdown report",
    "tags": ["sales", "report"],
    "dataset": {"node": "src", "setting": "file"},
    "params": {"report": {"node": "report", "setting": "file"}},
    "output": "report"
  }
}
```

- `dataset` — узел и настройка, куда `dap_recipe_run` пишет аргумент `dataset`;
  без блока берётся первый reader с настройкой `file`.
- `params` — именованные переопределения настроек; `dap_recipe_run` применяет
  только объявленные имена и отвечает `-32602`, если имя неизвестно.
- `output` — узел, чья таблица возвращается; по умолчанию последний узел.
- `dap_recipe_save` принимает workflow JSON или id workflow (движок сохраняет его
  через `/v1/workflows/{id}/save`) и пишет файл; имя не может содержать `/`,
  обратный слэш, начинаться с точки или быть `.`/`..`.
- `dap_recipe_run` загружает копию рецепта через `/v1/workflows/load`, применяет
  параметры, выполняет workflow, возвращает `{recipe, workflow, node, table, applied}`
  и удаляет загруженный workflow; сохранённый файл не меняется, поэтому два
  параллельных запуска не делят состояние.
- `dap_recipe_list` возвращает `{recipes:[{name,description,tags,params,dataset}]}`.

## dap_sql

`dap_sql` добавляет в существующий workflow узел `dap.sql_query`, связывает его с
`node` (если передан), выполняет workflow и возвращает таблицу узла:

```json
{"workflow": "wf-1", "node": "1:1",
 "sql": "SELECT region, SUM(amount) AS total FROM \"1:1\" GROUP BY region"}
```

Ответ — `{"workflow":"wf-1","node":"1:2","table":{...}}`. Таблица входа
доступна в SQL под именем `node` (id узла; id вида `1:1` нужно взять в кавычки).
Принимается ровно один read-only `SELECT`/`WITH`: DDL/DML (`DROP`, `CREATE`,
`INSERT`, `ATTACH`, `COPY`, `PRAGMA` и т. п.) и несколько стейтментов через `;`
отклоняются адаптером с `-32602` до HTTP-запроса; строковые литералы и комментарии
не считаются SQL. Узел остаётся в workflow — его таблицу можно дочитать
`dap_get_table`; `limit` задаёт cap строк узла и размер превью.

## Локальные базы данных

SQLite/Postgres/MySQL читаются через расширения DuckDB — один раз ставим их в
каталог расширений, дальше всё работает офлайн:

```bash
make extensions   # sqlite, postgres, mysql, httpfs -> $DAP_HOME/build/duckdb-ext
```

```json
{"factory": "dap.db_reader", "settings": {"driver": "postgres",
  "url": "postgresql://dap@127.0.0.1:5432/analytics", "table": "orders"}}
```

Для MySQL — `driver: mysql` и `url: mysql://user@127.0.0.1:3306/db`. Логин и
пароль задаются полями `user`/`password` и никогда не попадают в `workflow.json`;
вместо `table` можно передать read-only `query`. Локальный Postgres 17 проверен
(таблица, профиль, агрегирующий запрос); MySQL использует тот же механизм — нужен
свой сервер.

## Сценарий за два вызова

Цель — из файла получить таблицу, статистику, отчёт и график без ручной сборки графа:

1. `dap_ingest {"file": "/data/sales.csv"}` — один раз конвертирует CSV/Excel/JSON в
   Parquet-кэш и возвращает путь.
2. `dap_recipe_run {"name": "local_analyst", "dataset": "<путь из шага 1>",
   "params": {"report": "/tmp/report.md", "chart": "/tmp/chart.svg"}}` — применяет
   датасет и параметры к сохранённому рецепту, запускает его и возвращает таблицу;
   отчёт и график пишутся как побочный эффект.

Рецепт — обычный `workflow.json` плюс блок `recipe`:

```json
{
  "format": "dap-native-workflow/1",
  "nodes": [
    {"id": "src", "factory": "dap.parquet_reader", "settings": {"file": "/data/sales.parquet"}},
    {"id": "q", "factory": "dap.sql_query", "settings": {
      "sql": "SELECT region, COUNT(*) AS orders, ROUND(SUM(amount), 2) AS total FROM src GROUP BY region ORDER BY total DESC",
      "inputs": ["src"]}},
    {"id": "report", "factory": "dap.markdown_report", "settings": {
      "file": "/tmp/report.md", "title": "Sales summary",
      "sections": [{"kind": "table"}, {"kind": "profile"}, {"kind": "metrics", "columns": ["total"]}]}},
    {"id": "chart", "factory": "dap.chart", "settings": {
      "file": "/tmp/chart.svg", "format": "svg", "type": "bar", "x": "region", "y": ["total"]}}
  ],
  "connections": [
    {"from": "src", "fromPort": 0, "to": "q", "toPort": 0},
    {"from": "q", "fromPort": 0, "to": "report", "toPort": 0},
    {"from": "report", "fromPort": 0, "to": "chart", "toPort": 0}
  ],
  "recipe": {
    "name": "local_analyst",
    "dataset": {"node": "src", "setting": "file"},
    "params": {"report": {"node": "report", "setting": "file"},
               "chart": {"node": "chart", "setting": "file"}},
    "output": "chart"
  }
}
```

Проверено end-to-end: 600 строк CSV → ingest → `dap_recipe_run` = таблица по
регионам (4 строки), `report.md` с таблицей/профилем/метриками и `chart.svg`.

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
make check                       # JUnit + goldens + MCP launch + оба MCP e2e + lint
make install-e2e                 # установка из dist/ во временный HOME + stdio preflight
make mcp-recipe-e2e              # resources/prompts/recipes/dap_sql, 3 tool call
python3 tests/mcp_e2e.py         # create/add/run/table через stdio
python3 tests/mcp_recipe_e2e.py  # сценарий адаптивности через stdio
```
