# QUALITY — как проверяется качество

Проверки локальные, без сети и без внешнего oracle: JUnit-набор движка, golden-фикстуры
«вход → ожидаемый выход», чёрный smoke по HTTP и MCP e2e. Критерий приёмки — зелёный
`make check`; правила сравнения ниже обязательны для goldens и unit-тестов.

## 1. Уровни проверок

| Уровень | Команда | Что покрывает |
|---|---|---|
| JUnit | `make test-native` | 204 теста: исполнители, Dag/JobManager, DuckDB-сессии, каталог, HTTP-интеграция |
| Goldens | `make goldens` | 13 workflow на чистом экземпляре `:7811`; сравнение с `expected.json` или литеральными ожиданиями |
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
204 теста в 25 классах:

- **Исполнители** — по классу на узел (`CsvReaderExecutorTest`, `RowFilterExecutorTest`,
  `ColumnFilterExecutorTest`, `ColumnRenamerExecutorTest`, `SorterExecutorTest`,
  `MissingValueExecutorTest`, `ExcelReaderExecutorTest`, `GroupByExecutorTest`,
  `JoinerExecutorTest`, `StringManipulationExecutorTest`, `MathFormulaExecutorTest`):
  валидация config, типы и имена выходных колонок, NULL/`""`, порядок строк.
- **Движок** — `DuckDbSessionSettingsTest` (env-лимиты, нормализация, spill),
  `DuckDbSessionLoadIntoTest`, `MaterializationInterruptTest`, `ProfileDistinctTest`,
  `TypeMapTest`, `ArrowDuckDbSpikeTest`.
- **Исполнение** — `DagTest` (уровни, параллельные ветви, fail-fast, снимок графа),
  `JobManagerTest` (сериализация одного workflow, параллельность разных, timeout,
  cancel, закрытие).
- **Каталог и HTTP** — `NodeRegistryTest`, `NodeSpecTest`, `CatalogHandlersTest`,
  `NativeServiceTest` (18 интеграционных тестов на реальном Javalin: все маршруты,
  400/404/408/409/500, изоляция сессий, health во время sync-run, shutdown).

Минимум для нового узла — 3 теста: валидация config, успешный путь, граничный случай
(NULL, пустой результат, неверный тип).

## 3. Golden-фикстуры

Каталог `tests/goldens-native/`:

```
goldens-native/
  runner.py            # прогон и сравнение
  expected.json        # замороженные ожидания baseline-workflow
  data/                # входные фикстуры + datasets.json
  workflows/           # 13 workflow: wf-*.json
```

Сейчас 13 workflow: 8 `kind: "baseline"` (сравнение с `expected.json`) и 5
`kind: "native"` (литеральные проверки или эквивалентность). Фикстуры данных:
`iris.csv`/`iris.xlsx`, `adult.csv`, `air_quality.csv`, `worldbank_countries.csv`;
`datasets.json` задаёт файл, разделитель, заголовок, схему (`arrow`) и число
пропусков по колонкам.

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

- Логические типы узлов: `table_source`, `csv_reader`, `excel_reader`, `row_filter`,
  `sorter`, `group_by`, `joiner`, `column_filter`, `column_renamer`,
  `missing_value`, `string_manipulation`, `math_formula`. Раннер переводит их в
  `dap.<type>`, а `table_source` — в `dap.csv_reader`.
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
- Пустая строка — значение, а не NULL: `""` сравнивается как значение. В CSV пустое
  некавыченное поле — NULL, кавыченное `""` — пустая строка (если
  `emptyStringIsNull` не включён). Правило закреплено unit-тестами `csv_reader`.
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
  sketch, и если оценка не выше 110 000, значение досчитывается точно. Выше порога
  допускается измеренная погрешность sketch (HLL P=10, σ ≈ 3,2 %).
- Итог прогона: FAIL любого workflow — exit 1. Счётчик `matching cells N/M` в
  `runner.py` — отчётность по baseline-проверкам, проходного процента нет: golden
  обязан совпасть полностью.

## 5. Контракт ошибок

| Код | Когда | Тело |
|---|---|---|
| 200 | успех | объект операции |
| 202 | async run принят | job |
| 400 | невалидная сессия, неизвестный ключ `settings`, неизвестный `factory` при добавлении, `settings` не объект, `PATCH` без `settings`, `from`/`to`/порт вне диапазона, дубль порта, самосвязь, цикл, удаление связанного узла, узел без табличного выхода, неизвестный узел workflow в `/table`/`/profile`, отсутствующий `dir` у save, отрицательный порт | `{"error": "..."}` |
| 404 | неизвестный или чужой `wf-*`/`job-*`, неизвестный узел каталога в `GET /v1/nodes/{id}`, неизвестный узел workflow в `PATCH`/`DELETE`/`dialog`, неизвестное соединение, неизвестный маршрут или метод | `{"error": "..."}` |
| 408 | истёк `timeoutSec` sync-run | job со `state: "timeout"` |
| 409 | отмена уже завершённого job | `{"ok": false, "id": ..., "job": {...}}` |
| 500 | ошибка исполнения (узел `ERROR`), невалидное значение config, нечитаемый файл, битый JSON, лимит workflow на сессию, нецелый query-параметр | `{"error": "..."}` или job со `state: "failed"` для sync-run |

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
4. Golden-workflow, покрывающий узел, и `runner.py --validate` без замечаний;
   ожидания посчитаны независимо.
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
