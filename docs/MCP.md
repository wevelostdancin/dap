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
- `tools/call` с `params._meta.progressToken` → до финального ответа адаптер пишет
  `notifications/progress` (см. [Прогресс](#прогресс));
- `resources/list` / `resources/read` → каталог узлов, рецепты и каталог датасетов;
- `prompts/list` / `prompts/get` → готовые user-сообщения для типовых анализов.

Ошибки — JSON-RPC `error`:

| Код | Смысл |
|---|---|
| `-32000` | движок вернул ошибку (`data.status`, `data.body`) |
| `-32001` | движок недоступен/упал/не поднялся; `data.log`, `data.tail` |
| `-32602` | неверные аргументы инструмента; неизвестный URI ресурса, рецепт или промпт; отсутствует обязательный аргумент |

Логи и трассировки — только в stderr, stdout занят протоколом.

## Прогресс

Если клиент прислал в `tools/call` `params._meta.progressToken`, адаптер пишет
уведомления до финального ответа:

```json
{"jsonrpc":"2.0","method":"notifications/progress",
 "params":{"progressToken":"tok-1","progress":1,"total":4,"message":"run"}}
```

- `dap_run` с токеном идёт через асинхронное задание (`POST /run` с
  `{"async":true}`), адаптер опрашивает `GET /v1/jobs/{id}` и транслирует
  `progress` движка (`nodesDone`/`nodesTotal`/`currentNode`) в `progress`/
  `total`/`message`; финальный ответ — результат задания для `succeeded` или сам
  job (со `state` и `error`) для `failed`/`cancelled`/`timeout`. Без токена
  `dap_run` синхронный, как раньше.
- `dap_analyze`, `dap_report`, `dap_profile`, `dap_estimate` и `dap_explore`
  сообщают стадии: `ingest`, затем `run`/`explore`/`estimate`, `table` и
  `profile` (если профиль запрошен).
- Без `progressToken` уведомлений нет, поведение и ответы не меняются.

## Инструменты

| Группа | Инструменты |
|---|---|
| каталог | `dap_search_nodes`, `dap_node_details`, `dap_node_dialog_schema` |
| workflow | `dap_workflow_create`, `dap_list_workflows`, `dap_delete_workflow`, `dap_save_workflow`, `dap_load_workflow` |
| граф | `dap_add_node`, `dap_configure_node`, `dap_connect`, `dap_disconnect`, `dap_remove_node` |
| запуск | `dap_run`, `dap_run_async`, `dap_job_status`, `dap_job_cancel`, `dap_list_jobs` |
| данные | `dap_get_table` (пагинация/проекция/фильтр/форматы), `dap_profile_table`, `dap_ingest`, `dap_dataset` |
| разведка | `dap_explore` (профиль + качество + связи + гипотезы), `dap_estimate` (rows/operators/plan до запуска) |
| рецепты | `dap_recipe_list`, `dap_recipe_save`, `dap_recipe_run` |
| SQL | `dap_sql` (один запрос, батч `queries[]`, `explain`) |
| одним вызовом | `dap_analyze` (файл → таблица + профиль, опционально SQL), `dap_report` (файл → таблицы + отчёт md/html/pdf/pptx + график), `dap_profile` (файл → только профиль), `dap_workflow_build` (узлы + связи + run + таблицы выходов) |
| сервис | `dap_shutdown` |

Всего 33 инструмента: 22 базовых, инструменты рецептов, `dap_sql`, `dap_analyze`,
`dap_report`, `dap_profile`, `dap_workflow_build` и P6-инструменты `dap_estimate`,
`dap_explore`, `dap_dataset`. Каталог
движка — 66 узлов `dap.*`: `dap_search_nodes` и `dap_node_dialog_schema` видят их все,
включая узлы волн P2–P4 (`type_cast`, `duplicate_filter`, `pivot`, `window`,
`concatenate`, `describe`, `value_counts`, `correlation`, `histogram`, `quantile`,
`outlier_detect`, `resample`, `rolling`, `diff`, `date_range`, `excel_writer`,
`sql_query`, `db_reader`, `file_list`, `hypothesis_test`, `linear_regression`,
`markdown_report`, `chart`, `data_quality`). `dap_ingest`
один раз конвертирует CSV/Excel/JSON/Parquet в Parquet-кэш и возвращает `path`,
`rows`, `columns` и `cached`; `path` можно сразу передать в `dap.parquet_reader`
через `dap_add_node`.

Для типовой задачи хватает одного-двух вызовов: `dap_explore` (профиль, качество,
связи и гипотезы), `dap_analyze` (файл → таблица и
профиль, опционально SQL), `dap_report` (файл → таблицы, отчёт md/html/pdf/pptx и
график), `dap_profile` (только компактный профиль) или `dap_workflow_build` (весь
граф, запуск и таблицы выходов одним вызовом). Перед тяжёлым запросом `dap_estimate`
показывает rows/operators/plan без материализации. Ручная сборка `dap_workflow_create`
→ `dap_add_node` → `dap_connect` → `dap_run` → `dap_get_table` нужна только для
нестандартного графа.

Все ответы с таблицами — `dap_get_table`, `dap_analyze`, `dap_report`, `dap_sql`,
`dap_recipe_run` и `dap_workflow_build` — проходят через один форматтер: значение
ячейки по умолчанию обрезается до 200 символов с многоточием, одна ячейка на
100k символов больше не вытесняет остальной контекст. У всех шести инструментов
есть `columns` (проекция), `maxCellChars` (0 — пустая ячейка) и `includeColumns`;
у пяти одновызовных — `tableFormat` (`json`/`objects`/`csv`/`markdown`), у
`dap_get_table` тот же выбор называется `format`. Постраничные поля `offset`/
`nextOffset`/`truncated` остаются только у `dap_get_table`: одновызовные
инструменты удаляют временный workflow и не предлагают дочитать таблицу потом.

Для адаптации пайплайна под задачу вместо ручной сборки графа есть три пути:
`dap_sql` (один read-only SQL по таблице узла, батч `queries` или `explain`),
`dap_recipe_run` (сохранённый пайплайн с параметрами) и промпты
`analyze_csv`/`quality_check`/`profile_report`
(готовый план на один-два вызова). Сценарий приёмки — ingest, ресурс `dap://recipes`,
рецепт или `dap_sql` — укладывается в три tool call и проверяется
`make mcp-recipe-e2e`.

## Ресурсы

Адаптер объявляет capability `resources` и отдаёт пять видов URI:

| URI | Содержимое |
|---|---|
| `dap://catalog` | JSON каталога: `{"count":52,"nodes":[{"id","name","category","type","tier"}]}` |
| `dap://recipes` | JSON библиотеки: `{"recipes":[{"name","description","tags","params","dataset"}]}` |
| `dap://recipes/{name}` | workflow JSON рецепта (`dap-native-workflow/1`) с блоком `recipe` |
| `dap://datasets` | JSON каталога датасетов: `{"count":1,"datasets":[{"name","description","rows","columns","version","updatedAt"}]}` |
| `dap://datasets/{name}` | запись датасета с `path`, `version` и кэшем `profile` |

`resources/list` возвращает `dap://catalog`, `dap://recipes` и `dap://datasets`;
имена рецептов берутся из `dap://recipes`, имена датасетов — из `dap://datasets`.
`resources/read` возвращает `contents[0]` с `mimeType: application/json`. Чтение
`dap://catalog` и `dap://datasets*` поднимает движок (как первый tool call),
библиотека рецептов читается с диска без движка. Неизвестный URI, имя рецепта или
датасета — JSON-RPC `-32602` с `data.uri` / `data.name`.

## Промпты

Адаптер объявляет capability `prompts`; `prompts/list` отдаёт десять промптов, а
`prompts/get` — одно user-сообщение с готовым планом и подставленными аргументами:

| Промпт | Аргументы | Что делает |
|---|---|---|
| `analyze_csv` | `path` (обязательный), `question` | один вызов `dap_analyze` (таблица + профиль, опционально `sql`); альтернативы — `dap_explore` (quality/связи/гипотезы) и `dap_profile` (только профиль); перед тяжёлым SQL — `dap_estimate` |
| `quality_check` | `path` | один вызов `dap_explore` (score, issues, профиль), опционально `dap_profile` |
| `profile_report` | `path`, `output` (по умолчанию `profile.md`) | один вызов `dap_report` (обёртка над `dap.markdown_report`, формат md/html/pdf/pptx); таблица и профиль возвращаются в ответе |
| `cohort_analysis` | `dataset` (обязательный), `report` | рецепт `cohort_analysis`: когорты и retention |
| `ab_test` | `dataset` (обязательный), `report` | рецепт `ab_test`: сравнение A/B |
| `time_series` | `dataset` (обязательный), `report` | рецепт `time_series`: ресемплинг и прогноз |
| `segmentation` | `dataset` (обязательный), `report` | рецепт `segmentation`: кластеры и сегменты |
| `regression` | `dataset` (обязательный), `report` | рецепт `regression`: линейная регрессия |
| `funnel` | `dataset` (обязательный), `report` | рецепт `funnel`: шаги и конверсия |
| `exec_report` | `dataset` (обязательный), `report` | рецепт `exec_report`: сводка и график |

Семь сценарных промптов сначала читают `dap://recipes/<name>`, затем вызывают
`dap_recipe_run` с `dataset` и `params.report` (по умолчанию `<name>.md`); при
несовпадении колонок — `dap_profile`/`dap_explore`, затем эквивалент через
`dap_add_node`/`dap_sql`. В ответе — таблица и путь отчёта.

Готовая библиотека — [recipes/README.md](../recipes/README.md): 8 рецептов и demo-данные
в `examples/data/`. Установленный архив кладёт их в `$DAP_HOME/recipes` и
`$DAP_HOME/examples`; `dap_recipe_list` показывает все восемь.

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

Батч: вместо `sql` можно передать `queries` — массив `{"sql","limit"?,"node"?}`.
Адаптер добавляет по узлу `dap.sql_query` на запрос (каждый со своим `inputs`),
выполняет workflow один раз и возвращает
`{"workflow":"wf-1","tables":[{"index":0,"node":"1:2","table":{...}}, ...]}`.
`sql` и `queries` взаимоисключающие; DDL в любом элементе отклоняется до HTTP.

С `explain: true` узел не выполняется: адаптер строит его и читает
`GET /v1/workflows/{wf}/nodes/{node}/estimate`, возвращая
`{"workflow","node","estimate":{"rows","operators","plan"}}` (для батча —
`{"workflow","estimates":[{"index","node","estimate"}]}`). Это дешёвый способ
понять стоимость запроса до материализации; `dap_estimate` делает то же для
файла/датасета целиком.

## dap_get_table

`dap_get_table` читает выходную таблицу узла и умеет отдавать её постранично и
компактно — чтобы не тратить контекст агента на широкие таблицы:

| Аргумент | Смысл |
|---|---|
| `port`, `limit` | порт узла и размер страницы (по умолчанию 20, 1–1000) |
| `offset` | пропустить N строк после фильтра (`>= 0`) |
| `columns` | проекция: массив имён или строка через запятую; меняет и порядок колонок |
| `where` | SQL-предикат над выходными колонками, например `total > 10`; запрещены `;`, `--`, `/*`, `SELECT`, `FROM` и `read_*` — иначе `-32602` с `data.code=invalid_filter` |
| `format` | `json` (по умолчанию), `objects` (строки-объекты по именам колонок), `csv`, `markdown` |
| `maxCellChars` | обрезать значение ячейки до N символов с многоточием (по умолчанию 200) |
| `includeColumns` | `false` — не возвращать список колонок (только данные) |

Ответ всегда содержит `offset`, `nextOffset` (null на последней странице) и
`truncated`; если движок их не прислал, адаптер считает их из `rows`/`data`.
Для `csv` и `markdown` данные приходят одной строкой:
`{"format":"csv","text":"...","rows":150,"offset":0,"nextOffset":20,"truncated":true}`.
Формат проверяется до HTTP-запроса. Те же `columns`, `maxCellChars` и
`includeColumns` принимают `dap_analyze`, `dap_report`, `dap_sql`, `dap_recipe_run`
и `dap_workflow_build`; там вместо `format` используется `tableFormat` (у
`dap_analyze` и `dap_report` имя `format` уже занято входным форматом/форматом
отчёта). Поля пагинации для этих пяти инструментов не возвращаются.

```json
{"name": "dap_get_table", "arguments": {"workflow": "wf-1", "node": "1:2",
 "offset": 20, "limit": 20, "columns": ["region", "total"],
 "where": "total > 10", "format": "csv"}}
```

## dap_estimate и dap_explore

`dap_estimate` отвечает на вопрос «сколько это будет стоить» до запуска: принимает
`file` или `dataset` (ровно один), опциональные `format`, read-only `sql` (вход
как `src`) и `limit` (cap строк узла `sql_query`), собирает временный workflow
`parquet_reader` → [`sql_query`] и читает `/estimate`:

```json
{"source": "/cache/sales.parquet", "cached": false, "node": "1:2",
 "rows": 150, "operators": [{"name": "SEQ_SCAN", "rows": 150}],
 "plan": {...}, "columns": [...]}
```

`dap_explore` делает разведочный анализ одним вызовом: ingest (для `file`) и
`POST /v1/explore` с `{dataset, sql?, maxColumns?, sampleRows?}`. Ответ —
`{source, cached, report}`, где `report` содержит `rows`, `columns` (профиль),
`quality` (score и issues), `relationships`, `group_differences`, `time`,
`hypotheses` и `recommendations`. Временных workflow адаптер не создаёт —
`/v1/explore` управляет ими сам.

## dap_dataset

`dap_dataset` — каталог датасетов: `action` = `list` (по умолчанию), `add`,
`show`, `refresh`, `remove`; имя — `[A-Za-z0-9._-]{1,64}`. Для `add` нужен
ровно один источник — `file` (ингестится) или `dataset` (готовый Parquet), плюс
опциональные `format`, `description`, `tags`, `owner`. `refresh` переингестит
источник и увеличивает `version`, `remove` удаляет запись. Ответ — JSON движка:

```json
{"name": "sales", "description": "Sales by region", "rows": 600, "columns": 5,
 "version": 2, "path": "/cache/sales.parquet", "profile": {...}}
```

Имя из каталога можно передавать как `dataset` в `dap_analyze`, `dap_profile`,
`dap_report`, `dap_explore` и `dap_estimate`: если значение не существует как
путь и похоже на имя каталога, адаптер делает `GET /v1/catalog/datasets/{name}` и
берёт `path`; на 404 значение используется как путь (прежнее поведение).

## dap_report и dap_profile

Два инструмента закрывают типовую задачу одним вызовом и не оставляют workflow в сессии.

`dap_report` принимает `file` или `dataset` (ровно один), опциональный read-only
`sql` (вход как `src`) или батч `queries` (`[{"sql","title"?,"limit"?}]`),
обязательный `report` — путь файла отчёта, и опциональные
`format` (`md` по умолчанию, `html`, `pdf`, `pptx` — уходит в `dap.markdown_report`;
устаревшие значения `csv`/`excel`/`json`/`parquet` трактуются как формат входа),
`chart` (путь `.svg`/`.png`), `chartType` (`bar`/`line`/`scatter`/`histogram`,
по умолчанию `bar`), `x`, `y`, `title`, `profile` (по умолчанию `true`), `limit`
(20) и `timeoutSec`. Адаптер собирает временный workflow `parquet_reader` →
[`sql_query`] → `markdown_report` → [`chart`], выполняет его, читает таблицу и профиль
и удаляет workflow в `finally`. Секции отчёта — `table` (лимит `limit`) и `profile`
(если `profile` не `false`). Ответ:

```json
{"source": "/cache/sales.parquet", "cached": false, "node": "1:2",
 "table": {"rows": 2, "columns": [...], "data": [...]},
 "profile": {"rows": 2, "columns": [...]},
 "report": "/tmp/report.md", "chart": "/tmp/chart.svg",
 "bytes": {"report": 512, "chart": 4096}}
```

`node` — узел, чья таблица возвращена (`sql_query` при `sql`, иначе `parquet_reader`).
`bytes` — размеры записанных файлов, если они видны процессу адаптера.

С `queries` отчёт получает несколько таблиц: на каждый запрос создаётся свой узел
`dap.sql_query`, подключённый к отдельному входному порту `dap.markdown_report`,
а секции выглядят как `[{"kind":"table","input":0,"limit":5,"title":"Totals"},
{"kind":"table","input":1,"limit":20},{"kind":"profile","input":0}]`. Ответ в этом
режиме — `{"source","cached","format","nodes":[...],"tables":[{"index","node","table"}],
"report","profile"?,"chart"?,"bytes"?}`. График по-прежнему строится из pass-through
таблицы отчёта.

`dap_profile` — тот же вход без SQL, отчёта и графика: `file` или `dataset`, `format`
и `timeoutSec`. Возвращает `{source, cached, profile, rows, columns}` без таблицы:
`rows` — строк в источнике, `columns` — число колонок профиля. Аргумента `limit`
у инструмента нет: движок `/profile` не читает этот параметр (value count rows он
пока не отдаёт), поэтому адаптер его не принимает: параметр ничего не менял.

```json
{"name": "dap_report", "arguments": {"file": "/data/sales.csv",
 "report": "/tmp/report.md", "chart": "/tmp/chart.svg",
 "sql": "SELECT region, SUM(amount) AS total FROM src GROUP BY region",
 "x": "region", "y": ["total"], "title": "Sales summary"}}
```

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
        reconnect:
          enabled: true
```

После ручной правки DSH перезапустите; `dap-mcp doctor` проверяет сервер напрямую
через stdio и не зависит от CLI клиента.

## Проверка

```bash
make check                       # JUnit + goldens + MCP launch + оба MCP e2e + lint
make install-e2e                 # установка из dist/ во временный HOME + stdio preflight
make mcp-recipe-e2e              # resources/prompts/recipes/dap_sql/report/profile, 3 tool call
python3 tests/mcp_e2e.py         # create/add/run/table через stdio
python3 tests/mcp_recipe_e2e.py  # сценарий адаптивности через stdio
```
