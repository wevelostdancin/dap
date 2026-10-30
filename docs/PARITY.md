# PARITY — native vs KNIME

Документ фиксирует, **что считается совпадением** между `dap-native` и oracle
(`engine/` + `runtime/knime/`), какие биндинги используются для корпуса и какие
расхождения уже известны. Обновляется при каждом прогоне `tests/parity.py`.

Артефакты:

| Артефакт | Что это |
|---|---|
| `api/openapi-v1-frozen.yaml` | замороженный контракт `/v1` (байт-в-байт копия `openapi.yaml` на момент заморозки) |
| `api/openapi-v1-frozen.sha256` | контрольная сумма замороженного контракта |
| `tests/corpus/*.json` | 12 workflow, движко-нейтральное описание |
| `tests/corpus/data/` | фикстуры + `datasets.json` (схема, NULL, строки) |
| `tests/baseline.json` | наблюдения oracle в **native-словаре** (после нормализации) |
| `tests/parity.py` | dual-run harness (`--validate`, `--write-baseline`, `--check-baseline`) |

## 1. Что сравнивается

| Что | Источник | Правило |
|---|---|---|
| Состояния узлов | `POST /v1/workflows/{id}/run` | набор и состояния совпадают, ожидается `EXECUTED` |
| Таблица | `GET .../nodes/{node}/table` | `rows` точно; имена и **типы** колонок точно; ячейки — по §2–§4 |
| Профиль | `GET .../nodes/{node}/profile` | `rows`, `missing`, `distinct` точно; `min/max/mean` с допуском |
| HTTP-коды | ошибки | `400` невалидный config/unknown factory, `404` неизвестный workflow/job/каталог-узел по GET, `500` ошибка исполнения |

Baseline фиксирует **наблюдаемое поведение oracle**, приведённое к native-словарю.
Если KNIME ведёт себя странно, native обязан это повторить либо расхождение попадает
в §6 с причиной.

## 2. Числа

- `Number (Integer)` / Arrow `Int64` — точное равенство.
- `Number (Float)` / Arrow `Float64`:

  ```
  |a - b| <= 1e-9 * max(1, |a|, |b|)
  ```

  Абсолютные 1e-9 покрывают пилот; относительный множитель — для агрегатов на 32k строк,
  где меняется порядок суммирования.
- Числа передаются строками (`"5.006"`), поэтому сравнение всегда идёт через `float`.
  CSV-фикстуры round-trip-ятся без потерь: Java `Double.toString` → кратчайшее представление.

## 3. NULL и пустая строка

- `NULL` — `null` в JSON (Arrow null bitmap). Позиции NULL совпадают **точно**, как и
  счётчик `missing` в профиле.
- **Пустая строка ≠ NULL**; `""` — значение и сравнивается как значение.
- В CSV-фикстурах: NULL — пустое некавыченное поле, `""` — кавыченное пустое.
  `csv_reader` обязан различать их (`emptyStringIsNull` в `configSchema`).
- Ни один shipped-датасет пустых строк не содержит, поэтому правило проверяется
  unit-тестами `csv_reader`, а не dual-run (§6.5).

## 4. Порядок строк

- `order: "ordered"` — позиционное сравнение; ставится только там, где порядок задан
  явным `Sorter`, а ключи образуют полный порядок на данных корпуса.
- `order: "unordered"` (по умолчанию) — сравнение как мультимножество: строки
  канонизируются (числа округляются до 9 знаков) и сортируются.
- Строковая сортировка: в корпусе ключи либо числовые, либо имеют tie-breaker до
  полного порядка. KNIME Sorter по умолчанию использует `NATURAL` (`stringComparison`),
  поэтому и native по умолчанию `collation: natural`: цифровые серии сравниваются
  численно (`1st-4th` < `10th`), текст — побайтово, исходная строка — финальный
  tie-breaker. Это нужно для `wf-07` (ключ `education`). `collation: binary` — чисто
  байтовый порядок, `collation: nocase` — байтовый без учёта регистра (`COLLATE NOCASE`).
- `NULL` в ключах сортировки: KNIME `missingToEnd=false` → NULL первыми при ASC.
  В корпусе таких ключей нет (§6.4), правило зафиксировано на будущее.

## 5. Типы: Arrow → имя типа KNIME

| Arrow | KNIME | Где в корпусе |
|---|---|---|
| `Int64` | `Number (Integer)` | `age`, `fnlwgt`, `NMHC(GT)` |
| `Float64` | `Number (Float)` | `sepal_length`, `T`, `Mean(...)` |
| `Utf8` | `String` | `species`, `education` |
| `Boolean` | `Boolean` | нет |
| `Date32` | `Date` | `Date` (AIR_QUALITY) |
| `Time64(us)` | `Time` | `Time` (AIR_QUALITY) |
| `Timestamp(us)` | `Date&Time (Timestamp)` | нет |
| `Decimal128(p,s)` | `Number (Decimal)` | нет |

`table.columns[].type` в native обязан отдавать именно эти строки. `Timestamp` и
`Decimal128` ещё не встречались в корпусе: значения выведены из каталога KNIME и
подтверждаются при первом появлении. `Number (Long Integer)` в native не используется —
`Count(...)` отдаётся как `Number (Integer)` (§6.2).

## 6. Биндинги логических узлов и известные расхождения

### 6.1 Таблица биндингов (oracle)

Корпус описывает логические узлы; oracle-биндинг переводит их в конкретные KNIME-узлы.
Причина выбора «не тех» узлов — ограничения oracle, перечисленные ниже.

| logical | oracle node | настройки/особенности |
|---|---|---|
| `table_source` | `ReadDatasetNodeFactory` | `{"dataset": "IRIS"}`; shipped-датасеты KNIME |
| `row_filter` | `RuleEngineFilterNodeFactory` | одна строка правила `$col$ op value ... => TRUE`; `is_null` → `NOT $col$ LIKE "*"` |
| `sorter` | `SorterNodeFactory` | `sortingCriteria[]`, `stringComparison: NATURAL` |
| `group_by` | `RowAggregatorNodeFactory` | ровно один ключ и один агрегат; `mean→AVERAGE`, `count→COUNT`, `sum→SUM`; выход `Mean(x)` / `Count(x)` нормализуется |
| `joiner` | `Joiner3NodeFactory` | `matchingCriteria[]`, `mergeJoinColumns`, `MANUAL`+`includeUnknownColumns` (иначе 0 колонок) |
| `column_filter` | `FilterColumnNodeFactory` | только `exclude[]`; `selectionOption` по умолчанию `EnforceExclusion` |
| `missing_value` | `RuleEngineNodeFactory` | два правила: `$col$ LIKE "*" => $col$`, `TRUE => <constant>`; замена in-place |
| `string_manipulation` | `StringManipulationNodeFactory` | `upperCase($col$)`, `append_column=true` + `replaced_column=<col>` (так узел заменяет колонку) |

Эти биндинги — часть **семантического слоя**, а не «подгонка результатов»: каждый
маппинг проверен на корпусе, а нормализация (имена/типы) описана явно в `Binding.logical`.

### 6.2 Имена и типы агрегатных колонок
KNIME GroupBy называет агрегаты `Mean(<column>)`, `Count(<column>)`, `Sum(<column>)`
(политика `Aggregation method (column name)`). Native **повторяет** это правило: корпус
сортирует по `Count(age)`, и произвольный алиас сломал бы сравнение.
Так как oracle не может выполнить GroupBy-агрегацию (§6.6), биндинг использует
Row Aggregator, а harness нормализует `OCCURRENCE_COUNT` → `Count(x)` и тип
`Number (Long Integer)` → `Number (Integer)`. Ветка `mean` даёт колонку с исходным именем —
normalize переименовывает её в `Mean(x)`.

### 6.3 `distinct` в профиле
KNIME считает точный `distinct` до 100 000 значений на колонку. Native использует
`COUNT(DISTINCT)` до того же порога, выше — `APPROX_COUNT_DISTINCT` с допуском 1 %.
Максимум в корпусе — 33k (ADULT `fnlwgt`), на пилоте сверка точная.

### 6.4 NULL в ключах сортировки
Корпус не сортирует по колонкам с NULL (WORLDBANK-колонки с NULL не участвуют в
`ORDER BY`). Правило KNIME (`missingToEnd=false` → NULL сначала при ASC) зафиксировано,
но не проверяется до появления такого кейса.

### 6.5 Пустая строка в CSV
Shipped-датасеты пустых строк не содержат, поэтому `""` vs `NULL` в корпусе не
наблюдается. Проверяется unit-тестами `csv_reader` + правилом §3.

### 6.6 Ограничения oracle, определившие биндинги
Статус у всех — **документируем** (native реализует пилотный узел, oracle — эквивалент).

1. **Файловые ридеры не исполняются.** CSV Reader / Excel Reader падают с
   `Execute failed: Workflow context required.` (file-handling KNIME 5.12 требует
   workflow-context, которого у in-memory workflow нет). Отсюда: в dual-run корпусе
   источник — `Example Data Reader`, а `csv_reader`/`excel_reader` проверяются
   native-only через эквивалентность (`wf-n1`, `wf-n2`).
2. **`row3.RowFilter` не работает по колонкам.** Любой колоночный предикат даёт при run
   `Configure failed (NullPointerException) ... DataType.isCollectionType()`, потому что
   `columnType` в шаблоне содержит только `is_null` (нет `cell_class`). Работает только
   row key. Поэтому фильтры идут через `RuleEngineFilterNodeFactory`.
3. **GroupBy-агрегации недоступны через `/v1`.** `aggregationColumn.columnTypes` —
   ConfigArray, а JSON-мерджер принимает в массивах только скаляры; пустые
   config-массивы (`patternAggregators`, `dataTypeAggregators`) расширить нельзя.
   Работает только группировка без агрегации. Отсюда `RowAggregatorNodeFactory`
   и ограничение «один агрегат на узел».
4. **Missing Value не конфигурируется** (`columnSettings`/`dataTypeSettings` — пустые
   config без шаблона). Замена NULL на константу выражена Rule Engine (побитово
   эквивалентно для `constant`).
5. **Формулы недоступны.** `JEPNodeFactory` имеет пустую схему настроек;
   `MultiColumnJEP` ломается на `String for key "expression"`; `Expression`
   (`ExpressionRowMapperNodeFactory`) конфигурируется, но любой script падает с
   `Could not initialize class ...ArrowColumnStoreFactory` — сломана Arrow-подсистема
   окружения, а не синтаксис. Отсюда `math_formula` — native-only golden (`wf-05`, `wf-10`).
6. **Column Filter** — core-версия (`DataColumnSpecFilterNodeFactory`) имеет пустой
   settingsXml и падает с `Config for key "column-filter"`, поэтому используется
   `FilterColumnNodeFactory` с `exclude[]`. Включение (`include`) недоступно:
   ключа `include` в шаблоне нет.

### 6.7 Коды ошибок: коллизия контракта и критериев
Критерий «`404` для неизвестного узла» из ТЗ расходится с замороженным контрактом:
`POST /v1/workflows/{id}/nodes` с неизвестным `factory` возвращает **400**
`unknown node: ...`, а `GET /v1/nodes/{id}` с неизвестным id — **404**. Так как `/v1`
заморожен, native обязан повторять контракт (400 на POST, 404 на GET), а не буквальный
текст критерия. Вопрос вынесен в отчёт; до решения — контракт главнее.

### 6.8 Native-only golden-значения
`wf-05` и `wf-10` содержат `expect`/`expect_profile` проверки. Их эталонные числа
посчитаны независимо от native — pandas-скриптом по `tests/corpus/data/iris.csv`
(`petal_area = petal_length * petal_width`; `sepal_total = sepal_length + sepal_width`).
Это не dual-run: oracle не может выполнить формулу (§6.6.5). Значения:

| workflow | проверка | значения |
|---|---|---|
| `wf-05` | `Mean(petal_area)` по species | setosa 0.3628, versicolor 5.7204, virginica 11.2962 |
| `wf-05` | профиль `petal_area` | rows 150, distinct 101, min 0.11, max 15.87, mean 5.7931333333333335 |
| `wf-10` | строк после `sepal_total > 8.0` | 122 |
| `wf-10` | `Mean(sepal_total)` по species | setosa 8.815625, versicolor 8.941463414634146, virginica 9.606122448979592 |

### 6.9 Что корпус осознанно не покрывает
- Несколько агрегатов в одном GroupBy (oracle-блокер §6.6.3) — проверяется unit-тестами native.
- `column_filter.include`, `missing_value` с `mean`/`mode`, `string_manipulation` кроме `upper`
  — реализуются в native, но не имеют dual-run пары; покрываются unit-тестами.
- Joiner `left`/`right` outer: биндинг поддерживает `how: "left"`, в корпусе только `inner`.

## 7. Native engine run

`--engine native` исполняет корпус на `dap-native` и сравнивает table/profile с
`tests/baseline.json`; baseline пишет только oracle (`--write-baseline` + native — exit 2).

```bash
cd engine-native && ./mvnw -q -o -DskipTests package
DAP_PORT=7911 java -jar target/dap-native.jar &
python3 tests/parity.py --engine native --base http://127.0.0.1:7911
```

- Биндинг: логический тип -> `dap.<type>`; `POST .../nodes` с `{"factory": "dap.<type>",
  "settings": <config>}`, связи — `connect` с `toPort`, `POST .../run` синхронный;
  не-200 add/run или узел не в успешном состоянии — FAIL с id узла и текстом ошибки.
- Dataset shorthand: `{"dataset": "IRIS"}` разворачивает раннер в `dap.csv_reader`:
  `file` — абсолютный путь из `data/datasets.json`, `delimiter`, `header`,
  `columns[{name,type}]` (Arrow), `emptyStringIsNull: false`; `excel_reader` — `<stem>.xlsx`
  без `sheet`; неизвестный датасет или нет файла — FAIL, не SKIP.
- Native-only: `expect`/`expect_profile` сверяют `rows`, колонки (имя/тип и
  `missing`/`distinct`/`min`/`max`/`mean`, если заданы) и `data` по §2–§4; `expect_profile`
  сверяет только объявленные колонки (по имени; отсутствие объявленной — FAIL, лишние
  колонки ответа игнорируются); `equivalent`
  исполняет указанный workflow и сравнивает таблицу `node_ref` (unordered). Состояния
  узлов с baseline не сравниваются (имена engine-specific), успешность — все `EXECUTED`.
- 90 % роадмапа — `matching cells N/M (X%)` по восьми dual-run: `rows`, имя+тип
  колонки, ячейки data и поля профиля из baseline; native-only не в счёт.

## 8. Как перегенерировать baseline

```bash
make run                                          # oracle на 127.0.0.1:7800
python3 tests/parity.py --validate
python3 tests/parity.py --engine oracle --write-baseline
python3 tests/parity.py --engine oracle --check-baseline   # должен быть зелёным дважды подряд
```

Baseline коммитится вместе с корпусом. Изменение корпуса, данных или биндингов требует
перегенерации и записи причины в commit message (и CHANGELOG.md, если менялся `/v1`).
