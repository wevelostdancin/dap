# ROADMAP — dap-native

**Статус:** Фаза 0 закрыта (29.10.2026). Фаза 1 не начата.
**Область:** нативный движок `engine-native/` вместо KNIME-обёртки; `engine/`, `runtime/knime/`, `catalog/`
остаются oracle-ом и не развиваются.
**Связанные документы:** [PARITY.md](PARITY.md), [INDEPENDENCE.md](INDEPENDENCE.md), [API.md](API.md),
[NODES.md](NODES.md), [corpus/README.md](../tests/corpus/README.md).

## 1. Исходная точка: что уже зафиксировано

| Артефакт | Состояние |
|---|---|
| `/v1` контракт | заморожен, `api/openapi-v1-frozen.yaml`, sha256 `e2225093…`, проверка в `make lint` |
| Корпус | 12 workflow (`tests/corpus/`): 8 dual-run на oracle + 4 native-only |
| Baseline | `tests/baseline.json`, 20 наблюдений (таблицы + профили), воспроизводим 2/2 прогона |
| Harness | `tests/parity.py`: `--validate`, `--write-baseline`, `--check-baseline`, `--engine native` (заглушка) |
| Правила сравнения | [PARITY.md](PARITY.md): float 1e-9, точные int/NULL, `""≠NULL`, ordered/unordered, типы |
| Данные | 4 shipped-датасета KNIME, экспорт через `/table`, схема в `tests/corpus/data/datasets.json` |

Что выяснилось про oracle (влияет на план, подробности в PARITY.md §6):

1. Файловые ридеры (CSV/Excel) в oracle не исполняются — parity для них только через
   эквивалентность внутри native (`wf-n1`, `wf-n2`).
2. `row3.RowFilter` не работает по колонкам (NPE) — корпус использует Rule-based Row Filter.
3. GroupBy-агрегации недоступны через `/v1` — корпус использует Row Aggregator.
4. Missing Value и все formula-узлы неконфигурируемы; Expression падает на Arrow-инициализации.
5. Из этого следует: **семантику пилотных узлов задаём мы**, а oracle подтверждает только
   те узлы, которые у него работают. Отсюда normalisation-слой в harness.

## 2. Границы

Строим:

- `engine-native/` — Javalin + DuckDB (in-process) + Arrow bus, 10 узлов, `/v1` 1:1.
- Собственные JSON Schema узлов (`configSchema`) — агент настраивает узел без `settingsXml`.
- Lazy plan: `executor` возвращает план, материализация только в `table`/`profile`.
- Async DAG-планировщик: топосорт, параллельные ветви, отмена, timeout.

Не строим (в пилоте):

- 832 узла (только 10, дальше волнами по спросу);
- auth, multi-tenancy, persistence, HA;
- production-hardening до parity;
- любые изменения `engine/`, `runtime/knime/`, `catalog/`;
- Polars — только после бенчмарка с выигрышем ≥2× на конкретном узле.

## 3. Контракты, которые нельзя ломать

### 3.1 HTTP

Маршруты и схемы — из `api/openapi-v1-frozen.yaml` (26 endpoints). Любое изменение —
правка frozen-файла + `.sha256` + запись в `CHANGELOG.md`.

| Ситуация | Код | Комментарий |
|---|---|---|
| неизвестный `factory` в `POST …/nodes` | 400 | так в контракте и в oracle |
| неизвестный id в `GET /v1/nodes/{id}` | 404 | |
| неизвестный workflow/job | 404 | |
| невалидный config | 400 | |
| ошибка исполнения | 500 | |
| `timeoutSec` у sync-run | 408 + тело Job | |

Это расходится с формулировкой «404 для неизвестного узла» из исходного ТЗ; принят вариант
«контракт главнее» (PARITY.md §6.7).

### 3.2 Идентификаторы

- native node id: `dap.<type>`, например `dap.row_filter`.
- MCP-адаптер не меняется: `dap_search_nodes` отдаёт id из `GET /v1/nodes`, агент передаёт их
  в `POST …/nodes` как `factory`.
- `grep -r "org.knime" engine-native/` пуст — CI-гард.

### 3.3 Конфиги узлов (native vocabulary, задано корпусом)

| node | config |
|---|---|
| `dap.csv_reader` | `{file, delimiter, header, columns:[{name, type}], emptyStringIsNull}` |
| `dap.excel_reader` | `{file, sheet, header, columns}` |
| `dap.row_filter` | `{logic: and\|or, where:[{column, op, value}]}`, op: `eq ne lt le gt ge is_null not_null` |
| `dap.column_filter` | `{include:[...]}` или `{exclude:[...]}` |
| `dap.sorter` | `{keys:[{column, direction}]}` |
| `dap.missing_value` | `{rules:[{column, method: constant\|mean\|mode, value?}]}` |
| `dap.group_by` | `{keys:[...], aggregations:[{column, fn: mean\|sum\|count\|min\|max}]}` |
| `dap.joiner` | `{how: inner\|left\|right, left_keys:[], right_keys:[], merge_keys}` |
| `dap.string_manipulation` | `{column, op: upper\|lower\|trim\|replace\|concat, mode, ...}` |
| `dap.math_formula` | `{outputs:[{name, expression}]}` |

Ключи взяты из корпуса — это уже проверенный минимум, а не гипотеза.

### 3.4 Имена и типы

- Агрегаты называются как в KNIME: `Mean(x)`, `Sum(x)`, `Count(x)`.
- Arrow→KNIME: `Int64→Number (Integer)`, `Float64→Number (Float)`, `Utf8→String`,
  `Date32→Date`, `Time64→Time`, `Timestamp→Date&Time (Timestamp)`, `Decimal128→Number (Decimal)`,
  `Boolean→Boolean`.
- `Count` отдаётся как `Number (Integer)`, не `Long Integer`.

### 3.5 NULL и пустая строка

- NULL — только Arrow null bitmap; позиции совпадают с baseline точно.
- `""` — значение. `csv_reader` различает пустое некавыченное поле (NULL) и кавыченное `""`.

## 4. Фаза 1 — скелет и 5 узлов (5–7 дней)

| # | Задача | Результат |
|---|---|---|
| 1 | `pom.xml`: duckdb_jdbc, arrow-vector/memory-netty, javalin 6, jackson, slf4j/logback, junit5 | сборка без `runtime/knime` |
| 2 | `NativeService` + `/v1/health` на виртуальных потоках | health отвечает во время run |
| 3 | `NodeRegistry` + `NodeSpec` из `resources/specs/*.json` (10 спеков сразу, 5 `available:false`) | registry как источник правды |
| 4 | `DuckDbSession`, `ArrowTable`, `LazyPlan` (обёртка над DuckDB `Relation`) | план без материализации |
| 5 | **Spike: Arrow↔DuckDB** — проверить `conn.register(...)`/ArrowReader на zero-copy; замерить копию | решение и число в NATIVE.md |
| 6 | `Dag` + `JobManager`: queued→running→succeeded/failed/cancelled/timeout, `conn.interrupt()`, `InterruptedException`→терминальное состояние | нет KNIME-бага с `failed` вместо `cancelled` |
| 7 | Узлы: `csv_reader`, `column_filter`, `row_filter`, `sorter`, `missing_value` | + 3 теста на узел |
| 8 | `WorkflowStore` (in-memory, сессии), маршруты из frozen-спеки, `save`/`debug`/`shutdown` | 26 маршрутов отвечают |

**Acceptance**

- `make build-native` → `engine-native/target/dap-native.jar`; `make run-native` стартует < 2 с;
- `curl /v1/health` → `{"ok":true,"nodes":10,...}`;
- workflow из 3 узлов исполняется end-to-end, `table` отдаёт данные, `profile` считается;
- health отвечает (200) во время 10-секундного sync-run;
- `grep -r "org.knime" engine-native/` пуст; `mvn test` зелёный.

## 5. Фаза 2 — остальные 5 узлов, schemas, parity (5–7 дней)

| # | Задача | Результат |
|---|---|---|
| 1 | `group_by` (несколько агрегатов), `joiner` (inner/left/right, merge_keys, суффиксы), `string_manipulation` (ops из §3.3), `math_formula` (SQL-выражения над колонками), `excel_reader` (DuckDB excel/POI) | 10/10 узлов |
| 2 | `/v1/nodes`, `/v1/nodes/{id}`, `/v1/nodes/{id}/dialog` из `NodeSpec.configSchema`; для остальных — `available:false` + `reason: "not implemented in native engine yet"` | агент настраивает узел по JSON Schema |
| 3 | `profile` через DuckDB `SUMMARIZE` + `COUNT(DISTINCT)`/`APPROX_COUNT_DISTINCT` (>100k) | числа как в baseline |
| 4 | `parity.py --engine native` — прогон корпуса против `tests/baseline.json` | ≥90 % ячеек |
| 5 | Разбор расхождений: фикс или запись в PARITY.md с причиной | таблица «совпало / расхождение / причина» |

**Acceptance**

- 8 dual-run workflow проходят на native с ≥90 % совпадением (по правилам PARITY);
- 4 native-only workflow проходят свои `expect`/`equivalent` проверки;
- `/v1/nodes/{id}/dialog` отдаёт `configSchema` для 10 узлов;
- все 12 workflow корпуса исполняются end-to-end.

## 6. Фаза 3 — бенчмарки, образ, Polars (3–5 дней)

| # | Задача | Результат |
|---|---|---|
| 1 | `tests/bench.py`: cold start, CSV 10M (read+filter+groupby), RAM, размер jar/образа, параллельные ветви | числа воспроизводимы |
| 2 | `BENCH.md`: таблица native vs KNIME (+метод измерения) | cold start < 2 с, ≥3× на пилоте |
| 3 | Polars — **только** если бенчмарк узла даёт ≥2×; иначе DuckDB | решение с числом в BENCH.md |
| 4 | `Dockerfile` (`eclipse-temurin:21-jre`), `docker run` healthy < 5 с | образ < 200 МБ |
| 5 | `docs/NATIVE.md` (архитектура), `docs/MIGRATION.md` (11-й узел: spec + executor + тест + corpus) | шаблон воспроизводим |

**Acceptance:** числа в BENCH.md, образ < 200 МБ, MCP-адаптер проходит
`dap_workflow_create` → `dap_add_node` → `dap_run` без изменений.

## 7. Волны после пилота

Волна открывается, если: пилот прошёл DoD, есть спрос агента (использование в сценариях),
узел описан `NodeSpec`, есть ≥3 unit-теста и расширение корпуса.

| Волна | Узлы | Критерий входа |
|---|---|---|
| A — IO | csv_writer, table_reader/writer, excel_writer, json_reader | нужен агентам для выгрузки результатов |
| B — manipulation | concatenate, row_splitter, column_renamer, column_resorter, duplicate_filter, rule_engine, полный набор string/math ops | частые запросы в сценариях |
| C — analytics | row_aggregator, pivot, binner, normalizer, correlation, window, time series | после того как A/B стабильны |

## 8. Риски и меры

| Риск | Мера |
|---|---|
| DuckDB CSV не различает `""` и NULL | явные `nullstr`/`all_varchar=false` + правило `emptyStringIsNull`; unit-тест + эквивалентность `wf-n1` |
| Arrow↔DuckDB копирует данные | spike в Фазе 1 (день 1–2), замер; при отсутствии zero-copy — `ArrowStreamReader`/Appender и честная цифра в NATIVE.md |
| Javalin синхронный run блокирует диспетчер | `Executors.newVirtualThreadPerTaskExecutor()`, sync-run ждёт в виртуальном потоке; тест «health во время run» |
| Отмена запроса не доходит до DuckDB | `conn.interrupt()` + `Future.cancel(true)`; терминальные `cancelled`/`timeout` |
| Excel-расширения DuckDB нет офлайн | fallback Apache POI → Arrow; решение фиксируется в Фазе 2 |
| 10M строк не влезают в heap | DuckDB out-of-core, `memory_limit`/`temp_directory`; замер RAM в bench |
| Расхождение float на агрегатах | правило 1e-9·max(1, \|a\|, \|b\|); при систематике — запись в PARITY.md, не «чинить ценой» |
| oracle-узлы сломаны (формулы, missing) | native-only goldens (`wf-05`, `wf-10`) + unit-тесты; не инвестировать в oracle |
| MCP-совместимость | smoke в Фазе 1, e2e в Фазе 3 (`dap_workflow_create` + `dap_add_node` + `dap_run`) |

## 9. Метрики

| Метрика | Метод | Цель |
|---|---|---|
| Cold start | `java -jar` → `/v1/health` = 200 | < 2 с |
| CSV 10M read+filter+groupby | `tests/bench.py` | ≥ 3× быстрее KNIME |
| RAM на 10M | `Runtime.totalMemory()-freeMemory()` после исполнения | ≤ KNIME |
| Размер jar | `du -sh target/*.jar` | < 50 МБ |
| Размер образа | `docker images dap-native` | < 200 МБ |
| Параллельные ветви DAG | workflow с 2 независимыми ветвями | ≥ 1.8× против последовательно |
| Parity | `tests/parity.py --engine native` | ≥ 90 % ячеек |

## 10. Definition of Done и трассировка

| DoD | Чем проверяется | Фаза |
|---|---|---|
| сборка без `runtime/knime` | `make build-native`, `mvn -f engine-native/pom.xml test` | 1 |
| старт < 2 с, health | `make run-native` + `curl /v1/health` | 1 |
| 10 узлов + `configSchema` в dialog | `GET /v1/nodes/{id}/dialog` ×10 | 2 |
| корпус исполняется | `tests/parity.py --engine native` | 2 |
| parity ≥ 90 % | там же | 2 |
| бенчмарки и цифры | `tests/bench.py`, BENCH.md | 3 |
| образ < 200 МБ, healthy < 5 с | `docker build`/`docker run` | 3 |
| нет `org.knime` | `grep -r`, CI | 1+ |
| docs заполнены | NATIVE.md, PARITY.md, BENCH.md, MIGRATION.md | 3 |
| MCP без изменений | e2e create/add/run | 3 |

## 11. Открытые вопросы

1. **Коды ошибок:** оставляем контракт (400 на `POST …/nodes` c неизвестным factory) или правим
   frozen под формулировку «404 для узла»? Сейчас — контракт.
2. **Рабочая папка сессии:** писать в `~/Projects/dap` из сессии с workspace `~/Projects/knime-mcp`
   можно только через approval. Для фаз 1–3 открыть сессию с workspace `~/Projects/dap`.
3. **Polars:** порог входа — ≥2× на узле; кто принимает решение по цифрам bench (владелец фазы 3).

## 12. Чеклист по неделям

**Неделя 1 (Фаза 1, часть 1)**

- [ ] pom, скелет `NativeService`, `/v1/health`, виртуальные потоки
- [ ] `NodeRegistry` + 10 спеков, `DuckDbSession`, `LazyPlan`
- [ ] spike Arrow↔DuckDB с замером
- [ ] `csv_reader`, `row_filter`, `sorter` + тесты
- [ ] cold start замер

**Неделя 2 (Фаза 1, часть 2 + старт Фазы 2)**

- [ ] `column_filter`, `missing_value`
- [ ] `Dag`, `JobManager` (cancel/timeout), `WorkflowStore`, все маршруты
- [ ] `group_by` (multi-agg), начало `joiner`
- [ ] parity-раннер native + первый прогон

**Неделя 3 (Фаза 2 завершение + Фаза 3)**

- [ ] `joiner`, `string_manipulation`, `math_formula`, `excel_reader`
- [ ] dialog/profile, parity ≥ 90 %, PARITY.md обновлён
- [ ] bench.py, BENCH.md, Dockerfile, NATIVE.md, MIGRATION.md
- [ ] MCP e2e

## 13. Правила работы

- Oracle не чиним и не расширяем: он только источник baseline.
- Новый узел = `NodeSpec` JSON + executor + ≥3 теста + расширение корпуса.
- Правка `/v1` = frozen + `.sha256` + CHANGELOG.
- Сначала работает и совпадает с baseline, потом быстро, потом красиво.

