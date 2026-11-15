# ARCHITECTURE — движок dap-native

Документ описывает нативный движок `engine-native/`: компоненты, жизненный цикл запроса,
память и ресурсы DuckDB, отмену и MCP-адаптер. Контракт HTTP — `api/openapi.yaml`,
каталог узлов — [NODES.md](NODES.md), правила проверки — [QUALITY.md](QUALITY.md),
план — [ROADMAP.md](ROADMAP.md).

## 1. Общая картина

`dap-native` — один JVM-процесс на Java 21: Javalin 6.7.0, DuckDB 1.5.6.0 (in-memory),
Arrow 19.0.0, Jackson 2.19.0. Снаружи — HTTP-контракт `/v1` (27 операций) и каталог из
52 узлов `dap.*`. Workflow, задания и результаты живут в памяти процесса; на диск
попадают только результат `POST /v1/workflows/{id}/save` и Parquet-кэш
`POST /v1/datasets`.
Аутентификации нет, по умолчанию сервис слушает `127.0.0.1`. Каталог — 52 узла и 52
исполнителя, по одному на узел; проверки — 534 JUnit-теста в 90 классах и 25
golden-workflow ([QUALITY.md](QUALITY.md)).

```
HTTP (Javalin, virtual threads)
  ├─ X-DAP-Session ─► session ("default")
  ├─ /v1/health, /v1/nodes, /v1/workflows, /v1/jobs, /v1/datasets
  └─ handlers: Catalog / Workflow / Job / Data / Dataset / Ops
        │
        ▼
ServiceState ── WorkflowStore (in-memory, wf-N, per session)
        │        JobManager (virtual threads, watchdog, cancel)
        │        ConcurrentHashMap<session, DuckDbSession>
        ▼
JobManager ──► Dag (уровни Кана, параллельные ветви)
                 │
                 ▼
              NodeExecutor (dap.*) ──► LazyPlan (SQL + схема)
                 │
                 ▼
              DuckDbSession (одна in-memory БД на сессию)
                 ├─ base connection; duplicate() видит тот же каталог
                 ├─ csv_reader / excel_reader: Arrow → Appender → таблица
                 ├─ parquet_reader / json_reader: read_parquet / read_json_auto
                 ├─ csv_writer / parquet_writer / json_writer: COPY ... TO
                 ├─ excel_writer: zip + XML (без POI), атомарное переименование
                 └─ преобразования: SQL-отношения
```

### Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `DAP_PORT` | `7800` | порт HTTP |
| `DAP_BIND` | `127.0.0.1` | адрес прослушивания (`localhost`, `0.0.0.0`) |
| `DAP_MAX_WORKFLOWS` | `100` | лимит workflow на сессию |
| `DAP_JOB_TIMEOUT` | `600` | дедлайн job, с |
| `DAP_IDLE_TIMEOUT_SEC` | `900` | простой до автозавершения, с; `0` выключает |
| `DAP_QUERY_TIMEOUT_SEC` | `60` | предел материализации `/table` и `/profile`, с; `0` выключает |
| `DAP_MAX_SESSIONS` | `64` | максимум открытых сессионных БД, LRU-эвикция |
| `DAP_SESSION_TTL_SEC` | `1800` | простой сессии до эвикции, с; `0` выключает |
| `DAP_MAX_RETAINED_JOBS` | `200` | cap терминальных заданий на сессию, старейшие удаляются |
| `DAP_DUCKDB_EXTENSION_DIR` | `$DAP_HOME/build/duckdb-ext`, иначе `~/.dap/extensions` | каталог расширений DuckDB (`--install-extensions`) |
| `DAP_CACHE_DIR` | `$DAP_HOME/build/cache`, иначе `~/.dap/cache` | каталог Parquet-кэша `POST /v1/datasets` |
| `DAP_DUCKDB_MEMORY_LIMIT` | умолчание DuckDB | `SET memory_limit` |
| `DAP_DUCKDB_THREADS` | умолчание DuckDB | `SET threads` |
| `DAP_DUCKDB_TEMP_DIR` | умолчание DuckDB | `SET temp_directory` |
| `DAP_WARMUP` | `1` | прогрев движка при старте в фоновом потоке; `0` выключает |

Нецелое или отрицательное значение `DAP_DUCKDB_THREADS`/`DAP_IDLE_TIMEOUT_SEC`/
`DAP_QUERY_TIMEOUT_SEC`/`DAP_SESSION_TTL_SEC`, а также неположительные
`DAP_MAX_SESSIONS`/`DAP_MAX_RETAINED_JOBS` — ошибка конфигурации: `main`
печатает сообщение и завершается с кодом 2.
Ресурсы DuckDB — §4.

`DAP_CACHE_DIR` используется как есть. Без него каталог выбирается так:
`$DAP_HOME/build/cache` (адаптер выставляет `DAP_HOME`), иначе `~/.dap/cache`;
если выбранный каталог нельзя создать (sandbox), берётся `$TMPDIR/dap-cache`.

## 2. Компоненты

| Компонент | Ответственность |
|---|---|
| `NativeService` | Точка входа: читает `Settings` из env, собирает `ServiceState`, регистрирует маршруты `/v1`, отображает исключения в `{"error": ...}`, останавливает сервис |
| `ServiceState` | Реестр узлов, `WorkflowStore`, `JobManager` и по одной `DuckDbSession` на сессию; закрывает их вместе с сервисом |
| `NodeRegistry`, `NodeSpec` | Читают `specs/index.txt` и `specs/*.json` из classpath; поиск по каталогу; `configSchema` → поля `/dialog` |
| `Executors`, `NodeExecutor`, `NodeContext` | Регистрация 44 исполнителей по id `dap.*`; исполнитель получает config, сессию, job-соединение и входные `LazyPlan` |
| `WorkflowStore`, `Workflow` | Workflow в памяти по сессиям, id `wf-N`, лимит на сессию, граф узлов и связей, снимок графа на run, состояния узлов |
| `JobManager`, `Job` | Постановка run в виртуальный поток, сериализация запусков одного workflow, watchdog по дедлайну, отмена, состояния задания |
| `Dag` | Исполнение снимка графа уровнями Кана: узлы уровня параллельны, следующий уровень — после предыдущего; падение узла отменяет соседей |
| `LazyPlan` | Ленивая таблица: текст SQL, схема и сессия; данные читаются только в `/table` и `/profile` |
| `DuckDbSession` | In-memory БД сессии: базовое соединение, `duplicate()`, ресурсные настройки, ingestion, preview/profile, учёт активных чтений |
| `ArrowTable`, `CsvParser`, `CsvTableReader`, `XlsxTableReader` | Граница данных на входе: разбор CSV/XLSX в Arrow, затем построчная вставка в DuckDB |
| `IngestService` | Parquet-кэш `POST /v1/datasets`: конвертация CSV/Excel/JSON/Parquet, ключ SHA-256 (путь, размер, mtime, формат), ответ `path/rows/columns/cached` |
| `CatalogHandlers`, `WorkflowHandlers`, `JobHandlers`, `DataHandlers`, `DatasetHandlers`, `OpsHandlers` | HTTP-слой: валидация запросов, коды ошибок, сериализация ответов |
| MCP-адаптер (`adapters/mcp/dap_mcp.py`) | Отдельный Python-процесс: stdio JSON-RPC 2.0, 30 инструментов `dap_*`, при необходимости поднимает jar |

## 3. Жизненный цикл запроса

1. Javalin принимает запрос на виртуальном потоке. Сессия берётся из `X-DAP-Session`,
   затем из `?session=`, иначе `default`; id проверяется по `[A-Za-z0-9._-]{1,64}`,
   иначе 400. Валидная сессия возвращается в ответе.
2. Обработчик находит объект в `ServiceState`. Чужие и несуществующие id неотличимы:
   `wf-N` и `job-N` сквозные в процессе, но видны только своей сессии (404).
3. `POST .../run` или `POST .../jobs` создаёт `job-N` и ставит run в
   `JobManager`. Sync-режим ждёт завершения на виртуальном потоке запроса, поэтому
   health и остальные маршруты отвечают во время долгого run.
4. Поток run берёт fair-lock workflow (запуски одного workflow сериализованы, разные
   workflow идут параллельно), сбрасывает результаты предыдущего run и открывает
   job-соединение DuckDB.
5. `Dag` берёт `Workflow.snapshot()`: узлы, связи и deep-copy конфигов. Правки графа
   во время run на исполнение не влияют. Проверяются зарегистрированные исполнители и
   связность входных портов.
6. Узлы выполняются уровнями: все узлы уровня стартуют одновременно на виртуальных
   потоках, следующий уровень — после завершения предыдущего. Исполнитель получает
   `NodeContext` и входные `LazyPlan`, возвращает новый `LazyPlan`; узел получает
   состояние `RUNNING`, затем `EXECUTED` или `ERROR` с сообщением.
7. Первое падение узла отменяет `Future` соседей по уровню и закрывает job-соединение;
   run завершается состоянием `failed`, `cancelled` или `timeout`.
8. Ответ: sync — `200` с `RunResult` (`ok`, `durationMs`, `workflow`, состояния
   узлов), `408` при timeout, `500` при ошибке; async — `202` с объектом job.
9. `GET .../nodes/{node}/table` и `.../profile` не перезапускают workflow: они
   материализуют сохранённый план на отдельном `duplicate()`-соединении. `/table`
   делает `COUNT(*)` и `LIMIT` (по умолчанию 50 строк, `port` по умолчанию 0),
   `/profile` — полный проход с агрегатами по колонкам.

Исключения из ленивости: `csv_reader` и `excel_reader` читают файл и загружают
таблицу уже на run, поэтому ошибки файла и схемы видны в run, а не в `/table`.
`parquet_reader` и `json_reader` проверяют файл и выводят схему на run
(`DESCRIBE`), но строки читают лениво; писатели (`csv_writer`, `parquet_writer`,
`json_writer`) выполняют `COPY ... TO` на run и возвращают входной план без
изменений (pass-through).

`POST /v1/datasets` идёт вне workflow: `IngestService` конвертирует
CSV/Excel/JSON/Parquet в Parquet-файл в кэше и отвечает `{path, rows, columns,
cached}`. Ключ кэша — SHA-256 от абсолютного пути, размера, mtime и формата:
неизменённый файл отдаёт существующий Parquet с `cached: true`. Конвертация
выполняется в отдельной in-memory `DuckDbSession`, которая закрывается после
записи; кэш переживает перезапуск движка.

## 4. Память и ресурсы DuckDB

- `DuckDbSession` открывает `jdbc:duckdb:` — отдельная in-memory БД на сессию,
  создаётся лениво при первом run и закрывается вместе с сервисом. Стартовый
  прогрев (§6) использует одноразовую сессию, закрывает её и в `sessions` не
  попадает. На базовом
  соединении выставляется `TimeZone='UTC'`; `duplicate()` даёт соединения, видящие
  тот же каталог.
- Ресурсные настройки задаются только через env и применяются на базовом соединении
  при создании БД; `duplicate()` их наследует, потому что это настройки уровня БД:

  | Переменная | Действие | Пример |
  |---|---|---|
  | `DAP_DUCKDB_MEMORY_LIMIT` | `SET memory_limit` | `2GB` |
  | `DAP_DUCKDB_THREADS` | `SET threads`, положительное целое | `3` |
  | `DAP_DUCKDB_TEMP_DIR` | `SET temp_directory`; каталог создаётся | `/var/tmp/dap` |
  | `DAP_DUCKDB_EXTENSION_DIR` | `SET extension_directory`, `SET autoinstall_known_extensions=false`; каталог создаётся | `~/.dap/extensions` |

  Незаданные переменные оставляют умолчания DuckDB. Эффективные значения (после
  нормализации, например `2GB` → `1.8 GiB`) читаются через `current_setting` и
  отдаются в `GET /v1/workflows/{id}/debug` полем `duckdb`.
- Расширения DuckDB ставятся только явно: `java -jar dap-native.jar
  --install-extensions sqlite postgres httpfs` кладёт их в
  `DAP_DUCKDB_EXTENSION_DIR` (по умолчанию `$DAP_HOME/build/duckdb-ext`, иначе
  `~/.dap/extensions`) и выходит с кодом 0/1. Сессионные БД указывают на этот
  каталог и не включают autoinstall, поэтому в рантайме сеть не нужна.
  `GET /v1/health` отдаёт `extensions.installed` — имена установленных
  расширений.
- `csv_reader`/`excel_reader` грузят таблицу на job-соединении под стабильным именем
  `csv_<wf>_<node>` / `excel_<wf>_<node>` через `CREATE OR REPLACE TABLE` и
  `DuckDBAppender`. Ре-ран заменяет ту же таблицу, а не копит копии; закрытие
  job-соединения прерывает ingestion. Вставки сериализованы на соединении: две
  независимые ветви `csv_reader` в одном уровне DAG не интерливают результаты DuckDB.
- Zero-copy Arrow → DuckDB нет: публичный `registerArrowStream` в `duckdb_jdbc`
  1.5.6.0 требует `arrow-c-data` (`ArrowArrayStream`), которого нет в зависимостях,
  поэтому данные копируются построчно. Arrow остаётся границей ingestion, между
  узлами Arrow не передаётся.
- Arrow-таблицы CSV/Excel живут в JVM heap, а `DAP_DUCKDB_MEMORY_LIMIT` ограничивает
  только буферы DuckDB. Замер на 10 млн строк: ingest требует ~4,65 ГБ heap при
  `-Xmx6g` ([BENCH.md](BENCH.md)).
- Потолок памяти процесса — примерно `sessions × DAP_DUCKDB_MEMORY_LIMIT` плюс heap.
  Сессионные БД эвиктятся по `DAP_SESSION_TTL_SEC`/LRU-лимиту `DAP_MAX_SESSIONS`,
  история завершённых jobs ограничена `DAP_MAX_RETAINED_JOBS`; таблицы исходников
  занимают одну стабильную таблицу на узел и удаляются вместе с workflow.
- Parquet-кэш `POST /v1/datasets` лежит на диске, в heap не учитывается и не
  эвиктится; каталог общий для всех сессий и переживает перезапуск процесса.
  По умолчанию это `$DAP_HOME/build/cache` (когда `DAP_HOME` задан, например
  адаптером), иначе `~/.dap/cache`; в sandbox — `$TMPDIR/dap-cache`.

## 5. Отмена, таймауты и состояния

- Состояния job: `queued → running → succeeded | failed | cancelled | timeout`;
  терминальные состояния не меняются. Дедлайн считается от постановки в очередь:
  `createdAt + timeoutSec`, по умолчанию 600 с (`DAP_JOB_TIMEOUT`, `timeoutSec`
  в запросе переопределяет).
- Watchdog — отдельный демон-поток: по дедлайну помечает job как timeout, отменяет
  `Future` и закрывает job-соединение, что прерывает нативный запрос DuckDB.
- `DELETE /v1/jobs/{id}` отменяет только активный job: `requestCancel()`,
  `Future.cancel(true)`, закрытие job-соединения. `queued`-job становится
  `cancelled` сразу. Для завершённого job — 409, для чужого/неизвестного — 404.
- `/table` и `/profile` учитываются в `DuckDbSession` (соединение + workflow + node).
  Отмена running-job того же workflow вызывает `interruptReads(workflowId)`; закрытие
  сессии и `POST /v1/shutdown` закрывают все активные чтения. Нативный запрос
  прерывается за 0–1 мс. Отмена `queued`-job чтения соседа не трогает.
- Прерванный `/table`/`/profile` отвечает 500 с сообщением драйвера об interruption.
- Узел, игнорирующий прерывание, может удерживать run-поток до возврата нативного
  вызова; состояние job при этом всё равно терминально. `math_formula` выводит тип
  через `DESCRIBE ... LIMIT 0` на отдельном короткоживущем соединении — этот
  bind-only запрос job-cancel не прерывает.

## 6. Старт, остановка и idle watchdog

- Старт: `main` собирает `Settings` из env, создаёт `NativeService` и вызывает
  `start(port)`. Javalin начинает слушать первым, поэтому `GET /v1/health` отвечает
  сразу; затем демон-поток `dap-warm-up` прогревает движок: временная
  `DuckDbSession`, `SELECT 1`, CSV из трёх строк в `DAP_DUCKDB_TEMP_DIR` (иначе
  `$TMPDIR`), разбор настоящим путём `csv_reader` (`CsvTableReader` → `ArrowTable`
  → `DuckDBAppender`), `describe` и `preview`, затем сессия закрывается, а файл
  удаляется. Так инициализация DuckDB JDBC (~1,5 с) и JIT уходят в фоновый поток,
  а не в старт/health; вызов, пришедший после прогрева, их уже не платит (замер —
  `tests/measure_warmup.py`). Прогрев идёт один раз на процесс, его ошибка
  только пишется в stderr и не мешает старту; стоимость фиксирует строка
  `dap-native: warm-up completed in N ms`. Выключается `DAP_WARMUP=0` (или
  `-Ddap.warmup=0`); системное свойство важнее переменной окружения.
- Явная остановка: `POST /v1/shutdown` отвечает 200 и запускает закрытие в
  отдельном демон-потоке через ~100 мс; сигнал процессу обрабатывает shutdown hook.
  В обоих случаях закрываются job manager, HTTP-сервер и все сессионные БД.
- Автозавершение по простою: `DAP_IDLE_TIMEOUT_SEC` (по умолчанию 900 с, `0`
  выключает). Демон-поток `dap-idle-watchdog` проверяет простой с периодом
  `min(30 с, max(1 с, таймаут/2))`.
- Любой запрос — совпавший с маршрутом или нет — обновляет отметку активности,
  включая `GET /v1/health`.
- Остановка откладывается, пока есть активные jobs в любой сессии: длинный run не
  обрывается по простою. Если сервис уже останавливается, watchdog ничего не делает.
- Срабатывание watchdog идёт тем же путём, что `POST /v1/shutdown`
  (`shutdownAsync`), и так же теряет незавершённые workflow.
- Отрицательное или нецелое значение переменной — ошибка конфигурации: `main`
  печатает сообщение и завершается с кодом 2.

## 7. MCP-адаптер

`adapters/mcp/dap_mcp.py` — тонкий stdio-транспорт: JSON-RPC 2.0, по одному объекту
на строку, только стандартная библиотека Python. Вычислений в адаптере нет: он вызывает
`/v1` и возвращает результат как текст.

- 30 инструментов `dap_*`: каталог и схемы настроек, сборка workflow, запуск
  (sync/async), задания, таблица, профиль, ingest в Parquet-кэш (`dap_ingest`),
  сохранение и загрузка, `dap_shutdown`.
- Сессия задаётся один раз переменной `DAP_SESSION` и уходит заголовком
  `X-DAP-Session`; чужой `wf-*`/`job-*` движок не покажет.
- Если `GET /v1/health` не отвечает, адаптер запускает
  `java -Xmx${DAP_HEAP:-2g} -jar engine-native/target/dap-native.jar`, ждёт health
  до 60 с и опрашивает дочерний процесс. При мгновенном выходе java или таймауте
  health собственный процесс гасится, ошибка `-32001` содержит хвост лога. Уже
  работающий сервис адаптер не перезапускает и не останавливает.
- Переменные: `DAP_HOME`, `DAP_PORT`, `DAP_SESSION`, `DAP_BIND`, `DAP_HEAP`;
  HTTP-таймаут 600 с. Остальные `DAP_*` (`DAP_IDLE_TIMEOUT_SEC`, `DAP_CACHE_DIR`,
  ресурсы DuckDB) адаптер не читает, но передаёт дочернему процессу вместе с
  окружением. Для долгих workflow предпочтителен `dap_run_async` + `dap_job_status`.
- Ошибки: JSON-RPC `-32000` — движок вернул ошибку (`data.status`, `data.body`),
  `-32001` — движок недоступен или не поднялся.

## 8. Границы

- Один пользователь, без auth и multi-tenancy; сервис должен слушать loopback.
  Все файловые узлы читают и пишут любой путь, доступный процессу.
- Состояние в памяти: workflow, jobs и таблицы исчезают при остановке процесса.
  `POST /v1/workflows/{id}/save` пишет `workflow.json` (формат `dap-native-workflow/1`),
  `POST /v1/workflows/load` создаёт workflow из такого файла. На диске остаются
  только явно сохранённый `workflow.json` и Parquet-кэш `POST /v1/datasets`.
- Остановка: `POST /v1/shutdown` (ответ 200, закрытие через ~100 мс), сигнал
  процессу или простой дольше `DAP_IDLE_TIMEOUT_SEC` без активных jobs (§6).
- Каталог — 52 узла; расширение идёт по правилу «спека + исполнитель + goldens + docs».
