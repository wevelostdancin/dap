# NATIVE — движок dap-native

**Статус:** Фазы 1–3 закрыты (29–30.10.2026, ветка `phase3-native`): каталог из 11 узлов
(10 пилотных + `column_renamer`), планировщик, 11 исполнителей и полный HTTP-слой `/v1`
работают; parity 13/13. Приёмка Фаз 1–3 — §5–5.2.
**Область:** `engine-native/`; **связанные:** [ROADMAP-NATIVE.md](ROADMAP-NATIVE.md),
[PARITY.md](PARITY.md), [API.md](API.md), [../ARCHITECTURE.md](../ARCHITECTURE.md).

## 1. Назначение и статус

`dap-native` — один JVM-процесс: Javalin 6.7 + DuckDB 1.5.6.0 + Arrow 19.0.0, контракт
`/v1` из `api/openapi-v1-frozen.yaml`. Фаза 1 начиналась с 5 доступных узлов и 10
спецификаций; сейчас доступны все 11 (10 пилотных + `column_renamer`), ленивые SQL-планы и
in-memory модель workflow/job. 832 узла, auth, multi-tenancy, persistence и HA в пилот не
входят.

| Что | Состояние на этой ветке |
|---|---|
| Сборка | `pom.xml`: javalin 6.7.0, duckdb_jdbc 1.5.6.0, arrow-vector/memory-netty 19.0.0, jackson 2.19.0, junit 5.11.4. `./mvnw -B test` — 203 теста, 0 падений |
| Каталог | 11 JSON-спеков + `index.txt`; `NodeRegistry`; все 11 с `available:true` (10 пилотных + `column_renamer`); `/dialog` строится из `configSchema` |
| HTTP | Все 25 операций frozen-контракта `/v1` зарегистрированы в `NativeService`; `X-DAP-Session`, коды ошибок и Job-модель по frozen-контракту; 16 интеграционных HTTP-тестов |
| Исполнение | `WorkflowStore`, `Dag`, `JobManager`, 11 executor-ов; runs одного workflow сериализованы, разные — параллельны; snapshot графа на run |
| Parity | `tests/parity.py --engine native`: 13/13 workflow, `matching cells 1563/1563 (100 %)`; `tests/native_smoke.py` — 9/9; MCP e2e (`tests/mcp_e2e.py`) — PASS |
| Данные | `DuckDbSession`, `ArrowTable`, `LazyPlan`, `CsvParser`/`CsvTableReader`; одна in-memory БД на сессию (`ServiceState`), ingestion CSV идёт через job-connection |
| Вне пилота | auth, persistence, HA, 832 узла, изменения `engine/`, `runtime/knime/`, `catalog/` |

Проверка: `tests/native_smoke.py` (9 чёрных проверок по HTTP) и `NativeServiceTest`
(16 интеграционных тестов) проходят на собранном jar.

## 2. Архитектура

```
HTTP (Javalin 6.7)
  ├─ X-DAP-Session ──► session (default)
  ├─ /v1, /v1/health
  └─ nodes / workflows / jobs / table / profile   (регистрируются в NativeService)
        │
        ▼
WorkflowStore (in-memory, wf-N, per session)
        │
        ▼
JobManager (virtual threads, watchdog, cancel, timeout)
        │
        ▼
Dag (Kahn levels, parallel branches)
        │
        ▼
NodeExecutor (dap.*) ──► LazyPlan (SQL text + schema)
        │
        ▼
DuckDbSession (one in-memory DuckDB per session)
  ├─ base connection; duplicate() shares the catalog
  ├─ csv_reader: CsvParser → CsvTableReader → ArrowTable → Appender → table
  └─ transforms: SQL relations; /table и /profile материализуют результат
```

### 2.1 HTTP

- Javalin 6.7.0 на JDK 21; JSON UTF-8, заголовок `X-DAP-Api-Version: 1`.
- Сессия — `X-DAP-Session` или `?session=`, по умолчанию `default`; допустимый id:
  `[A-Za-z0-9._-]{1,64}`, иначе 400. Валидная сессия возвращается в ответе.
- Идентификаторы `wf-N` и `job-N` сквозные в процессе; объекты видны только своей сессии.
- Javalin запускается с `useVirtualThreads=true`; sync-run ждёт job в виртуальном потоке,
  поэтому health отвечает во время блокирующего run (проверено: 1 мс при 10-секундном узле).
- `/v1/health` отдаёт `nodes=11`, `coreNodes=11`, `workflows`, `sessions`, активные `jobs`
  текущей сессии и память JVM из живых `NodeRegistry`/`WorkflowStore`/`ServiceState`.

### 2.2 Каталог узлов

- Спеки лежат в `src/main/resources/specs/*.json`, список — `specs/index.txt`.
  `NodeRegistry` читает index из classpath, падает при отсутствующем/дублирующем спеке.
- `NodeSpec`: id/name/category/type/tier/available, порты и `configSchema`; native id — `dap.<type>`.
- `configSchema` превращается в поля `/dialog`: `field`, `configKey`, `title`, `type`,
  `optional`, `default`, `choices`, `items`; плюс `labelCoverage` и `warnings`.
  Для `available:false` dialog отдаёт `available:false` и `reason`, без схемы.
- Сейчас 11 спеков, все доступны: 10 пилотных (`csv_reader`, `row_filter`, `column_filter`,
  `sorter`, `missing_value`, `excel_reader`, `group_by`, `joiner`, `string_manipulation`,
  `math_formula`) и `column_renamer` — шаблон 11-го узла ([MIGRATION.md](MIGRATION.md)).

### 2.3 Исполнение

- `WorkflowStore` — in-memory workflow по сессиям, лимит на сессию, id `wf-N`. Граф хранит
  узлы и связи; `connect` проверяет дубли портов, самосвязь и циклы.
- `JobManager` принимает workflow и `DuckDbSession`, создаёт `job-N`, запускает run в
  виртуальном потоке. Состояния: `queued → running → succeeded | failed | cancelled | timeout`.
  Запуски одного workflow сериализованы fair-lock'ом и сбрасывают результаты в начале run;
  разные workflow идут параллельно. Watchdog срабатывает по дедлайну; cancel/timeout
  отменяют future и закрывают job-connection, что прерывает native-запрос DuckDB.
- `Dag` берёт один `Workflow.snapshot()` (узлы, связи, deep-copy конфигов), проверяет
  фабрики/порты и строит уровни Кана: узлы уровня параллельны, следующий — после
  предыдущего. Узлы выполняются через `Future.cancel(true)`, поэтому падение соседа
  прерывает блокированные ветви. Результат — `Map<nodeId, LazyPlan>`; трансформации данные
  не читают, исключение — `csv_reader` (ingestion на run).
- `NodeExecutor` получает `NodeContext` (config, сессия, job-connection) и входные планы,
  возвращает `LazyPlan`. Одиннадцать executor-ов: `csv_reader`, `row_filter`, `column_filter`,
  `sorter`, `missing_value`, `excel_reader`, `group_by`, `joiner`, `string_manipulation`,
  `math_formula`, `column_renamer`.

### 2.4 Хранилище

- `DuckDbSession` открывает `jdbc:duckdb:` (in-memory) и ставит `TimeZone='UTC'`.
  Базовая connection владеет каталогом; `duplicate()` даёт соединения, видящие те же таблицы.
- Одна БД на сессию: `ServiceState` держит `ConcurrentHashMap<String, DuckDbSession>` и
  закрывает их вместе с сервисом.
- `csv_reader` вызывает `loadInto(jobConnection, table, "csv_<wf>_<node>")`:
  `CREATE OR REPLACE TABLE` на job-соединении и `DuckDBAppender`. Ре-ран заменяет ту же
  таблицу (не копит копии), а cancel/timeout может прервать ingestion закрытием соединения.
  Ingest сериализован по connection: две независимые ветви `csv_reader` в одном уровне
  DAG не интерливают pending-результаты DuckDB (стресс: 30 sync-run, 0 ошибок).
- `load(ArrowTable)` остаётся для тестов и будущих writer-ов: префикс + счётчик.
- `run` строит планы; `/table` вызывает `LazyPlan.preview` (`COUNT(*)` + `LIMIT`), `/profile`
  — `LazyPlan.profile` (`COUNT(*)`, `COUNT(DISTINCT)`, `MIN/MAX/AVG` для числовых колонок).
  Оба открывают собственные duplicate-соединения.

### 2.5 Граница данных

- Arrow используется на входе CSV: `CsvParser` → `CsvTableReader` → `ArrowTable` →
  `DuckDbSession.loadInto` → таблица DuckDB. Дальше все преобразования — SQL-отношения.
- `CsvParser` хранит флаг `quoted` для каждого поля, поэтому пустое некавыченное поле
  становится SQL NULL, а кавыченное `""` — пустой строкой (если не включён
  `emptyStringIsNull`).
- Arrow null bitmap переходит в SQL NULL; `ArrowTable` остаётся границей для будущих
  IPC/Polars/writers, но между узлами Arrow не передаётся.

## 3. Замеры

Стенд: Apple M1, 8 ГБ, macOS arm64; Temurin 21.0.13+9 (build 21.0.13+9-LTS). Команды — в
`engine-native/` с переменными из §7; каждый прогон spike — отдельная JVM.

| Что | Команда | Результат |
|---|---|---|
| Полный suite | `./mvnw -B test` | 203 теста, 0 failures, 0 errors; BUILD SUCCESS, ~10 с (114 на момент Фазы 1) |
| HTTP-интеграция | `NativeServiceTest` (16 тестов) | реальный Javalin на свободном порту, все 25 операций, timeout/cancel, изоляция сессий |
| Health во время sync-run | `-Ddap.test.slowMs=10000` | health 200 за 1 мс при 10-секундном узле; sync-run завершился 200 |
| Spike 200k, 5 прогонов | `./mvnw -B -Dtest=ArrowDuckDbSpikeTest test` | 1952–2108 мс; 94 877–102 459 строк/с; медиана 1994 мс / 100 301 строк/с |
| Jar (native-библиотека хоста) | `./mvnw -B -DskipTests package`; `stat -f '%z'` | 46 246 388 Б ≈ 44,1 МиБ на macOS arm64 |
| Native libs внутри jar | `unzip -l target/dap-native.jar` | одна библиотека платформы сборки (osx_universal 107,6 МБ); linux_amd64 60,8 МБ, linux_arm64 53,6 МБ, windows_amd64 35,1 МБ вырезаются shade-фильтром |
| Cold start финального jar, 3 прогона | `java -jar` + poll `/v1/health` | 410 / 411 / 500 мс (медиана 411 мс) |

Spike: 200 000 строк, 3 колонки (`Int64`, `Utf8`, `Float64`); таймер включает
`ArrowTable.of` и вставку в DuckDB, но не `COUNT(*)`. Прогоны (мс/строк/с):
2093/95 557, 1994/100 301, 1952/102 459, 1994/100 301, 2108/94 877. Разброс — прогрев
JIT: в полном suite после 83 тестов тот же тест даёт 230 мс.

### 3.1 Arrow ↔ DuckDB: zero-copy нет

В `duckdb_jdbc` 1.5.6.0 `DuckDBConnection.registerArrowStream(String, Object)` **public**
(`javap -v`: `flags: (0x0001) ACC_PUBLIC`), но это не регистрация `VectorSchemaRoot`:
`getArrowStreamAddress` рефлексией требует `org.apache.arrow.c.ArrowArrayStream`
(`arrow-c-data`), которого нет в `pom.xml`; вызов падает
`ClassNotFoundException: org.apache.arrow.c.ArrowArrayStream`. Поэтому доступного
zero-copy пути нет: `load` делает построчную копию через `DuckDBAppender`, и цифры
выше — её стоимость вместе с `ArrowTable.of`. Утверждение «registerArrowStream
package-private» для 1.5.6.0 неверно; zero-copy не проверен и не используется.

### 3.2 CSV: пустое поле и NULL

Проверено на `read_csv` 1.5.6.0 с `all_varchar=true`:

| Опция | `1,` (unquoted) | `2,""` (quoted) |
|---|---|---|
| по умолчанию (`allow_quoted_nulls=true`) | NULL | NULL |
| `nullstr='\u0000'` | пустая строка | пустая строка |
| `allow_quoted_nulls=false` | NULL | пустая строка |

По умолчанию DuckDB схлопывает оба случая в NULL, `nullstr='\u0000'` делает оба
строками; `allow_quoted_nulls=false` различает, поэтому утверждение «настройки нет»
неверно. DAP всё равно парсит CSV сам: нужен флаг quoted, типизация/инференс и
`emptyStringIsNull` как настройка узла, а не глобальная опция. Тесты:
`CsvParserTest.quotedEmptyFieldIsFlaggedAsQuoted`,
`CsvReaderExecutorTest.unquotedEmptyFieldIsNullAndQuotedEmptyFieldIsEmptyString`,
`CsvReaderExecutorTest.emptyStringIsNullTurnsQuotedEmptyIntoSqlNull`.

### 3.3 Соединения и отмена

| Проверка | Результат |
|---|---|
| `base.duplicate()` видит таблицу, созданную в base | `SELECT x FROM t` → 42 |
| Закрытие duplicate во время `COUNT(*) range(100000) × range(100000)` | `SQLTimeoutException: INTERRUPT Error: Interrupted!` через 2 мс после `close()` |
| `DuckDBConnection.interrupt()` | package-private (`javap`), из кода движка не вызывается |

`JobManager` использует именно закрытие job-connection для cancel/timeout, а не
`interrupt()`. Это совпадает с замером, но расходится с формулировкой роадмапа
«`conn.interrupt()`».

### 3.4 Ресурсные лимиты DuckDB

Лимиты задаются только через env (`Settings.fromEnv()` — единственное место чтения):

| Переменная | Смысл | Пример |
|---|---|---|
| `DAP_DUCKDB_MEMORY_LIMIT` | `SET memory_limit` на БД сессии | `2GB` |
| `DAP_DUCKDB_THREADS` | `SET threads`, положительное целое | `3` |
| `DAP_DUCKDB_TEMP_DIR` | `SET temp_directory`; каталог создаётся при отсутствии | `/var/tmp/dap` |

Незаданные переменные оставляют умолчания DuckDB (на стенде `6.3 GiB`, `8`, `.tmp`).
Настройки выполняются на базовом соединении, а `duplicate()` их наследует, потому что это
настройки уровня БД; `temp_directory` создаётся до `SET`. Эффективные значения (после
нормализации: `2GB` → `1.8 GiB`, `1GB` → `953.6 MiB`) читаются `current_setting` и
отдаются в `GET /v1/workflows/{id}/debug` полем `duckdb:{memoryLimit, threads,
tempDirectory}` (до создания БД сессии — сконфигурированные значения, `null` — умолчание
DuckDB).

Проверено (`DuckDbSessionSettingsTest`, 6 тестов):

| Проверка | Результат |
|---|---|
| `SELECT current_setting('memory_limit'), current_setting('threads'), current_setting('temp_directory')` на duplicate после `SET` | `953.6 MiB`, `2`, `/tmp/.../spill` — те же, что на базовом |
| `SELECT COUNT(*) FROM (SELECT i FROM range(20000000) t(i) ORDER BY i DESC)` при `memory_limit=64MB`, `threads=2` | 20 000 000 строк, ~810 мс, без OOM; в `temp_directory` появляются файлы (polling во время запроса) |
| `SET memory_limit='banana'` | SQLException `invalid DuckDB memory limit 'banana': Parser Error: Memory must have a number (e.g. 1GB)` |
| `SET memory_limit='0GB'` | DuckDB принимает `SET`, но следующий запрос не может выделить память; чтение `current_setting` после `SET` валит создание сессии: `invalid DuckDB memory limit '0GB': Out of Memory Error...` |
| `DAP_DUCKDB_THREADS=0` / `-1` | `IllegalArgumentException: DAP_DUCKDB_THREADS must be a positive integer` при разборе env; прямой конструктор сессии — `DuckDB threads must be positive` |
| temp-путь под существующим файлом | SQLException `cannot create DuckDB temp directory '...'` |

Проверка живого jar (значения после нормализации DuckDB):

```bash
DAP_DUCKDB_MEMORY_LIMIT=2GB DAP_DUCKDB_THREADS=3 DAP_DUCKDB_TEMP_DIR=/tmp/dap-res-verify \
  java -Ddap.port=7863 -jar target/dap-native.jar &
curl -s -H 'X-DAP-Session: verify' http://127.0.0.1:7863/v1/workflows/wf-1/debug
# "duckdb":{"memoryLimit":"1.8 GiB","threads":3,"tempDirectory":"/tmp/dap-res-verify"}
```

## 4. Решения и следствия

| Решение | Следствие |
|---|---|
| Преобразования — SQL-отношения, без Arrow-шины между узлами | Просто, есть pushdown и ленивость; нет zero-copy и Arrow-native операций. Будущий Polars потребует отдельной границы |
| Arrow сохраняется на входе (и для будущих IPC/Polars/writers) | Единый ingestion-путь и контроль типов; цена — построчная копия в DuckDB |
| Исходные таблицы копируются в сессионную БД | Нет зависимости от файла после run; растёт память сессии, нужна очистка таблиц |
| `missing_value.mean` только для `Float64` | `AVG` не меняет тип колонки; для остальных типов — `constant`/`mode`, иначе ошибка |
| `csv_reader` принимает реальный путь к файлу | Детерминированный IO. Сокращение `dataset` из корпуса — забота parity-раннера, не узла |
| `run` ленивый: строится план, SELECT-ы не выполняются | Sync-run может завершиться без материализации. Исключение — `csv_reader`: он читает файл и загружает таблицу уже на run |
| `/table` и `/profile` материализуют результат | Вся стоимость — в этих запросах; `profile` делает полный проход и агрегаты |
| Отмена — через закрытие соединения | Job-connection закрывается на cancel/timeout; materialization-соединения `/table`/`/profile` учитываются в сессии и закрываются при `close()`, `interruptReads(workflowId)` и teardown-е сессии (Фаза 2) |

## 5. Приёмка Фазы 1

Пункты из [ROADMAP-NATIVE.md](ROADMAP-NATIVE.md) §4; все проверены на `phase1-native`.

| Пункт | Статус | Комментарий |
|---|---|---|
| `make build-native` → `target/dap-native.jar` | да | jar 44,1 МиБ: shade оставляет только native-библиотеку платформы сборки, `-Ddap.duckdb.native=all` возвращает все четыре |
| `make run-native` стартует < 2 с | да | 410–500 мс от `java -jar` до health 200 |
| `/v1/health` → `nodes=10` | да | живые счётчики registry/store/sessions; `coreNodes=10` |
| Workflow из 3 узлов end-to-end | да | `csv_reader → missing_value → column_filter → row_filter → sorter`; 5 узлов, 3 строки |
| `table` отдаёт данные, `profile` считается | да | строки/типы/NULL/`""`, `missing`/`distinct`/`min/max/mean` |
| Health 200 во время 10-секундного sync-run | да | 1 мс при `dap.test.slowMs=10000`; job остаётся `running` |
| `grep -r "org.knime" engine-native/` пуст | да | проверяется `scripts/check_repo.py` |
| `mvn test` зелёный | да | 114 тестов на момент приёмки Фазы 1; 199 после Фазы 3 (§5.1–5.2) |
| все маршруты frozen-контракта отвечают | да | 25 операций OpenAPI зарегистрированы; `tests/native_smoke.py` — 9/9 |

## 5.1 Приёмка Фазы 2

Пункты из [ROADMAP-NATIVE.md](ROADMAP-NATIVE.md) §5; проверено на `phase2-native`.

| Пункт | Статус | Комментарий |
|---|---|---|
| 10/10 узлов | да | `excel_reader`, `group_by`, `joiner`, `string_manipulation`, `math_formula` добавлены к пяти пилотным |
| `configSchema` в `/v1/nodes/{id}/dialog` для 10 узлов | да | `available: true` у всех 10; `available: false`/reason остаётся для будущих волн |
| 8 dual-run workflow ≥ 90 % ячеек | да | `tests/parity.py --engine native`: 12/12 workflow ok, `matching cells 1563/1563 (100 %)` |
| 4 native-only workflow | да | `expect`/`expect_profile`/`equivalent` (`wf-05`, `wf-10`, `wf-n1`, `wf-n2`) зелёные на живом движке |
| dataset-shorthand в раннере | да | `{"dataset": ...}` → `dap.csv_reader`/`excel_reader` с абсолютным путём и схемой из `datasets.json` |
| profile distinct cap 100k | да | точный `COUNT(DISTINCT)` до 100k различных значений, выше — sketch + досчёт у границы (§6) |
| отмена `/table`//`/profile` | да | `interruptReads(workflowId)` + закрытие сессии; замер: прерывание за 0–1 мс |
| jar < 50 МБ | да | 46 246 388 Б ≈ 44,1 МиБ (одна платформенная native-библиотека) |
| тесты и smoke | да | `mvn test` — 181 тест, 0 падений; `tests/native_smoke.py` — 9/9; `tests/test_parity_native.py` — 27/27 |

## 5.2 Приёмка Фазы 3

Пункты из [ROADMAP-NATIVE.md](ROADMAP-NATIVE.md) §6; проверено на `phase3-native`.

| Пункт | Статус | Комментарий |
|---|---|---|
| `tests/bench.py` + [BENCH.md](BENCH.md) | да | cold 473 мс, 10M ingest 11,7 с, `/table` 69 мс, heap peak 4,65 ГБ, ветви 1,18× (5M) |
| Решение по Polars | да | остаёмся на DuckDB: SQL-часть 10M-конвейера 69 мс, узкое место — ingest (BENCH.md §4.3) |
| `deploy/Dockerfile.native` + [deploy/README-native.md](../deploy/README-native.md) | определение готово | multi-stage, jlink-рантайм, non-root, healthcheck без curl; арифметика ~152 МБ; **`docker build`/`run` не выполнены** (нет Docker-демона) |
| `docs/MIGRATION.md` + 11-й узел | да | `dap.column_renamer` + `wf-n3`; каталог 11 узлов, parity 13/13 |
| MCP e2e | да | `tests/mcp_e2e.py` (`DAP_NATIVE=1`): create → add_node → connect → run → table → delete, 3,6 с |
| Лицензии/граница | да | [NOTICE](../NOTICE) §2, [INDEPENDENCE.md](INDEPENDENCE.md) §11 |
| Тесты | да | `mvn test` — 203 теста; parity 13/13, `matching cells 1563/1563 (100 %)`; smoke 9/9; MCP 27+1 |
## 6. Дальше и риски

- **Все 11 узлов доступны (10 пилотных + `column_renamer` как шаблон [MIGRATION.md](MIGRATION.md));
  остальные узлы волн A–C ещё не реализованы.** `excel_reader`
  реализован без Apache POI (лимит jar): JDK `zip`+`StAX`, поддерживаются `inlineStr`,
  shared strings (rich-text склеивается), числа, boolean, error→NULL, ISO-даты и даты по
  `numFmtId`; `.xls`, шифрованные книги и формулы без кэша не читаются (формула берётся
  из кэшированного значения).
- **Parity-раннер:** `tests/parity.py --engine native` исполняет корпус (13/13, 100 % ячеек);
  `dataset`-shorthand разворачивает раннер, а не узел: `csv_reader` требует реальный путь.
  Валидация корпуса запрещает native-only проверки на dual-run workflow и требует
  `name`+`type` у колонок `expect`, чтобы опечатка не проходила молча.
- **Profile distinct:** точный `COUNT(DISTINCT)` при `rows <= 100 000`; выше — `APPROX_COUNT_DISTINCT`,
  и если оценка `<= 110 000` (запас 3σ), значение досчитывается точным `COUNT(DISTINCT)`
  на отдельном Statement, чтобы обещание «точно до 100k различных» выдерживалось.
  DuckDB 1.5.6 использует HLL P=10 (σ ≈ 3,2 %, измерено ≤ 5 %), поэтому 1 %-допуск PARITY
  заменён на измеренный. Для таблиц >100k строк с низкой кардинальностью `/profile` делает
  два агрегатных прохода по колонке (оценка + точный досчёт) — осознанная плата за контракт.
- **Исходные таблицы:** `csv_reader` заменяет одну стабильную таблицу на узел
  (`CREATE OR REPLACE`), поэтому ре-раны не копят копии; eviction по сессии всё ещё нет.
- **Размер jar:** 44,1 МиБ: shade оставляет native-библиотеку платформы сборки (macOS →
  osx_universal, Linux amd64/arm64 и Windows amd64 аналогично), три остальных файла
  исключаются. Профили `duckdb-native-host-*` выбирают хост по `<os>`; для
  cross-platform упаковки выбор переопределяется
  `-Ddap.duckdb.native=all|osx_universal|linux_amd64|linux_arm64|windows_amd64`
  (менять выбор и пересобирать нужно с `clean package`: shade не пересобирает уже
  shaded jar). `make lint` проверяет собранный jar: < 50 МиБ и хотя бы одна
  DuckDB-native библиотека (ноль библиотек — ERROR, несколько — WARN для
  cross-platform сборки).
- **Polars:** бенчмарк снят в Фазе 3 (BENCH.md §4.1): SQL-часть 10M-конвейера — 69 мс,
  узкое место — ingest (11,7 с) и heap, а не SQL-движок. Решение: **остаёмся на DuckDB**
  (BENCH.md §4.3); пересмотр — если появится узел с долей DuckDB ≥ 50 % и прототип Polars
  покажет ≥ 2× на его профиле.
- **Отмена материализации:** `DuckDbSession` учитывает активные `/table`/`/profile`-чтения
  (соединение + workflow + node); `close()` сессии, `POST /v1/shutdown` и
  `DELETE /v1/jobs/{id}` (через `interruptReads(workflowId)`) закрывают их и прерывают нативный
  запрос за 0–1 мс. Читаемые данные принадлежат workflow, поэтому прерывается только
  **running** job того же workflow: отмена queued job не трогает чтения работающего соседа.
  Прерванный запрос `/table`/`/profile` возвращает замороженный 500 (ошибка исполнения)
  с сообщением драйвера об interruption.
- **Память 10M-пайплайна — в JVM heap, не в DuckDB.** Ingest 10M строк (`CsvParser` →
  `ArrowTable` → построчный `Appender`) требует ~4,65 ГБ heap при лимите `-Xmx6g`;
  `DAP_DUCKDB_MEMORY_LIMIT` ограничивает только буфер DuckDB. Для 10M на 8 ГиБ машине
  `-Xmx4g` недостаточно (проверено падением `Java heap space`); для двух ветвей на 10M
  нужно ≥ 8 ГиБ heap (в BENCH.md ветви измерены на 5M).
- **Параллельные ветви: 1,18× вместо 1,8×.** `DuckDbSession.loadInto` синхронизирован на
  job-соединении: parse перекрывается, `CREATE TABLE`+`Appender` — нет. Для цели ≥1,8×
  нужен параллельный ingest (несколько соединений/appender'ов) — кандидат в волну IO;
  харнесс пишет измеренное отношение и причину, ничего не подгоняя.
- **Образ не проверен в этой среде.** `deploy/Dockerfile.native` собран по арифметике
  (~152 МБ: debian-bookworm-slim 74 МБ + jlink JRE 38 МБ + jar 34 МБ), но `docker build`/
  `docker run` на Docker-хосте не выполнялись (демона нет) — статус "не проверено"
  в [deploy/README-native.md](../deploy/README-native.md); `java.desktop` удалён из jlink
  (проверено smoke + parity на урезанном рантайме).
- **RSS-семплирование недоступно в песочнице** (`ps` запрещён) — BENCH.md фиксирует heap peak
  из `/v1/health` (формула роадмапа) и `rssPeakBytes = null`; на обычной машине заполняются оба.
- **KNIME-сравнение для 10M невозможно:** oracle не читает файлы через `/v1` (PARITY §6.6.1),
  а shipped-датасеты ≤ 32k строк — в BENCH.md это `n/a` с причиной, а не выдуманная цифра.
- **Отмена узлов, игнорирующих interrupt:** job-состояние всё равно терминально
  (watchdog/cancel), но run-поток может жить до возврата нативного вызова.
- **Файловая граница:** `csv_reader` читает любой путь, доступный процессу; auth в пилоте
  нет, поэтому сервис должен слушать loopback (по умолчанию 127.0.0.1).
- **Память job-таблиц:** finished jobs не эвиктятся, lock-и по workflow не удаляются —
  приемлемо для пилота, но требует ревизии при долгих сессиях.
- **Ресурсы DuckDB:** `memory_limit`/`threads`/`temp_directory` применяются при создании
  БД **каждой** сессии, поэтому общепроцессный потолок — примерно
  `sessions × DAP_DUCKDB_MEMORY_LIMIT`; без `DAP_DUCKDB_TEMP_DIR` spill идёт в `.tmp`
  относительно рабочего каталога процесса, деплою нужен явный каталог. Лимит DuckDB не
  ограничивает JVM heap и Arrow-таблицы CSV/Excel ingestion (они живут вне buffer manager),
  поэтому для 10M-строк нужны оба лимита. Невалидные `DAP_DUCKDB_MEMORY_LIMIT`/
  `DAP_DUCKDB_TEMP_DIR` обнаруживаются на первом запросе, которому нужна БД (500 с текстом
  настройки), `DAP_DUCKDB_THREADS` — при старте; всё это фиксирует
  `DuckDbSessionSettingsTest`.
- **Память excel_reader:** весь лист держится в `TreeMap<row, Map<col, Cell>>` (сырая строка +
  double на числовую ячейку), затем копируется в Arrow — ~2–3× объёма таблицы и без лимита,
  поэтому очень большая книга может упереться в heap. Ограничение размера — задача волны
  IO; сейчас книга читается целиком.
- **native_smoke:** 9 проверок покрывают контракт и пять узлов Фазы 1; e2e всех одиннадцати
  узлов проверяет `tests/parity.py --engine native` (13 workflow), а не smoke.
- **Расхождения, зафиксированные замерами:** `registerArrowStream` public, но требует
  `arrow-c-data` (zero-copy не используется); `allow_quoted_nulls=false` различает
  `""` и NULL, но DAP всё равно парсит CSV сам; роадмап говорит `conn.interrupt()`, код
  закрывает соединения; `dap.sorter` принимает `natural` (по умолчанию), `binary` и `nocase`;
  `dap.joiner` при `merge_keys` берёт `COALESCE(left, right)`, чтобы right-only строка не
  теряла ключ; `dap.math_formula` выводит тип через `DESCRIBE ... LIMIT 0` на отдельном
  короткоживущем соединении (bind-only, не отменяется job-cancel); описание
  `emptyStringIsNull` в спеке исправлено.
- **Осознанные отличия HTTP-слоя:** legacy-алиасы без `/v1` (например, `GET /health`) не
  зарегистрированы — канонический контракт только `/v1/*`; sync-run выполняется на
  виртуальном потоке, поэтому текст frozen-описания «blocks the dispatcher» к native не
  относится; `PATCH .../nodes/{id}` без `settings` теперь 400 (поле обязательно);
  `DELETE` неизвестного node — 404 (openapi допускает и 400, docs/API.md §5 — 400);
  `health.sessions` считает открытые сессионные БД, а не workflow-сессии;
  fallback `table` на первый табличный порт пока не нужен — у всех узлов один
  табличный выход.

## 7. Сборка и запуск

```bash
cd engine-native
export JAVA_HOME=/Users/vitalypesnev/Projects/tools/jdk-21.0.13+9/Contents/Home
export DAP_TOOLS=/Users/vitalypesnev/Projects/dap/engine-native/.tools
export DAP_M2=/Users/vitalypesnev/Projects/dap/engine-native/.tools/m2

./mvnw -B test                 # полный suite
./mvnw -B -DskipTests package  # target/dap-native.jar
DAP_PORT=7800 java -jar target/dap-native.jar
```

Из корня репозитория:

```bash
make build-native   # jar
make test-native    # mvnw test
make run-native     # java -jar, порт 7800 (блокирующий)
```

`DAP_PORT` задаёт порт (по умолчанию 7800), `DAP_BIND` — адрес (по умолчанию `127.0.0.1`;
допускаются `localhost` и `0.0.0.0`), `DAP_MAX_WORKFLOWS` — лимит workflow на сессию.

Проверка живого сервиса (чёрный ящик, 9 сценариев):

```bash
java -Ddap.port=7861 -jar engine-native/target/dap-native.jar &
python3 tests/native_smoke.py --base http://127.0.0.1:7861
curl -s http://127.0.0.1:7861/v1/health
```

Acceptance-проверка 10-секундного sync-run:

```bash
cd engine-native
./mvnw -B -Dtest='NativeServiceTest#healthAnswersWhileSyncRunBlocks' -Ddap.test.slowMs=10000 test
```