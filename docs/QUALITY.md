# QUALITY — как проверяется качество

Проверки локальные, без сети и без внешнего oracle: JUnit-набор движка, golden-фикстуры
«вход → ожидаемый выход», чёрный smoke по HTTP и MCP e2e. Критерий приёмки — зелёный
`make check`; правила сравнения ниже обязательны для goldens и unit-тестов.

## 1. Уровни проверок

| Уровень | Команда | Что покрывает |
|---|---|---|
| JUnit | `make test-native` | 859 тестов в 115 классах: исполнители, IO-кэш, Dag/JobManager, DuckDB-сессии, каталог, HTTP-интеграция |
| Goldens | `make goldens` | 35 workflow на чистом экземпляре `:7811`; сравнение с `expected.json` или литеральными ожиданиями |
| Coverage | `make coverage` | 69 задач таксономии через `/v1`; сейчас 68/69 (F6 — не-цель) |
| Структура goldens | `python3 tests/goldens-native/runner.py --validate` | проверка фикстур без движка |
| Smoke | `make smoke` (`DAP_BASE`, по умолчанию `:7800`) | 9 чёрных проверок контракта и пяти узлов на запущенном движке |
| MCP e2e | `make mcp-e2e` | адаптер поднимает движок, проходит create → add_node → connect → run → table → delete |
| Всё | `make check` | `test-native + goldens + goldens-validate + test-mcp + mcp-e2e + lint` |

`make goldens` собирает jar, затем `tests/run_goldens.py` поднимает его на
`127.0.0.1:7811` с отдельным каталогом `/tmp/dap-goldens`, ждёт `/v1/health`,
прогоняет `tests/goldens-native/runner.py` и гасит движок через `POST /v1/shutdown`.
Резидентный сервис на `:7800` не затрагивается.

## 2. Unit-тесты

JUnit 5.11.4, запуск через `./mvnw test` (surefire, `--add-opens java.base/java.nio`).
859 тестов в 115 классах:

- **Исполнители** — по классу на узел (`CsvReaderExecutorTest`, `ExcelReaderExecutorTest`,
  `ParquetReaderExecutorTest`, `JsonReaderExecutorTest`, `CsvWriterExecutorTest`,
  `ParquetWriterExecutorTest`, `JsonWriterExecutorTest`, `RowFilterExecutorTest`,
  `ColumnFilterExecutorTest`, `ColumnRenamerExecutorTest`, `SorterExecutorTest`,
  `MissingValueExecutorTest`, `GroupByExecutorTest`, `JoinerExecutorTest`,
  `StringManipulationExecutorTest`, `MathFormulaExecutorTest`, `TypeCastExecutorTest`,
  `DuplicateFilterExecutorTest`, `ValueReplaceExecutorTest`, `ColumnResorterExecutorTest`,
  `OutlierFilterExecutorTest`, `DateTimeExecutorTest`, `RuleEngineExecutorTest`,
  `BinnerExecutorTest`, `NormalizerExecutorTest`, `EncoderExecutorTest`,
  `SampleRowsExecutorTest`, `RowAggregatorExecutorTest`, `PivotExecutorTest`,
  `UnpivotExecutorTest`, `WindowExecutorTest`, `TopNPerGroupExecutorTest`,
  `ConcatenateExecutorTest`, `DescribeExecutorTest`, `ValueCountsExecutorTest`,
  `CorrelationExecutorTest`, `HistogramExecutorTest`, `QuantileExecutorTest`,
  `OutlierDetectExecutorTest`, `ResampleExecutorTest`, `RollingExecutorTest`,
  `DiffExecutorTest`, `DateRangeExecutorTest`, `ExcelWriterExecutorTest`):
  валидация config, типы и имена выходных колонок, NULL/`""`, порядок строк.
  `TimeSeriesSpecsTest` сверяет спеки P3 с контрактами исполнителей (порты, обязательные
  ключи, enum-значения), `P2ReviewRegressionTest` — регрессии волны P2.
- **IO и кэш** — `IngestServiceTest`: CSV/JSON/Parquet → Parquet, повторный вызов
  возвращает тот же файл (`cached`), неизвестный формат и отсутствующий файл
  отклоняются.
- **Движок** — `DuckDbSessionSettingsTest` (env-лимиты, нормализация, spill),
  `DuckDbSessionLoadIntoTest`, `MaterializationInterruptTest`, `ProfileDistinctTest`,
  `TypeMapTest`, `ArrowDuckDbSpikeTest`.
- **Исполнение** — `DagTest` (уровни, параллельные ветви, fail-fast, снимок графа),
  `JobManagerTest` (сериализация одного workflow, параллельность разных, timeout,
  cancel, закрытие).
- **Каталог и HTTP** — `NodeRegistryTest`, `NodeSpecTest`, `CatalogHandlersTest`,
  `NativeServiceTest` (22 интеграционных теста на реальном Javalin: маршруты,
  400/404/408/409/500, изоляция сессий, health во время sync-run, shutdown,
  idle-timeout, `POST /v1/datasets` → `dap.parquet_reader`).

Kendall tau-b и p-value сверяются с эталоном `tests/reference/kendall_pvalue.py`
(scipy, двусторонняя нормальная аппроксимация): запуск —
`/Users/vitalypesnev/Projects/dap/build/refvenv/bin/python tests/reference/kendall_pvalue.py`,
ожидания `CorrelationExecutorTest` совпадают с эталоном до `1e-9`.

Минимум для нового узла — 3 теста: валидация config, успешный путь, граничный случай
(NULL, пустой результат, неверный тип).

## 3. Golden-фикстуры

Каталог `tests/goldens-native/`:

```
goldens-native/
  runner.py            # прогон и сравнение
  expected.json        # замороженные ожидания baseline-workflow
  data/                # входные фикстуры + datasets.json
  workflows/           # 25 workflow: wf-*.json
```

Сейчас 35 workflow: 8 `kind: "baseline"` (сравнение с `expected.json`) и 27
`kind: "native"` (литеральные проверки или эквивалентность). P6 добавил `wf-p6-*`:
file_reader, asof join, correlation, outlier DBSCAN, cluster, PCA, resample, survival,
A/B, time_series. Фикстуры данных:
`iris.csv`/`iris.xlsx`, `adult.csv`, `air_quality.csv`, `worldbank_countries.csv`,
`p2.csv`, `join_left.csv`/`join_right.csv`; `datasets.json` задаёт файл, разделитель, заголовок, схему (`arrow`) и
число пропусков по колонкам. `wf-n4-p2-clean` — golden волны P2: `type_cast` →
`value_replace` → `duplicate_filter` → `sorter` на `p2.csv`; `wf-n5-p3-stats` — golden
волны P3: `value_counts` по колонке `category` из `p2.csv` (A=3, B=2, C=1);
`wf-n10-joiner-modes` — golden волны P5: `full`/`semi`/`anti` джойны на
`join_left.csv`/`join_right.csv`; `wf-n11-hypothesis` — Welch t-тест и ANOVA на
`iris.csv`, χ² на `p2.csv`; `wf-n12-regression` — коэффициенты и predict
`dap.linear_regression` на `iris.csv` и `p2.csv`.

### Формат workflow

```json
{
  "id": "wf-n3-iris-renamer",
  "kind": "native",
  "nodes": [
    {"id": "src", "type": "table_source", "config": {"dataset": "IRIS"}},
    {"id": "ren", "type": "column_renamer", "config": {"renames": [{"from": "sepal_length", "to": "sl"}]}}
  ],
  "edges": [{"from": "src", "to": "ren"}],
  "checks": [
    {"node": "ren", "kind": "expect", "rows": 150,
     "columns": [{"name": "sl", "type": "Number (Float)"}],
     "data": [["5.1"]], "order": "ordered"}
  ]
}
```

Пример сокращён: в реальной проверке перечислены все колонки и строки.

- Логические типы узлов — id каталога без префикса `dap.` (`csv_reader`,
  `row_filter`, `sorter`, `group_by`, `joiner`, `column_renamer`, `type_cast`,
  `duplicate_filter`, `value_replace`, `column_resorter`, `outlier_filter`, `date_time`,
  `rule_engine`, `binner`, `normalizer`, `encoder`, `sample_rows`, `row_aggregator`,
  `pivot`, `unpivot`, `window`, `top_n_per_group`, `concatenate` и остальные), плюс
  `table_source`. Раннер переводит их в `dap.<type>`, а `table_source` — в
  `dap.csv_reader`.
- `config.dataset` — сокращение раннера: он подставляет абсолютный путь из
  `data/`, разделитель, заголовок и схему колонок. Движок всегда получает реальный
  путь; сам узел сокращений не знает.
- `--validate` проверяет структуру без движка: уникальность id, известные типы,
  обязательные поля config, ссылки рёбер и проверок, наличие `rows` у
  `expect`/`expect_profile` и `type` у колонок `expect`.

### Виды проверок

| `kind` | Где допустим | Что сравнивается |
|---|---|---|
| `table` | baseline | таблица узла с `expected.json` |
| `profile` | baseline | профиль узла с `expected.json` |
| `expect` | native | литеральные `rows`, `columns`, `data`; если у колонки заданы `missing`/`distinct`/`min`/`max`/`mean` — ещё и профиль |
| `expect_profile` | native | литеральный профиль; сравниваются только объявленные колонки |
| `equivalent` | native | таблица узла с таблицей `node_ref` другого workflow |

`expected.json` хранит `corpusVersion`, версию движка и `workflows → checks` с ключом
`"<node>:<kind>"` (например, `"sort:table"`). Для `kind: "native"` он не
используется: ожидания лежат в самом workflow.

Новый golden: добавить `workflows/wf-*.json`, при необходимости фикстуру в `data/`
и запись в `datasets.json`, затем `runner.py --validate`, `make goldens` и
зафиксировать ожидания. Эталонные значения нельзя подгонять под фактический ответ:
они считаются независимо (например, `statistics.mean` по фикстуре) и коммитятся.

## 4. Правила сравнения

- `rows` — точное равенство.
- Колонки — имена и `type` сравниваются точно, порядок колонок — позиционно.
- Целые (`Number (Integer)`) — точное равенство.
- Дробные (`Number (Float)`):

  ```
  |a - b| <= 1e-9 * max(1, |a|, |b|)
  ```

  Числа в JSON приходят числом или строкой, поэтому сравнение всегда идёт через
  `float`.
- NULL — позиции и счётчик `missing` совпадают точно. `NULL` — это `null` в JSON.
- Пустая строка — значение, а не NULL: `""` сравнивается как значение. В CSV через
  `read_csv` и некавыченное, и кавыченное пустое поле — NULL; `emptyStringIsNull`
  принимается для совместимости, но не влияет на результат. Правило закреплено
  unit-тестами `csv_reader` и `IngestService`.
- Порядок строк: `order: "ordered"` — позиционное сравнение (ставится только там,
  где порядок задан явным `sorter` и ключи образуют полный порядок);
  `order: "unordered"` (по умолчанию) — сравнение как мультимножества: числа
  канонизируются округлением до 9 знаков, строки сравниваются как есть, строки
  сортируются.
- Профиль: `rows`, `missing`, `distinct` — точно; `min`/`max`/`mean` — с тем же
  допуском, что и дробные. `expect_profile` сравнивает только объявленные колонки:
  отсутствие объявленной колонки — FAIL, лишние колонки ответа игнорируются.
  Baseline-`profile` сравнивает полный набор колонок.
- `distinct`: до 100 000 строк — точный `COUNT(DISTINCT)`; выше — приблизительный
  sketch, и если строк не больше 1 000 000, а оценка не выше 110 000, значение
  досчитывается точно одним дополнительным сканом. Выше 1 000 000 строк тем же
  дополнительным запросом уточняются только колонки с оценкой не выше 10 000
  (дешёвые низкокардинальные признаки, например категории); остальные остаются
  приблизительными — точный distinct по миллионам уникальных значений
  не помещается в память. Выше порога допускается измеренная погрешность sketch
  (HLL P=10, σ ≈ 3,2 %). Это же значение видит profile-секция отчёта.
- Итог прогона: FAIL любого workflow — exit 1. Счётчик `matching cells N/M` в
  `runner.py` — отчётность по baseline-проверкам, проходного процента нет: golden
  обязан совпасть полностью.

## 5. Контракт ошибок

Тело ошибки аддитивно расширено: `{"error": "...", "code": "...", "hint": "..."}`.
`code` обязателен, выводится из статуса (`bad_request`, `not_found`, `conflict`,
`server_error`), если у ошибки нет специфичного; `hint` необязателен. Старые поля
не меняются, поэтому клиенты, читающие только `error`, продолжают работать.

| Код | Когда | Тело |
|---|---|---|
| 200 | успех | объект операции |
| 202 | async run принят | job |
| 400 | невалидная сессия, неизвестный ключ `settings`, неизвестный `factory` при добавлении, `settings` не объект, `PATCH` без `settings`, `from`/`to`/порт вне диапазона, дубль порта, самосвязь, цикл, удаление связанного узла, отсутствующий `dir` у save, отрицательный порт, нецелый query-параметр | `{"error": "...", "code": "...", "hint": "..."}`; коды `bad_request`, `unknown_setting`, `invalid_query_param` |
| 404 | неизвестный или чужой `wf-*`/`job-*`, неизвестный узел каталога в `GET /v1/nodes/{id}`, неизвестный узел workflow в `/table`/`/profile`/`PATCH`/`DELETE`/`dialog`, неизвестное соединение, неизвестный маршрут или метод | `{"error": "...", "code": "...", "hint": "..."}`; коды `not_found`, `unknown_node` |
| 408 | истёк `timeoutSec` sync-run; истёк `DAP_QUERY_TIMEOUT_SEC` у `/table`/`/profile` | job со `state: "timeout"`; `{"error": "...", "code": "query_timeout", "hint": "..."}` |
| 409 | отмена уже завершённого job; лимит workflow на сессию (`workflow_limit`, поле `limit`); у узла ещё нет таблицы (`not_materialized`); узел упал (`node_failed` с сообщением узла) | `{"ok": false, "id": ..., "job": {...}, "error": "...", "code": "not_cancellable"}` или `{"error": "...", "code": "...", "hint": "..."}` |
| 500 | ошибка исполнения (узел `ERROR`), невалидное значение config, нечитаемый файл, битый JSON | `{"error": "...", "code": "server_error"}` или job со `state: "failed"` и `code: "node_failed"` для sync-run |

Важно: неизвестный **ключ** настроек — 400 сразу; неверное **значение** (тип, имя
колонки, диапазон) обнаруживает исполнитель при run, поэтому sync-run отвечает 500,
а job получает `failed` с сообщением узла. Ошибка узла видна и в
`GET .../nodes`: `state: "ERROR"`, `message`.

## 6. Что считается готовым узлом

1. JSON-спека в `engine-native/src/main/resources/specs/` и строка в `index.txt`;
   `available: true`, `configSchema` с типами, умолчаниями и `required`.
2. Исполнитель в `nodes/`, зарегистрированный в `NativeService.defaultExecutors`,
   возвращает `LazyPlan` и валидирует config с понятными сообщениями.
3. Не менее трёх unit-тестов: валидация, успешный путь, граничный случай.
4. Golden-сценарий на волну: один workflow покрывает несколько узлов волны
   end-to-end (например, `wf-n4-p2-clean` — `type_cast` → `value_replace` →
   `duplicate_filter` → `sorter` на `p2.csv`); `runner.py --validate` без замечаний,
   ожидания посчитаны независимо. Golden на каждый узел не требуется. Для IO-узлов
   (читатели/писатели) вместо golden — unit-тесты плюс интеграционный round-trip
   (ingest → `parquet_reader` → `table`), потому что golden-раннер не проверяет файлы.
5. Строка в [NODES.md](NODES.md): назначение, таблица config, пример JSON.
6. В `engine-native/` нет ссылок на внешний аналитический рантайм (проверяет
   `make lint`); jar содержит ровно одну DuckDB-native библиотеку и меньше 50 МиБ.
7. `make check` зелёный.

## 7. Воспроизводимость

- JDK 21 через `DAP_JDK`; зависимости пиннятся `engine-native/pom.xml` и `./mvnw`.
- Goldens не ходят в сеть и не требуют внешней БД: вход — файлы в `data/`, движок
  запускается на свободном порту со своим временным каталогом.
- `expected.json` коммитится; изменение ожиданий — отдельный коммит с причиной.
- Прогон не трогает резидентный сервис на `:7800`.
