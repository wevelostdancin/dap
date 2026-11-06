# ARCHITECTURE — движок dap-native

Документ описывает нативный движок `engine-native/`: компоненты, жизненный цикл запроса,
память и ресурсы DuckDB, отмену и MCP-адаптер. Контракт HTTP — `api/openapi.yaml`,
каталог узлов — [NODES.md](NODES.md), правила проверки — [QUALITY.md](QUALITY.md),
план — [ROADMAP.md](ROADMAP.md).

## 1. Общая картина

`dap-native` — один JVM-процесс на Java 21: Javalin 6.7.0, DuckDB 1.5.6.0 (in-memory),
Arrow 19.0.0, Jackson 2.19.0. Снаружи — HTTP-контракт `/v1` (26 операций) и каталог из
11 узлов `dap.*`. Workflow, задания и результаты живут в памяти процесса; на диск
сохраняется только то, что записано через `POST /v1/workflows/{id}/save`.
Аутентификации нет, по умолчанию сервис слушает `127.0.0.1`.

```
HTTP (Javalin, virtual threads)
  ├─ X-DAP-Session ─► session ("default")
  ├─ /v1/health, /v1/nodes, /v1/workflows, /v1/jobs
  └─ handlers: Catalog / Workflow / Job / Data / Ops
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
                 └─ преобразования: SQL-отношения
```

## 2. Компоненты

| Компонент | Ответственность |
|---|---|
| `NativeService` | Точка входа: читает `Settings` из env, собирает `ServiceState`, регистрирует маршруты `/v1`, отображает исключения в `{"error": ...}`, останавливает сервис |
| `ServiceState` | Реестр узлов, `WorkflowStore`, `JobManager` и по одной `DuckDbSession` на сессию; закрывает их вместе с сервисом |
| `NodeRegistry`, `NodeSpec` | Читают `specs/index.txt` и `specs/*.json` из classpath; поиск по каталогу; `configSchema` → поля `/dialog` |
| `Executors`, `NodeExecutor`, `NodeContext` | Регистрация 11 исполнителей по id `dap.*`; исполнитель получает config, сессию, job-соединение и входные `LazyPlan` |
| `WorkflowStore`, `Workflow` | Workflow в памяти по сессиям, id `wf-N`, лимит на сессию, граф узлов и связей, снимок графа на run, состояния узлов |
| `JobManager`, `Job` | Постановка run в виртуальный поток, сериализация запусков одного workflow, watchdog по дедлайну, отмена, состояния задания |
| `Dag` | Исполнение снимка графа уровнями Кана: узлы уровня параллельны, следующий уровень — после предыдущего; падение узла отменяет соседей |
| `LazyPlan` | Ленивая таблица: текст SQL, схема и сессия; данные читаются только в `/table` и `/profile` |
| `DuckDbSession` | In-memory БД сессии: базовое соединение, `duplicate()`, ресурсные настройки, ingestion, preview/profile, учёт активных чтений |
| `ArrowTable`, `CsvParser`, `CsvTableReader`, `XlsxTableReader` | Граница данных на входе: разбор CSV/XLSX в Arrow, затем построчная вставка в DuckDB |
| `CatalogHandlers`, `WorkflowHandlers`, `JobHandlers`, `DataHandlers`, `OpsHandlers` | HTTP-слой: валидация запросов, коды ошибок, сериализация ответов |
| MCP-адаптер (`adapters/mcp/dap_mcp.py`) | Отдельный Python-процесс: stdio JSON-RPC 2.0, 21 инструмент `dap_*`, при необходимости поднимает jar |

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

Исключение из ленивости: `csv_reader` и `excel_reader` читают файл и загружают
таблицу уже на run, поэтому ошибки файла и схемы видны в run, а не в `/table`.

## 4. Память и ресурсы DuckDB

- `DuckDbSession` открывает `jdbc:duckdb:` — отдельная in-memory БД на сессию,
  создаётся лениво при первом run и закрывается вместе с сервисом. На базовом
  соединении выставляется `TimeZone='UTC'`; `duplicate()` даёт соединения, видящие
  тот же каталог.
- Ресурсные настройки задаются только через env и применяются на базовом соединении
  при создании БД; `duplicate()` их наследует, потому что это настройки уровня БД:

  | Переменная | Действие | Пример |
  |---|---|---|
  | `DAP_DUCKDB_MEMORY_LIMIT` | `SET memory_limit` | `2GB` |
  | `DAP_DUCKDB_THREADS` | `SET threads`, положительное целое | `3` |
  | `DAP_DUCKDB_TEMP_DIR` | `SET temp_directory`; каталог создаётся | `/var/tmp/dap` |

  Незаданные переменные оставляют умолчания DuckDB. Эффективные значения (после
  нормализации, например `2GB` → `1.8 GiB`) читаются через `current_setting` и
  отдаются в `GET /v1/workflows/{id}/debug` полем `duckdb`.
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
  Сессионные БД и завершённые jobs не эвиктятся; таблицы исходников занимают одну
  стабильную таблицу на узел.

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

## 6. MCP-адаптер

`adapters/mcp/dap_mcp.py` — тонкий stdio-транспорт: JSON-RPC 2.0, по одному объекту
на строку, только стандартная библиотека Python. Вычислений в адаптере нет: он вызывает
`/v1` и возвращает результат как текст.

- 21 инструмент `dap_*`: каталог и схемы настроек, сборка workflow, запуск
  (sync/async), задания, таблица, профиль, сохранение и загрузка, `dap_shutdown`.
- Сессия задаётся один раз переменной `DAP_SESSION` и уходит заголовком
  `X-DAP-Session`; чужой `wf-*`/`job-*` движок не покажет.
- Если `GET /v1/health` не отвечает, адаптер запускает
  `java -Xmx${DAP_HEAP:-2g} -jar engine-native/target/dap-native.jar`, ждёт health
  до 60 с и опрашивает дочерний процесс. При мгновенном выходе java или таймауте
  health собственный процесс гасится, ошибка `-32001` содержит хвост лога. Уже
  работающий сервис адаптер не перезапускает и не останавливает.
- Переменные: `DAP_HOME`, `DAP_PORT`, `DAP_SESSION`, `DAP_BIND`, `DAP_HEAP`;
  HTTP-таймаут 600 с. Для долгих workflow предпочтителен `dap_run_async` +
  `dap_job_status`.
- Ошибки: JSON-RPC `-32000` — движок вернул ошибку (`data.status`, `data.body`),
  `-32001` — движок недоступен или не поднялся.

## 7. Границы

- Один пользователь, без auth и multi-tenancy; сервис должен слушать loopback.
  `csv_reader`/`excel_reader` читают любой путь, доступный процессу.
- Состояние в памяти: workflow, jobs и таблицы исчезают при остановке процесса.
  `POST /v1/workflows/{id}/save` пишет `workflow.json` (формат `dap-native-workflow/1`),
  `POST /v1/workflows/load` создаёт workflow из такого файла.
- Остановка явная: `POST /v1/shutdown` (ответ 200, закрытие через ~100 мс) или
  сигнал процессу. Автозавершение по простою (`DAP_IDLE_TIMEOUT_SEC`) в движке
  пока не реализовано — это работа P1 по [ROADMAP.md](ROADMAP.md).
- Каталог — 11 узлов; расширение идёт по правилу «спека + исполнитель + goldens + docs».
