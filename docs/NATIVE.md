# NATIVE — движок dap-native

**Статус:** Фаза 1 в работе (ветка `phase1/docs`): каталог, планировщик и пять
исполнителей уже в ветке; HTTP-маршруты workflow/job/table/profile ещё не подключены.
**Область:** `engine-native/`; **связанные:** [ROADMAP-NATIVE.md](ROADMAP-NATIVE.md),
[PARITY.md](PARITY.md), [API.md](API.md), [../ARCHITECTURE.md](../ARCHITECTURE.md).

## 1. Назначение и статус

`dap-native` — один JVM-процесс: Javalin 6.7 + DuckDB 1.5.6.0 + Arrow 19.0.0, контракт
`/v1` из `api/openapi-v1-frozen.yaml`. Цель Фазы 1 — 5 доступных узлов, 10 спецификаций,
ленивые SQL-планы и in-memory модель workflow/job. 832 узла, auth, multi-tenancy,
persistence и HA в пилот не входят.

| Что | Состояние на этой ветке |
|---|---|
| Сборка | `pom.xml`: javalin 6.7.0, duckdb_jdbc 1.5.6.0, arrow-vector/memory-netty 19.0.0, jackson 2.19.0, junit 5.11.4. `./mvnw -B test` — 85 тестов, 0 падений |
| Каталог | 10 JSON-спеков + `index.txt`; `NodeRegistry`; 5 спеков с `available:true`, 5 — с `false`; `/dialog` строится из `configSchema` |
| HTTP | `NativeService` отдаёт `/v1` и `/v1/health`; `CatalogHandlers` и контрактные тесты есть, но в сервис не зарегистрированы. Workflow/job/table/profile-маршрутов нет |
| Исполнение | `WorkflowStore`, `Dag`, `JobManager`, 5 executor-ов; unit-тесты на DAG, job lifecycle и узлы |
| Данные | `DuckDbSession`, `ArrowTable`, `LazyPlan`, `CsvParser`/`CsvTableReader`. Одна in-memory БД на сессию — целевая модель; `SessionStore` ещё нет |
| Вне пилота | auth, persistence, HA, 832 узла, изменения `engine/`, `runtime/knime/`, `catalog/` |

HTTP-слой делается параллельно и в этой ветке отсутствует; пункты приёмки, требующие
маршрутов, ниже отмечены как непроверенные.

## 2. Архитектура

```
HTTP (Javalin 6.7)
  ├─ X-DAP-Session ──► session (default)
  ├─ /v1, /v1/health
  └─ nodes / workflows / jobs / table / profile   (подключает HTTP-задача)
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
- `JobManager` и `Dag` используют `Executors.newVirtualThreadPerTaskExecutor()`. Sync-run
  должен ждать в виртуальном потоке, не занимая платформенный поток HTTP-диспетчера;
  это требование к HTTP-задаче, в текущем `NativeService` его ещё нет.
- `/v1/health` по контракту отдаёт `nodes`, `coreNodes`, `workflows`, `sessions`, `jobs`
  и память. Сейчас значения жёстко равны 0 — это заглушка скелета, а не приёмка.

### 2.2 Каталог узлов

- Спеки лежат в `src/main/resources/specs/*.json`, список — `specs/index.txt`.
  `NodeRegistry` читает index из classpath, падает при отсутствующем/дублирующем спеке.
- `NodeSpec`: id/name/category/type/tier/available, порты и `configSchema`; native id — `dap.<type>`.
- `configSchema` превращается в поля `/dialog`: `field`, `configKey`, `title`, `type`,
  `optional`, `default`, `choices`, `items`; плюс `labelCoverage` и `warnings`.
  Для `available:false` dialog отдаёт `available:false` и `reason`, без схемы.
- Сейчас 10 спеков: 5 доступных (`csv_reader`, `row_filter`, `column_filter`, `sorter`,
  `missing_value`) и 5 заявленных, но не реализованных (`excel_reader`, `group_by`,
  `joiner`, `string_manipulation`, `math_formula`).

### 2.3 Исполнение

- `WorkflowStore` — in-memory workflow по сессиям, лимит на сессию, id `wf-N`. Граф хранит
  узлы и связи; `connect` проверяет дубли портов, самосвязь и циклы.
- `JobManager` принимает workflow и `DuckDbSession`, создаёт `job-N`, запускает run в
  виртуальном потоке. Состояния: `queued → running → succeeded | failed | cancelled | timeout`.
  Watchdog срабатывает по дедлайну; cancel/timeout отменяют future и закрывают job-connection.
- `Dag` проверяет фабрики/порты и строит уровни Кана: узлы уровня параллельны, следующий —
  после предыдущего. Результат — `Map<nodeId, LazyPlan>`; трансформации данные не читают,
  исключение — `csv_reader` (ingestion на run).
- `NodeExecutor` получает `NodeContext` (config, сессия, job-connection) и входные планы,
  возвращает `LazyPlan`. Пять executor-ов: `csv_reader`, `row_filter`, `column_filter`,
  `sorter`, `missing_value`.

### 2.4 Хранилище

- `DuckDbSession` открывает `jdbc:duckdb:` (in-memory) и ставит `TimeZone='UTC'`.
  Базовая connection владеет каталогом; `duplicate()` даёт соединения, видящие те же таблицы.
- Одна БД на сессию — целевая модель. В этой ветке нет `SessionStore`, который связывал бы
  `X-DAP-Session` с `DuckDbSession`; это часть HTTP-задачи.
- `load(ArrowTable)` создаёт таблицу по схеме и вставляет строки `DuckDBAppender`; имя —
  санитизированный префикс + счётчик.
- `run` строит планы; `/table` вызывает `LazyPlan.preview` (`COUNT(*)` + `LIMIT`), `/profile`
  — `LazyPlan.profile` (`COUNT(*)`, `COUNT(DISTINCT)`, `MIN/MAX/AVG` для числовых колонок).
  Оба открывают собственные duplicate-соединения.

### 2.5 Граница данных

- Arrow используется на входе CSV: `CsvParser` → `CsvTableReader` → `ArrowTable` →
  `DuckDbSession.load` → таблица DuckDB. Дальше все преобразования — SQL-отношения.
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
| Полный suite | `./mvnw -B test` | 85 тестов, 0 failures, 0 errors; BUILD SUCCESS, 6.75 с |
| Spike 200k, 5 прогонов | `./mvnw -B -Dtest=ArrowDuckDbSpikeTest test` | 1952–2108 мс; 94 877–102 459 строк/с; медиана 1994 мс / 100 301 строк/с |
| Jar | `./mvnw -B -DskipTests package`; `stat -f '%z'` | 98 035 998 Б ≈ 93,5 МиБ |
| Native libs внутри jar | `unzip -l target/dap-native.jar` | ≈257 МБ (245 МиБ) несжатыми: osx_universal 107,6 МБ, linux_amd64 60,8 МБ, linux_arm64 53,6 МБ, windows_amd64 35,1 МБ |
| Cold start скелета, 3 прогона | `java -jar` + poll `/v1/health` | 421–533 мс; медиана 429 мс |

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
| Отмена — через закрытие job-connection | Работает, когда запрос идёт на этом соединении. `/table` и `/profile` открывают свои соединения и этим механизмом не покрыты |

## 5. Приёмка Фазы 1

Пункты из [ROADMAP-NATIVE.md](ROADMAP-NATIVE.md) §4. «Да» — проверено на этой ветке;
«нет» — на ветке отсутствует; «HTTP» — должна доказать параллельная HTTP-задача.

| Пункт | Статус | Комментарий |
|---|---|---|
| `make build-native` → `target/dap-native.jar` | да | сборка успешна; jar 93,5 МиБ |
| `make run-native` стартует < 2 с | скелет: да | `java -jar` 421–533 мс, 3 прогона; финальный сервис не мерили |
| `/v1/health` → `nodes=10` | нет | сейчас `nodes:0`, значения жёстко зашиты в `NativeService` |
| Workflow из 3 узлов end-to-end | нет | workflow-маршрутов нет; `Dag`/executor-ы проверены unit-тестами |
| `table` отдаёт данные, `profile` считается | нет | `LazyPlan.preview/profile` проверены unit-тестами, HTTP-маршрутов нет |
| Health 200 во время 10-секундного sync-run | HTTP | run-маршрута нет; виртуальные потоки в JobManager/Dag есть, sync-wait не подключён |
| `grep -r "org.knime" engine-native/` пуст | да | совпадений нет |
| `mvn test` зелёный | да | 85 тестов, 0 падений |
| 26 маршрутов `/v1` отвечают | нет | в сервисе только `/v1` и `/v1/health`; `/v1/nodes` → 404 |

## 6. Дальше и риски

- **5 недоступных узлов:** `excel_reader`, `group_by`, `joiner`, `string_manipulation`,
  `math_formula` — Фаза 2; в каталоге уже `available:false`.
- **Parity-раннер:** `tests/parity.py --engine native` пока заглушка; native-прогон корпуса —
  Фаза 2. `csv_reader` требует реальный путь; подстановка dataset — задача раннера.
- **Profile distinct:** `MAX_DISTINCT = 100_000` объявлен, но не используется; сейчас всегда
  точный `COUNT(DISTINCT)`; нужен порог и `APPROX_COUNT_DISTINCT` (API/PARITY обещают 100k).
- **Очистка исходных таблиц:** сессионная БД хранит копии CSV; eviction нет — риск роста памяти.
- **Размер jar:** 93,5 МиБ; внутри ≈257 МБ native-библиотек всех платформ. Цель Фазы 3
  < 50 МБ требует вырезать/разделить платформенные библиотеки, а не только сжатие.
- **Polars:** порог — ≥2× на конкретном узле; бенчмарка ещё нет, поэтому остаёмся на DuckDB.
- **Отмена материализации:** `/table`/`/profile` не используют job-connection; для
  покрытия отмены нужно прокинуть соединение/хэндл запроса.
- **Каталог и health:** `CatalogHandlers` не зарегистрированы в `NativeService`;
  health должен считать registry/store, иначе приёмка `nodes=10` не пройдёт.
- **Расхождения:** `registerArrowStream` public, а не package-private (но требует
  `arrow-c-data`, zero-copy нет); `allow_quoted_nulls=false` различает `""` и NULL;
  роадмап говорит `conn.interrupt()`, код закрывает connection; `dap.sorter` рекламирует
  `collation:"binary"`, executor принимает только `nocase`; `emptyStringIsNull` в спеке
  описан как «unquoted empty → NULL», хотя unquoted всегда NULL, а флаг управляет quoted `""`.

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

`DAP_PORT` задаёт порт (по умолчанию 7800). Проверка скелета:

```bash
curl -s http://127.0.0.1:7800/v1/health
```
