# NODES — каталог узлов

Каталог нативного движка: 66 узлов `dap.*`. Источник истины — JSON-спеки
`engine-native/src/main/resources/specs/*.json`, список — `specs/index.txt` (66 строк);
`NodeRegistry` читает их из classpath и падает при отсутствующем или дублирующем
спеке. Все 66 узлов имеют `available: true` и `tier: core`.

## 1. Общие правила

- `id` узла — `dap.<type>`; он же передаётся в `factory` при
  `POST /v1/workflows/{id}/nodes`.
- Настройки — плоский JSON по ключам `configSchema`. Неизвестный ключ даёт 400
  сразу; значения проверяются исполнителем при run (ошибка значения — 500).
- `required` в `configSchema` влияет на `optional` в `/dialog`, а не на приём
  запроса: фактическую обязательность проверяет исполнитель.
- У всех узлов один табличный выход (порт 0). У источников нет входов; у `dap.joiner`
  два входа (0 — Left, 1 — Right), у `dap.concatenate` — два входа (0 — Top,
  1 — Bottom), у `dap.data_quality` — два входа (0 — Data, 1 — Reference; второй
  нужен только для проверок `referential`), у `dap.chart_grid` — восемь входов
  (0…7, подключаются только используемые), у остальных один вход (0).
  `dap.concatenate` требует оба входа.
- Все преобразователи — ленивые SQL-отношения: данные читаются только в `/table`
  и `/profile`; исключения — читатели и писатели (см. ниже).
- Писатели (`dap.csv_writer`, `dap.parquet_writer`, `dap.json_writer`,
  `dap.excel_writer`, `dap.markdown_report`, `dap.chart`, `dap.chart_grid`) — Sink:
  один вход и один выход, входная таблица возвращается неизменной (pass-through),
  поэтому граф можно продолжать за писателем. Файл пишется на run (csv/parquet/json —
  на job-соединении, excel — сборкой книги в памяти); родительские каталоги создаются.
- Ошибки config и файловые ошибки видны на run: sync-запуск отвечает 500, job
  получает `failed`, узел — `ERROR` с сообщением.
- Типы колонок в `/table` и `/profile` — имена контракта:

  | Arrow | `type` в ответе |
  |---|---|
  | `Int64` | `Number (Integer)` |
  | `Float64` | `Number (Float)` |
  | `Utf8` | `String` |
  | `Boolean` | `Boolean` |
  | `Date32` | `Date` |
  | `Time64` | `Time` |
  | `Timestamp` | `Date&Time (Timestamp)` |
  | `Decimal128(p,s)` | `Number (Decimal)` |

- Ячейки `data` в `/table` — строки (числа в десятичном виде) или `null`;
  `preview` — число возвращённых строк.

- Особенности узлов волны P2:
  - `dap.duplicate_filter` без `keys` (или с пустым массивом) — `SELECT DISTINCT *`;
    с ключами остаётся одна произвольная строка на ключ, порядок строк не определён.
  - `dap.pivot` требует непустой `values`: список фиксирует схему, поэтому значение,
    которого нет в данных, всё равно даёт колонку (из NULL).
  - `dap.unpivot` отбрасывает строки, где выбранное значение NULL, — это поведение
    DuckDB `UNPIVOT`; остальные колонки сохраняются.
  - `dap.type_cast` принимает `Int64`, `Float64`, `Utf8`, `Boolean`, `Date32`,
    `Timestamp` и `Decimal128(p,s)`; `Time64` не приводится.
  - `dap.window` при непустом `orderBy` считает `sum`/`mean` running-агрегатами
    (`UNBOUNDED PRECEDING` → текущая строка), без `orderBy` — по всему разделу.
  - `dap.outlier_filter` берёт статистику по всему входу; NULL всегда проходит.
  - `dap.value_replace` сравнивает точно и с участием NULL (`IS NOT DISTINCT FROM`);
    для колонки действует первое совпавшее правило.
  - `dap.row_aggregator`: NULL в любой выбранной колонке делает `sum`/`mean`/`min`/`max`
    NULL для строки, `count` считает не-NULL.

- Особенности узлов волны P3:
  - `dap.describe` — ровно одна строка на колонку; `type` — имя контракта
    (`Number (Integer)`, `String`, …); нечисловые колонки оставляют `min`, `max`,
    `mean` и `stddev` пустыми (NULL).
  - `dap.value_counts` — NULL исключены; значения приводятся к строке, поэтому `value`
    всегда `String`; порядок — по убыванию `count`, при равенстве — по возрастанию
    значения; `limit` (по умолчанию 20) обрезает хвост.
  - `dap.correlation` — только пары `i < j` в порядке `columns`; `pearson` считает
    `CORR` по сырым значениям, `spearman` — по `RANK()` (равные значения делят ранг),
    `kendall` — tau-b; строки с NULL в любой колонке пары исключены. P6 добавил
    `partial`, `output` long/matrix, p-value и доверительный интервал (см. §37).
  - `dap.histogram` — выдаются все бины, включая пустые (`count: 0`); последний бин
    включает максимум; константная колонка попадает целиком в первый бин.
  - `dap.quantile` — вероятности в `[0, 1]` включительно; `QUANTILE_CONT` интерполирует
    между соседними значениями, строка на каждую вероятность в порядке массива.
  - `dap.outlier_detect` — `iqr`/`zscore` добавляют Boolean-колонку `outlier`; NULL не
    бывает выбросом; статистика берётся по всему входу, а не по окну. P6 добавил
    `dbscan`/`lof`/`isolation_forest` с Float64-колонкой `score` (см. §40).
  - `dap.resample` — бакеты DuckDB `time_bucket`, `every` по умолчанию `1 day`; старт бакета
    заменяет колонку времени, выход упорядочен по ней.
  - `dap.rolling` — окно по строкам (`ROWS BETWEEN n-1 PRECEDING AND CURRENT ROW`),
    а не по времени: первые строки раздела считают по неполному окну.
  - `dap.diff` — `column - LAG(column)`; первая строка каждого раздела NULL, тип колонки
    сохраняется.
  - `dap.date_range` — Source без входов, одна колонка `date`; обе границы включительно.
  - `dap.excel_writer` — Sink: даты и время пишутся как `inlineStr` в ISO-виде (Excel
    покажет их текстом), запись атомарная.

| id | Название | Категория | Входы |
|---|---|---|---|
| `dap.ab_test` | A/B Test | /Statistics | 1 |
| `dap.binner` | Binner | /Manipulation/Column | 1 |
| `dap.calendar` | Calendar | /Manipulation/Column/Date | 1 |
| `dap.chart` | Chart | /Visualization | 1 (pass-through) |
| `dap.chart_grid` | Chart Grid | /Visualization | 8 (pass-through) |
| `dap.cluster` | Cluster | /Statistics | 1 |
| `dap.column_filter` | Column Filter | /Manipulation/Column/Filter | 1 |
| `dap.column_renamer` | Column Renamer | /Manipulation/Column | 1 |
| `dap.column_resorter` | Column Resorter | /Manipulation/Column | 1 |
| `dap.concatenate` | Concatenate | /Manipulation/Row/Join | 2 |
| `dap.correlation` | Correlation | /Statistics | 1 |
| `dap.csv_reader` | CSV Reader | /IO/Read | — |
| `dap.csv_writer` | CSV Writer | /IO/Write | 1 (pass-through) |
| `dap.data_quality` | Data Quality | /Statistics | 2 |
| `dap.date_range` | Date Range | /Time Series | — |
| `dap.date_time` | Date & Time | /Manipulation/Column/Date | 1 |
| `dap.db_reader` | Database Reader | /IO/Read | — |
| `dap.db_writer` | Database Writer | /IO/Write | 1 (pass-through) |
| `dap.describe` | Describe | /Statistics | 1 |
| `dap.diff` | Diff | /Time Series | 1 |
| `dap.duplicate_filter` | Duplicate Filter | /Manipulation/Row | 1 |
| `dap.encoder` | Encoder | /Manipulation/Column | 1 |
| `dap.excel_reader` | Excel Reader | /IO/Read | — |
| `dap.excel_writer` | Excel Writer | /IO/Write | 1 (pass-through) |
| `dap.file_list` | File List | /IO/Read | — |
| `dap.file_reader` | File Reader | /IO/Read | — |
| `dap.fuzzy_join` | Fuzzy Join | /Manipulation/Row/Join | 2 |
| `dap.geo_distance` | Geo Distance | /Manipulation/Column/Geo | 1 |
| `dap.group_by` | Group By | /Manipulation/Row/GroupBy | 1 |
| `dap.histogram` | Histogram | /Statistics | 1 |
| `dap.hypothesis_test` | Hypothesis Test | /Statistics | 1 |
| `dap.joiner` | Joiner | /Manipulation/Row/Join | 2 |
| `dap.json_reader` | JSON Reader | /IO/Read | — |
| `dap.json_writer` | JSON Writer | /IO/Write | 1 (pass-through) |
| `dap.linear_regression` | Linear Regression | /Statistics | 1 |
| `dap.logistic_regression` | Logistic Regression | /Statistics | 1 |
| `dap.markdown_report` | Markdown Report | /IO/Write | 8 (pass-through) |
| `dap.math_formula` | Math Formula | /Manipulation/Column/Math | 1 |
| `dap.missing_value` | Missing Value | /Manipulation/Column/Missing | 1 |
| `dap.normalizer` | Normalizer | /Manipulation/Column | 1 |
| `dap.outlier_detect` | Outlier Detect | /Statistics | 1 |
| `dap.outlier_filter` | Outlier Filter | /Manipulation/Row/Filter | 1 |
| `dap.parquet_reader` | Parquet Reader | /IO/Read | — |
| `dap.parquet_writer` | Parquet Writer | /IO/Write | 1 (pass-through) |
| `dap.pca` | PCA | /Statistics | 1 |
| `dap.pivot` | Pivot | /Manipulation/Column | 1 |
| `dap.quantile` | Quantile | /Statistics | 1 |
| `dap.resample` | Resample | /Time Series | 1 |
| `dap.resample_test` | Resample Test | /Statistics | 1 |
| `dap.rolling` | Rolling | /Time Series | 1 |
| `dap.row_aggregator` | Row Aggregator | /Manipulation/Column | 1 |
| `dap.row_filter` | Row Filter | /Manipulation/Row/Filter | 1 |
| `dap.rule_engine` | Rule Engine | /Manipulation/Column | 1 |
| `dap.sample_rows` | Sample Rows | /Manipulation/Row | 1 |
| `dap.sorter` | Sorter | /Manipulation/Row/Sort | 1 |
| `dap.sql_query` | SQL Query | /Manipulation/Row | 8 |
| `dap.string_manipulation` | String Manipulation | /Manipulation/Column/String | 1 |
| `dap.survival` | Survival | /Statistics | 1 |
| `dap.text_normalize` | Text Normalize | /Manipulation/Column/String | 1 |
| `dap.time_series` | Time Series | /Statistics | 1 |
| `dap.top_n_per_group` | Top N per Group | /Manipulation/Row | 1 |
| `dap.type_cast` | Type Cast | /Manipulation/Column/Type | 1 |
| `dap.unpivot` | Unpivot | /Manipulation/Column | 1 |
| `dap.value_counts` | Value Counts | /Statistics | 1 |
| `dap.value_replace` | Value Replace | /Manipulation/Column | 1 |
| `dap.window` | Window | /Manipulation/Column | 1 |

## 2. dap.csv_reader

Читает один CSV-файл через DuckDB `read_csv(...)`. Схема выводится DuckDB из заголовка
или задаётся явно в `columns`. Строки не загружаются в heap и не материализуются в таблицу
сессии: узел возвращает ленивый план, файл сканируется DuckDB по запросу.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь проверяется через `PathGuard` |
| `delimiter` | string | `,` | ровно один символ; `"\t"` означает табуляцию |
| `header` | boolean | `true` | первая строка — имена колонок |
| `columns` | array<{name, type}> | — | явная схема; `type`: `Int64`, `Float64`, `Utf8`, `Boolean`, `Date32`, `Time64`, `Timestamp`, `Decimal128`; применяется как projection CAST над `read_csv` |
| `compression` | string | `auto` | `auto`/`none`/`gzip`; gzip определяется по magic-байтам и читается самим DuckDB; zstd не поддерживается |
| `emptyStringIsNull` | boolean | `false` | принимается для совместимости, но больше ничего не меняет (no-op) |

Семантика NULL — DuckDB: и некавыченное, и кавыченное пустое поле (`""`) становятся NULL.
Ключ `emptyStringIsNull` продолжает приниматься, но стал no-op: кавыченная пустая строка
всегда NULL, и ошибок «установи `emptyStringIsNull`» больше нет. Дубликаты имён в
заголовке переименовываются DuckDB (`a`, `a_1`, …), а не отклоняются. При `header: true`
каждая колонка явной схемы должна найтись в заголовке; при `header: false` схема
сопоставляется по позиции (`column0`, `column1`, …). Ошибки остаются внятными: файл вне
разрешённых корней или нечитаемый, zstd, несовпадение `compression` с содержимым файла,
неподдерживаемый тип колонки, отсутствующая в заголовке колонка — с именем колонки.

```json
{
  "factory": "dap.csv_reader",
  "settings": {
    "file": "/data/iris.csv",
    "delimiter": ",",
    "header": true,
    "columns": [
      {"name": "sepal_length", "type": "Float64"},
      {"name": "sepal_width", "type": "Float64"},
      {"name": "petal_length", "type": "Float64"},
      {"name": "petal_width", "type": "Float64"},
      {"name": "species", "type": "Utf8"}
    ],
    "emptyStringIsNull": false
  }
}
```

## 3. dap.excel_reader

Читает один лист `.xlsx` в таблицу. Apache POI не используется: книга разбирается
средствами JDK (`zip` + StAX); поддерживаются inline/shared strings, числа, boolean,
ISO-даты и даты по `numFmtId`; ошибки ячеек становятся NULL. `.xls`, шифрованные
книги и формулы без кэшированного значения не читаются.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь к `.xlsx` |
| `sheet` | string | первый лист | имя листа или индекс строкой: `"0"` |
| `header` | boolean | `true` | первая строка — имена колонок |
| `columns` | array<{name, type}> | — | `type`: `Int64`, `Float64`, `Utf8`, `Boolean`, `Date32`, `Timestamp` |
| `emptyStringIsNull` | boolean | `false` | пустые ячейки — NULL |

Пустые имена заголовка получают `columnN`, дубли отклоняются. Ячейки «только время»
(формат без даты) читаются как `Timestamp` на дате эпохи книги.

```json
{
  "factory": "dap.excel_reader",
  "settings": {
    "file": "/data/iris.xlsx",
    "sheet": "0",
    "header": true,
    "emptyStringIsNull": false
  }
}
```

## 4. dap.row_filter

Оставляет строки, удовлетворяющие условиям; условия объединяются `and` или `or`.
Пустой или отсутствующий `where` — pass-through.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `logic` | `and` \| `or` | `and` | способ объединения |
| `where` | array<{column, op, value}> | — | обязателен по спеке |
| `where[].column` | string | — | колонка входа |
| `where[].op` | `eq` \| `ne` \| `lt` \| `le` \| `gt` \| `ge` \| `is_null` \| `not_null` | — | для `is_null`/`not_null` значение не нужно |
| `where[].value` | любой JSON-скаляр | — | сравнивается по типу колонки |

Сравнения — SQL: `NULL` не проходит `eq`/`ne`/`lt`/…; для пропусков используйте
`is_null`/`not_null`.

```json
{
  "factory": "dap.row_filter",
  "settings": {
    "logic": "and",
    "where": [
      {"column": "species", "op": "eq", "value": "setosa"},
      {"column": "sepal_length", "op": "ge", "value": 5.0}
    ]
  }
}
```

## 5. dap.column_filter

Оставляет или удаляет колонки по именам, сохраняя порядок входа. Ровно один из
`include`/`exclude` должен быть задан; пустой результат — ошибка.

| Ключ | Тип | Примечание |
|---|---|---|
| `include` | array<string> | оставить только перечисленные |
| `exclude` | array<string> | удалить перечисленные |

```json
{"factory": "dap.column_filter", "settings": {"exclude": ["petal_width"]}}
```

## 6. dap.column_renamer

Переименовывает колонки проекцией, без копирования данных. Переименования применяются
одновременно и сохраняют порядок и типы; `from` и `to` не должны дублироваться, а
`to` не должен совпадать с непереименованной колонкой.

| Ключ | Тип | Примечание |
|---|---|---|
| `renames` | array<{from, to}> | обязателен; пустой массив — pass-through |
| `renames[].from` | string | существующая колонка |
| `renames[].to` | string | новое имя |

```json
{
  "factory": "dap.column_renamer",
  "settings": {
    "renames": [
      {"from": "sepal_length", "to": "sl"},
      {"from": "sepal_width", "to": "sw"}
    ]
  }
}
```

## 7. dap.sorter

Сортирует строки по нескольким ключам; ключи применяются по порядку. Пустой
`keys` — pass-through. NULL идут первыми при `asc` и последними при `desc`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `keys` | array<{column, direction}> | — | обязателен по спеке |
| `keys[].column` | string | — | колонка входа |
| `keys[].direction` | `asc` \| `desc` | `asc` | направление |
| `collation` | `natural` \| `binary` \| `nocase` | `natural` | сравнение строк |

`natural` сравнивает цифровые серии численно (`1st-4th` < `10th`), текст — побайтово,
исходная строка — финальный tie-breaker; `binary` — чисто байтовый порядок;
`nocase` — байтовый без учёта регистра.

```json
{
  "factory": "dap.sorter",
  "settings": {
    "keys": [
      {"column": "sepal_length", "direction": "desc"},
      {"column": "species", "direction": "asc"}
    ],
    "collation": "natural"
  }
}
```

## 8. dap.missing_value

Заменяет NULL в указанных колонках правилом за правилом; правила применяются
последовательно. Отсутствующий `rules` — pass-through.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `rules` | array<{column, method, value}> | — | обязателен по спеке |
| `rules[].column` | string | — | колонка входа |
| `rules[].method` | `constant` \| `mean` \| `mode` | `constant` | источник замены |
| `rules[].value` | любой JSON-скаляр | — | константа для `constant` |

`mean` работает только с `Float64` (тип колонки не меняется); `mode` берёт самое
частое значение, при равенстве частот — наименьшее.

```json
{
  "factory": "dap.missing_value",
  "settings": {
    "rules": [
      {"column": "petal_width", "method": "mean"},
      {"column": "species", "method": "constant", "value": "unknown"}
    ]
  }
}
```

## 9. dap.group_by

Группирует строки по ключам и считает агрегаты. Имена выходных колонок —
`Mean(x)`, `Sum(x)`, `Count(x)`, `Min(x)`, `Max(x)`.

| Ключ | Тип | Примечание |
|---|---|---|
| `keys` | array<string> | обязателен; минимум одна колонка |
| `aggregations` | array<{column, fn}> | обязателен |
| `aggregations[].column` | string | колонка входа |
| `aggregations[].fn` | `mean` \| `sum` \| `count` \| `min` \| `max` | — |

`mean` и `sum` требуют числовую колонку. `mean` возвращает `Float64`, `count` —
`Int64`, `sum`/`min`/`max` — тип входной колонки.

```json
{
  "factory": "dap.group_by",
  "settings": {
    "keys": ["species"],
    "aggregations": [
      {"column": "sepal_length", "fn": "mean"},
      {"column": "sepal_width", "fn": "count"}
    ]
  }
}
```

## 10. dap.joiner

Соединяет два входа по ключам: `inner`, `left`, `right`, `full`, `semi`, `anti`,
`asof` или `cross`. Ключи сопоставляются позиционно; `left_keys` и `right_keys`
должны быть одной длины. При `merge_keys` ключевые колонки сливаются в одну
(`COALESCE(left, right)`, чтобы right-only строка не теряла ключ). Колонка правого
входа, имя которой уже есть в выходе, получает суффикс `" (Right)"`.
`semi`/`anti` возвращают только колонки левого входа.
`asof` — `ASOF LEFT JOIN` по времени: каждой левой строке достаётся ближайшая
правая с `right_time` ≤ `left_time` (при `asof_inequality: ">="`) или ближайшая
следующая (`"<="`); необязательные `left_keys`/`right_keys` ограничивают
сопоставление равенством (например, по символу инструмента). Ключи `asof` не
сливаются. `cross` — `CROSS JOIN`: все пары строк, ключи не нужны и не читаются.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `how` | `inner` \| `left` \| `right` \| `full` \| `semi` \| `anti` \| `asof` \| `cross` | `inner` | режим соединения |
| `left_keys` | array<string> | — | обязателен для key-режимов, непустой |
| `right_keys` | array<string> | — | обязателен для key-режимов, та же длина |
| `merge_keys` | boolean | `true` | слить ключи в одну колонку |
| `left_time` | string | — | колонка времени левого входа для `asof` |
| `right_time` | string | — | колонка времени правого входа для `asof` |
| `asof_inequality` | `>=` \| `<=` | `>=` | ближайшая предыдущая или следующая строка |

Ключи сравниваются SQL-равенством: NULL-ключи не совпадают ни в одном режиме.

```json
{
  "factory": "dap.joiner",
  "settings": {
    "how": "left",
    "left_keys": ["species"],
    "right_keys": ["species"],
    "merge_keys": true
  }
}
```

## 11. dap.string_manipulation

Преобразует значения одной строковой колонки. В режиме `replace` колонка
перезаписывается, в режиме `append` результат добавляется последней колонкой
`new column` (если такая колонка уже есть — ошибка).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; только `Utf8` |
| `op` | `upper` \| `lower` \| `trim` \| `replace` \| `concat` | — | обязателен |
| `mode` | `replace` \| `append` | `replace` | куда писать результат |
| `search` | string | — | для `replace`: что искать |
| `value` | string | `""` | для `replace`: замена (пустая — удалить); для `concat`: суффикс |

`trim` удаляет ASCII-пробелы (пробел, `\t`, `\n`, `\v`, `\f`, `\r`).

```json
{"factory": "dap.string_manipulation", "settings": {"column": "species", "op": "upper", "mode": "append"}}
```

## 12. dap.math_formula

Добавляет колонки, вычисленные SQL-выражениями по входным колонкам. Имя выхода не
должно совпадать с входной колонкой или другим выходом. Тип выхода выводится
bind-only запросом `DESCRIBE ... LIMIT 0`.

| Ключ | Тип | Примечание |
|---|---|---|
| `outputs` | array<{name, expression}> | обязателен, непустой |
| `outputs[].name` | string | имя новой колонки |
| `outputs[].expression` | string | SQL-выражение по колонкам входа |

```json
{
  "factory": "dap.math_formula",
  "settings": {
    "outputs": [
      {"name": "petal_area", "expression": "petal_length * petal_width"},
      {"name": "sepal_total", "expression": "sepal_length + sepal_width"}
    ]
  }
}
```

## 13. dap.parquet_reader

Читает Parquet-файл в таблицу. Имена и типы колонок берутся из схемы Parquet:
исполнитель выполняет `DESCRIBE read_parquet(...)` и переводит типы DuckDB в
Arrow-имена. Строки читаются лениво, при `/table` или `/profile`.
Порты: входов нет; выход 0 — `Data` (`BufferedDataTable`).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; абсолютный путь к `.parquet` |

Отсутствующий или нечитаемый файл и тип колонки вне контракта Arrow — ошибка run.
`file` может быть путём, который вернул `POST /v1/datasets`.

```json
{
  "factory": "dap.parquet_reader",
  "settings": {"file": "/data/iris.parquet"}
}
```

## 14. dap.json_reader

Читает JSON-документ в таблицу через `read_json_auto`: поддерживаются массив
объектов верхнего уровня и newline-delimited JSON (NDJSON). Схему и типы колонок
выводит DuckDB по документу; строки читаются лениво.
Порты: входов нет; выход 0 — `Data` (`BufferedDataTable`).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; абсолютный путь к `.json`/`.ndjson` |

Отсутствующий или нечитаемый файл, битый JSON и тип колонки вне контракта Arrow —
ошибка run.

```json
{
  "factory": "dap.json_reader",
  "settings": {"file": "/data/events.json"}
}
```

## 15. dap.csv_writer

Пишет входную таблицу в delimited-файл и возвращает её же без изменений
(pass-through). Запись — `COPY ... TO` на job-соединении во время run;
родительские каталоги создаются, существующий файл перезаписывается.
Порты: вход 0 — `Data`, выход 0 — `Data` (та же таблица).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь к выходному `.csv` |
| `delimiter` | string | `,` | ровно один символ; `"\t"` означает табуляцию |
| `header` | boolean | `true` | писать строку с именами колонок |

`delimiter` проверяется: пустая строка, больше одного символа, кавычка, `\r`
и `\n` отклоняются. Ошибка пути или настроек видна на run.

```json
{
  "factory": "dap.csv_writer",
  "settings": {"file": "/data/out.csv", "delimiter": ";", "header": true}
}
```

## 16. dap.parquet_writer

Пишет входную таблицу в Parquet и возвращает её же (pass-through). Кодек задаётся
`compression`; неизвестное значение — ошибка run. Родительские каталоги
создаются, существующий файл перезаписывается.
Порты: вход 0 — `Data`, выход 0 — `Data` (та же таблица).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь к выходному `.parquet` |
| `compression` | `snappy` \| `zstd` \| `gzip` \| `uncompressed` | `zstd` | кодек Parquet |

```json
{
  "factory": "dap.parquet_writer",
  "settings": {"file": "/data/out.parquet", "compression": "zstd"}
}
```

## 17. dap.json_writer

Пишет входную таблицу как JSON и возвращает её же (pass-through). При
`array: true` получается один JSON-массив, при `false` — newline-delimited JSON
(по объекту на строку). Родительские каталоги создаются, существующий файл
перезаписывается.
Порты: вход 0 — `Data`, выход 0 — `Data` (та же таблица).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь к выходному `.json` |
| `array` | boolean | `true` | `true` — массив; `false` — NDJSON |

```json
{
  "factory": "dap.json_writer",
  "settings": {"file": "/data/out.json", "array": false}
}
```

## 18. dap.type_cast

Приводит выбранные колонки к другому Arrow-типу через SQL `CAST`. Имена и порядок
колонок сохраняются, неперечисленные колонки остаются как есть; схема выхода
показывает новые типы. Пустой `columns` — pass-through.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | Примечание |
|---|---|---|
| `columns` | array<{column, type}> | обязателен; список приводимых колонок |
| `columns[].column` | string | колонка входа |
| `columns[].type` | string | `Int64`, `Float64`, `Utf8`, `Boolean`, `Date32`, `Timestamp`, `Decimal128(precision,scale)` |

`Decimal128` пишется как `Decimal128(18,4)`: precision 1–38, scale 0–precision.
`Time64` не приводится. Повтор колонки, неизвестная колонка и неподдерживаемый тип —
ошибка run; несовместимое значение (например, `"abc"` → `Int64`) — ошибка DuckDB на run.

```json
{
  "factory": "dap.type_cast",
  "settings": {
    "columns": [
      {"column": "score", "type": "Int64"},
      {"column": "amount", "type": "Decimal128(18,4)"}
    ]
  }
}
```

## 19. dap.duplicate_filter

Удаляет дубликаты: оставляет одну строку на комбинацию ключей или только различные
строки, если `keys` нет. Порядок строк не определён, и при заданных ключах выжившая
строка тоже произвольна: без ключей — `SELECT DISTINCT *`, с ключами —
`QUALIFY ROW_NUMBER() OVER (PARTITION BY …) = 1`.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `keys` | array<string> | — | колонки ключа; пусто или нет — сравнение по всем колонкам (DISTINCT) |

```json
{"factory": "dap.duplicate_filter", "settings": {"keys": ["customer_id"]}}
```

Без `keys` — `{"factory": "dap.duplicate_filter", "settings": {}}`: остаются только
различные строки. Неизвестная или пустая колонка в `keys` — ошибка run.

## 20. dap.value_replace

Заменяет точные значения в выбранных колонках другим скаляром. Тип колонки
сохраняется, NULL можно и сопоставлять, и записывать; правила одной колонки
применяются по порядку, побеждает первое совпадение. Сравнение —
`IS NOT DISTINCT FROM`, поэтому NULL совпадает с NULL. Пустой `rules` — pass-through.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | Примечание |
|---|---|---|
| `rules` | array<{column, from, to}> | обязателен |
| `rules[].column` | string | колонка входа |
| `rules[].from` | string \| number \| boolean \| null | что заменить; `null` — SQL NULL |
| `rules[].to` | string \| number \| boolean \| null | замена; `null` — записать NULL |

`from` и `to` обязательны в каждом правиле: отсутствие ключа — ошибка, `null` —
значение. Объекты и массивы не принимаются; несовместимое значение — ошибка run.

```json
{
  "factory": "dap.value_replace",
  "settings": {
    "rules": [
      {"column": "category", "from": "A", "to": "Z"},
      {"column": "score", "from": null, "to": 0}
    ]
  }
}
```

## 21. dap.column_resorter

Меняет порядок колонок входа. Список должен быть перестановкой входных колонок:
каждая ровно один раз, без добавлений и потерь. Имена и типы сохраняются, данные не
копируются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | Примечание |
|---|---|---|
| `columns` | array<string> | обязателен; новый порядок всех колонок |

Дубль, неизвестная колонка и неполный список — ошибка run; сообщение о неполном
списке содержит ожидаемое и фактическое число колонок.

```json
{
  "factory": "dap.column_resorter",
  "settings": {"columns": ["id", "name", "score", "category"]}
}
```

## 22. dap.outlier_filter

Отбрасывает строки, чьи числовые значения выходят за границы выбросов: fences IQR
(`q1 − k·iqr`, `q3 + k·iqr`) или z-score (`mean ± k·stddev`). Несколько колонок
объединяются по AND, NULL всегда проходит, статистика считается по всему входу.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | обязателен; непустой, только числовые колонки |
| `method` | `iqr` \| `zscore` | — | обязателен |
| `threshold` | number | `1.5` для `iqr`, `3` для `zscore` | положительный множитель ширины |

Для `iqr` квартили считаются `quantile_cont`; для `zscore` — `avg` и `stddev`, при
нулевом stddev границы вырождаются в среднее. Нечисловая колонка, неизвестный `method`
и неположительный `threshold` — ошибка run.

```json
{
  "factory": "dap.outlier_filter",
  "settings": {"columns": ["score"], "method": "iqr", "threshold": 1.5}
}
```

## 23. dap.date_time

Разбирает текст в timestamp, форматирует timestamp в текст или извлекает часть даты.
Исходная колонка сохраняется, результат добавляется новой колонкой; имя `output` не
должно совпадать с существующей колонкой.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; для `parse` — `Utf8`, для `format`/`extract` — `Timestamp` или `Date32`; `Time64` — только для `extract` с `unit: hour` |
| `output` | string | — | обязателен; имя новой колонки |
| `op` | `parse` \| `format` \| `extract` | `parse` | операция |
| `format` | string | `%Y-%m-%d` | strptime/strftime; обязателен для `parse` и `format` |
| `unit` | `year` \| `month` \| `day` \| `hour` \| `dow` \| `quarter` \| `week` | `year` | часть даты для `extract` |

Типы выхода: `parse` → `Timestamp`, `format` → `Utf8`, `extract` → `Int64`. `dow` —
день недели (в DuckDB `dayofweek`). Неверный `op`/`unit`/`format`, неподходящий тип
колонки и занятое имя выхода — ошибка run.

```json
{
  "factory": "dap.date_time",
  "settings": {
    "column": "created",
    "output": "created_ts",
    "op": "parse",
    "format": "%Y-%m-%d %H:%M:%S"
  }
}
```

## 24. dap.rule_engine

Присваивает значение первого сработавшего булева правила, как `CASE WHEN`. Результат
добавляется новой колонкой; тип выхода выводится DuckDB из значений правил
(`DESCRIBE ... LIMIT 0`). Условия — SQL-выражения; каждое проверяется на BOOLEAN до
исполнения.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | Примечание |
|---|---|---|
| `output` | string | обязателен; имя новой колонки |
| `rules` | array<{condition, value}> | обязателен; непустой, порядок важен |
| `rules[].condition` | string | SQL boolean-выражение по колонкам входа |
| `rules[].value` | скаляр | значение при истинном условии |
| `elseValue` | скаляр | значение, если ни одно правило не сработало; нет — NULL |

`value` и `elseValue` — JSON-скаляры (строка, число, boolean, null); тип CASE выводится
из них. Небулево условие, битое выражение и занятое имя выхода — ошибка run.

```json
{
  "factory": "dap.rule_engine",
  "settings": {
    "output": "segment",
    "rules": [
      {"condition": "score >= 90", "value": "high"},
      {"condition": "score >= 60", "value": "mid"}
    ],
    "elseValue": "low"
  }
}
```

## 25. dap.binner

Раскладывает числовую колонку по целочисленным бинам: равная ширина, явные границы
или равночастотные buckets. Исходная колонка сохраняется, результат добавляется
колонкой `Int64`.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; числовая колонка |
| `output` | string | — | обязателен; имя новой колонки |
| `method` | `width` \| `custom` \| `frequency` | `width` | способ разбиения |
| `bins` | integer | `10` | положительное; для `width` и `frequency` |
| `edges` | array<number> | — | для `custom`: не менее двух строго возрастающих границ |

`width` считает границы по min/max входа, максимум попадает в последний бин, константа —
бин 0. `custom`: n+1 границ задают n полуинтервалов `[edge[i], edge[i+1])`; значения
ниже первой границы — бин 0, выше последней — последний бин. `frequency` — `NTILE(bins)`
по возрастанию значений. NULL остаётся NULL. Нечисловая колонка, неизвестный `method`,
неположительный `bins` и неотсортированные `edges` — ошибка run.

```json
{
  "factory": "dap.binner",
  "settings": {"column": "score", "output": "score_bin", "method": "width", "bins": 5}
}
```

## 26. dap.normalizer

Масштабирует числовые колонки в единичный диапазон (min-max) или к нулевому среднему и
единичному отклонению (z-score). Результат добавляется колонками `Float64`, исходные
колонки сохраняются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<{column, output}> | — | обязателен; непустой, только числовые колонки |
| `columns[].column` | string | — | числовая колонка входа |
| `columns[].output` | string | `<column>_norm` | имя новой колонки |
| `method` | `minmax` \| `zscore` | `minmax` | способ масштабирования |

`minmax`: `(x − min) / (max − min)`; `zscore`: `(x − avg) / stddev`. Нулевой размах или
stddev даёт NULL (`NULLIF`). Нечисловая колонка, дубль имени выхода и неизвестный
`method` — ошибка run.

```json
{
  "factory": "dap.normalizer",
  "settings": {
    "columns": [{"column": "score"}, {"column": "amount", "output": "amount_norm"}],
    "method": "minmax"
  }
}
```

## 27. dap.encoder

Кодирует категориальную колонку плотными целочисленными метками или one-hot колонками
0/1. Исходная колонка сохраняется.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; категориальная колонка |
| `output` | string | — | обязателен; имя колонки меток или префикс one-hot |
| `method` | `label` \| `onehot` | `label` | способ кодирования |
| `categories` | array<скаляр> | — | для `onehot`: непустой список категорий |

`label` — `DENSE_RANK()` по возрастанию значений, `Int64`. `onehot` добавляет по колонке
`<output>_<category>` на категорию (`Int64` 0/1); схема задаётся конфигом, а не данными,
поэтому категория, которой нет в данных, даёт колонку из нулей. Дубли категорий,
занятое имя и категория несовместимого типа — ошибка run.

```json
{
  "factory": "dap.encoder",
  "settings": {"column": "category", "output": "category_label", "method": "label"}
}
```

## 28. dap.sample_rows

Оставляет первые n строк или берёт воспроизводимую случайную выборку резервуаром, не
меняя схему. При `head` порядок строк сохраняется; `random` порядок не гарантирует.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `mode` | `head` \| `random` | `head` | способ выборки |
| `n` | integer | `100` | положительное число строк |
| `seed` | integer | — | seed для `random`; без него выборка случайна |

`head` — `LIMIT n`; `random` — `USING SAMPLE reservoir(n ROWS)` с `REPEATABLE (seed)`,
если seed задан. Неположительный `n` и неизвестный `mode` — ошибка run.

```json
{
  "factory": "dap.sample_rows",
  "settings": {"mode": "random", "n": 100, "seed": 42}
}
```

## 29. dap.row_aggregator

Добавляет построчные агрегаты по выбранным числовым колонкам: `sum` складывает колонки,
`mean` делит сумму на их число, `min`/`max` — `LEAST`/`GREATEST`, `count` считает
не-NULL. Входные колонки сохраняются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | Примечание |
|---|---|---|
| `columns` | array<string> | числовые колонки; пусто или нет — все числовые |
| `aggregations` | array<{fn, output}> | обязателен; непустой |
| `aggregations[].fn` | `sum` \| `mean` \| `min` \| `max` \| `count` | функция |
| `aggregations[].output` | string | имя новой колонки; по умолчанию имя `fn` |

NULL в любой выбранной колонке делает `sum`, `mean`, `min` и `max` NULL для этой
строки; `count` NULL игнорирует. Типы выхода: `count` — `Int64`, `mean` — `Float64`,
`sum`/`min`/`max` — `Int64`, если все выбранные колонки `Int64`, иначе `Float64`.
Нечисловая колонка, дубль имени выхода и пустой список агрегаций — ошибка run.

```json
{
  "factory": "dap.row_aggregator",
  "settings": {
    "columns": ["q1", "q2", "q3"],
    "aggregations": [
      {"fn": "sum", "output": "total"},
      {"fn": "mean"},
      {"fn": "count"}
    ]
  }
}
```

## 30. dap.pivot

Разворачивает строки в колонки: для каждого значения из `values` создаётся колонка с
условным агрегатом по `valueColumn`, группировка — по `index`. Список `values`
обязателен и фиксирует схему, поэтому значение, которого нет в данных, всё равно даёт
колонку (из NULL).
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | Примечание |
|---|---|---|
| `index` | array<string> | обязателен; непустой, колонки строки выхода |
| `pivotColumn` | string | обязателен; его значения становятся колонками |
| `valueColumn` | string | обязателен; агрегируемая колонка |
| `values` | array<string> | обязателен; непустой, порядок = порядок колонок выхода |
| `agg` | `sum` \| `mean` \| `count` \| `min` \| `max` | обязателен |

`sum`/`mean` требуют числовую `valueColumn`; `sum`/`min`/`max` возвращают тип
`valueColumn`, `mean` — `Float64`, `count` — `Int64`. Значения `values` сравниваются
с `pivotColumn` типизированным SQL-литералом (по типу колонки), а не как строки.
Дубль `index`/`values`, коллизия имени выхода с `index` и неизвестная агрегация —
ошибка run.

```json
{
  "factory": "dap.pivot",
  "settings": {
    "index": ["customer_id"],
    "pivotColumn": "month",
    "valueColumn": "amount",
    "values": ["2026-01", "2026-02"],
    "agg": "sum"
  }
}
```

## 31. dap.unpivot

Складывает выбранные колонки в пару «имя/значение» через DuckDB `UNPIVOT`. Остальные
колонки сохраняются; выбранные колонки должны иметь один тип. Строки, где выбранное
значение NULL, отбрасываются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | обязателен; непустой, колонки для stacking |
| `nameColumn` | string | `variable` | имя колонки с именами исходных колонок |
| `valueColumn` | string | `value` | имя колонки со значениями |

Порядок выхода: сохранённые колонки, затем `nameColumn` (`Utf8`), затем `valueColumn`
(тип выбранных колонок). Совпадение `nameColumn`/`valueColumn` между собой или с
сохраняемой колонкой, разные типы выбранных колонок и неизвестная колонка — ошибка run.

```json
{
  "factory": "dap.unpivot",
  "settings": {"columns": ["q1", "q2"], "nameColumn": "quarter", "valueColumn": "amount"}
}
```

## 32. dap.window

Добавляет колонки SQL window-функций. Все функции используют один `OVER`, собранный из
`partitionBy` и `orderBy`; `rank`, `dense_rank`, `lag` и `lead` требуют `orderBy`;
`row_number`, `sum` и `mean` работают и по всему разделу. При заданном `orderBy` `sum` и
`mean` — running-агрегаты (`UNBOUNDED PRECEDING` → текущая строка). Входные колонки
сохраняются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `partitionBy` | array<string> | — | колонки разделов; нет — вся таблица один раздел |
| `orderBy` | array<{column, direction}> | — | порядок внутри раздела, общий для всех функций |
| `orderBy[].direction` | `asc` \| `desc` | `asc` | `asc` — NULLS FIRST, `desc` — NULLS LAST |
| `functions` | array<{fn, column, offset, output}> | — | обязателен; непустой |
| `functions[].fn` | `row_number` \| `rank` \| `dense_rank` \| `lag` \| `lead` \| `sum` \| `mean` | — | функция |
| `functions[].column` | string | — | для `lag`, `lead`, `sum`, `mean` |
| `functions[].offset` | integer | `1` | сдвиг для `lag`/`lead`, ≥ 0 |
| `functions[].output` | string | — | обязателен; имя новой колонки |

Типы: `row_number`/`rank`/`dense_rank` — `Int64`, `sum` и `lag`/`lead` — тип колонки,
`mean` — `Float64`. Отсутствие `orderBy` у функций, которым он нужен, нечисловая колонка
для `sum`/`mean`, дубль имени выхода и неизвестная функция — ошибка run.

```json
{
  "factory": "dap.window",
  "settings": {
    "partitionBy": ["customer_id"],
    "orderBy": [{"column": "month", "direction": "asc"}],
    "functions": [
      {"fn": "row_number", "output": "rn"},
      {"fn": "sum", "column": "amount", "output": "running_amount"}
    ]
  }
}
```

## 33. dap.top_n_per_group

Оставляет первые n строк каждой группы, упорядоченные по `orderBy`. Реализация —
`QUALIFY ROW_NUMBER() OVER (PARTITION BY groupBy ORDER BY …) <= n`; при равных ключах
сортировки номера строк произвольны. Входные колонки сохраняются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `groupBy` | array<string> | — | обязателен; непустой |
| `orderBy` | array<{column, direction}> | — | обязателен; непустой |
| `orderBy[].direction` | `asc` \| `desc` | `asc` | `asc` — NULLS FIRST, `desc` — NULLS LAST |
| `n` | integer | — | обязателен; больше нуля |

Пустые `groupBy`/`orderBy`, неизвестная колонка и `n ≤ 0` — ошибка run.

```json
{
  "factory": "dap.top_n_per_group",
  "settings": {
    "groupBy": ["customer_id"],
    "orderBy": [{"column": "amount", "direction": "desc"}],
    "n": 3
  }
}
```

## 34. dap.concatenate

Складывает строки двух таблиц. В режиме `name` таблицы сопоставляются по именам колонок
(`UNION ALL BY NAME`), поэтому колонка, которой нет на одной стороне, заполняется NULL;
в режиме `position` — по позициям, и число колонок должно совпадать. Оба входа
обязательны.
Порты: вход 0 — `Top`, вход 1 — `Bottom`; выход 0 — `Data`.

| Ключ | Тип | Примечание |
|---|---|---|
| `mode` | `name` \| `position` | обязателен; способ сопоставления колонок |

Схема выхода выводится `DESCRIBE`; в режиме `position` имена берутся у верхнего входа.
Разное число колонок в `position`, неподключённый вход, неизвестный `mode` и пустая
схема — ошибка run.

```json
{"factory": "dap.concatenate", "settings": {"mode": "name"}}
```

## 35. dap.describe

Описывает каждую колонку входа одной строкой: логический тип, число не-NULL значений
(`count`), число NULL (`missing`), число различных значений (`distinct`), а для числовых
колонок — `min`, `max`, `mean` и выборочное стандартное отклонение `stddev`.
Нечисловые колонки оставляют числовые статистики пустыми (NULL). Реализация — `UNION ALL`
по колонкам, порядок строк задан порядком `columns`.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | колонки в порядке выхода; пусто или нет — все колонки входа |

Схема выхода: `column`, `type` (`String`), `count`, `missing`, `distinct`
(`Number (Integer)`), `min`, `max`, `mean`, `stddev` (`Number (Float)`).
`type` — имя контракта (`Number (Integer)`, `Number (Float)`, `String`, `Boolean`,
`Date`, `Time`, `Date&Time (Timestamp)`, `Number (Decimal)`). `stddev` — выборочное
(`STDDEV_SAMP`), поэтому на одной строке оно NULL. Дубль имени, пустое имя и неизвестная
колонка — ошибка run.

```json
{
  "factory": "dap.describe",
  "settings": {"columns": ["sepal_length", "species"]}
}
```

## 36. dap.value_counts

Считает частоты значений одной колонки: одна строка на различное значение, по убыванию
`count`, при равенстве — по возрастанию значения. NULL исключены, поэтому сумма
`count` равна числу не-NULL строк. Значения приводятся к строке, поэтому `value` всегда
`String` независимо от типа колонки.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; колонка, частоты которой считаются |
| `limit` | integer | `20` | максимум строк; больше нуля |

Схема выхода: `value` (`String`), `count` (`Number (Integer)`). `limit` обрезает
хвост уже отсортированного результата, поэтому при обрезке возвращаются самые частые
значения. Неизвестная колонка, нецелый или неположительный `limit` — ошибка run.

```json
{
  "factory": "dap.value_counts",
  "settings": {"column": "category", "limit": 10}
}
```

## 37. dap.correlation

Считает парные корреляции двух и более числовых колонок в длинном формате (одна строка
на неупорядоченную пару `i < j` в порядке `columns`) или матрицей. `pearson` —
`CORR` по сырым значениям; `spearman` — `CORR` по `RANK()` каждой колонки, причём
равные значения делят ранг; `kendall` — tau-b с поправкой на связи (Fenwick-дерево
по рангам, `O(n log n)`). Строки с NULL хотя бы в одной колонке пары исключены; при
`partial` статистика считается по полным строкам всех участвующих колонок.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | обязателен; не менее двух различных числовых колонок |
| `method` | `pearson` \| `spearman` \| `kendall` | `pearson` | `pearson` — сырые значения, `spearman` — ранги, `kendall` — tau-b |
| `partial` | array<string> | — | необязательные числовые контрольные колонки; не должны пересекаться с `columns` |
| `output` | `long` \| `matrix` | `long` | `matrix` — строка на переменную, колонка на переменную |
| `alpha` | number | `0.05` | уровень значимости для интервала: `1 − alpha`; в `(0, 1)` |
| `correction` | `none` \| `bonferroni` \| `holm` \| `bh` | `none` | поправка на множественные сравнения для `p_adjusted` по всем парам; в `matrix` не применяется |

Схема выхода `long`: `column_a`, `column_b`, `method`, `partial` (`String`),
`n` (`Number (Integer)`), `correlation`, `p_value`, `ci_low`, `ci_high`, `p_adjusted`
(`Number (Float)`). `partial` — контрольные колонки через запятую (NULL, если
частичной корреляции нет). `p_adjusted` — `p_value` с поправкой на число пар; при
`correction = none` равен `p_value`, NULL остаётся NULL. Число строк — `n·(n−1)/2`.

Схема выхода `matrix`: `column` (`String`) и по одной `Number (Float)` колонке на
каждую переменную `columns`, имя колонки — имя переменной; строк столько же, сколько
переменных, диагональ 1, матрица симметрична. При `partial` матрица содержит частичные
корреляции (обратная корреляционная матрица пар с контролем).

Правила:

- p-value: для Pearson/Spearman `t = r·sqrt(df/(1−r²))`, `df = n−2−k` (`k` —
  число контрольных колонок), двусторонний хвост t; для Kendall асимптотический
  `z = τ/se = C/sqrt(var)`, где `C` — разность конкордантных и дискордантных пар,
  `m = n(n−1)`, `var = (m(2n+5)−x1−y1)/18 + 2·xtie·ytie/m + x0·y0/(9m(n−2))`;
  суммы по связям `xtie = Σt(t−1)/2`, `x0 = Σt(t−1)(t−2)`, `x1 = Σt(t−1)(2t+5)`
  (то же для y) — как в scipy `kendalltau(method='asymptotic')`. `p = 2(1−Φ(|z|))`,
  двусторонний нормальный хвост. Эталон: `tests/reference/kendall_pvalue.py`
  (scipy, запуск через `build/refvenv/bin/python`).
- Поправка: `p_adjusted` считается общим с `dap.hypothesis_test` хелпером по всем
  валидным p-value выхода: `bonferroni` — `min(1, p·m)`, `holm` — step-down с
  накоплением максимума, `bh` — Benjamini–Hochberg step-up (`fdr_bh`). Порядок строк
  сохраняется, NULL-значения не участвуют и остаются NULL. Эталон —
  `tests/reference/correction.py` (statsmodels `multipletests`, 1e-9 в JUnit).
  В матричном формате p-value нет, поэтому `correction` на выход не влияет.
- Интервал: Fisher `atanh` с `se = 1/sqrt(n−3−k)` для Pearson/Spearman; для Kendall
  `se = sqrt(var/((tot−xtie)(tot−ytie)))`, `tot = n(n−1)/2`; значения обрезаны
  в `[−1, 1]`.
- Ограничение: DAP всегда считает нормальную асимптотику; точный пермутационный
  p-value для малых n (и данных со связями) не вычисляется, даже если scipy
  `method='auto'` выбрал бы exact.
- `n < 3` (для Pearson/Spearman `df ≤ 0` или `n−3−k ≤ 0`) оставляет `p_value`
  и/или `ci_low`/`ci_high` NULL; `|r| = 1` даёт `p_value = 0` и вырожденный
  интервал.
- Меньше двух колонок, дубль, неизвестная или нечисловая колонка, пересечение
  `columns` и `partial`, неизвестный `method`/`output`/`correction`, `alpha` вне
  `(0, 1)` — ошибка run.
- Kendall читает значения в память: не более 100 000 строк, иначе ошибка с подсказкой
  использовать `spearman`.
- На вырожденной паре (нулевой разброс) корреляция NULL; если корреляционная матрица
  вырождена, частичная корреляция NULL.

```json
{
  "factory": "dap.correlation",
  "settings": {
    "columns": ["sepal_length", "sepal_width", "petal_length"],
    "method": "spearman",
    "partial": ["petal_width"],
    "output": "long",
    "alpha": 0.05,
    "correction": "bh"
  }
}
```

## 38. dap.histogram

Строит равномерную гистограмму одной числовой колонки между её минимумом и максимумом.
Выдаются все бины, включая пустые (`count: 0`); константная колонка попадает целиком
в первый бин. Бины считаются по не-NULL значениям, NULL в счёт не входят.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; числовая колонка |
| `bins` | integer | `10` | по умолчанию 10; больше нуля |

Схема выхода: `bin_start`, `bin_end` (`Number (Float)`), `count` (`Number (Integer)`).
Бин — полуинтервал `[bin_start, bin_end)`, кроме последнего: он включает максимум.
Ширина — `(max − min) / bins`, границы — `Float64`. Пустой или целиком NULL вход даёт
ноль строк. Нечисловая или неизвестная колонка, `bins ≤ 0` — ошибка run.

```json
{
  "factory": "dap.histogram",
  "settings": {"column": "sepal_length", "bins": 20}
}
```

## 39. dap.quantile

Считает непрерывные квантили одной числовой колонки: одна строка на запрошенную
вероятность, в порядке массива. `QUANTILE_CONT` интерполирует между соседними
значениями, NULL игнорируются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; числовая колонка |
| `quantiles` | array<number> | — | обязателен; непустой; каждая вероятность в `[0, 1]` |

Схема выхода: `quantile`, `value` (`Number (Float)`). Вероятности проверяются
включительно: `0` и `1` допустимы, `1.01` — ошибка run. Повтор вероятности даёт
повтор строки; пустой массив, нечисловое значение, нечисловая или неизвестная колонка —
ошибка run.

```json
{
  "factory": "dap.quantile",
  "settings": {"column": "sepal_length", "quantiles": [0.25, 0.5, 0.75]}
}
```

## 40. dap.outlier_detect

Помечает строки, у которых числовое значение выходит за границы выброса: `iqr` —
`q1 − k·iqr` и `q3 + k·iqr`, `zscore` — `mean ± k·stddev`. Добавляется Boolean-колонка
`outlier`; строка помечается, если хотя бы одна из перечисленных колонок вне своей
границы. Статистика берётся по всему входу, NULL не бывает выбросом. Исходные колонки
сохраняются.

Методы `dbscan`, `lof`, `isolation_forest` считают модель в пространстве выбранных
колонок: признаки стандартизуются (z-score по выборке, `n − 1`), строки с NULL или
нефинитным значением из модели исключаются, а к входу добавляются `outlier` и Float64
`score`. `dbscan` — классический DBSCAN: выброс = шумовая метка `-1`, score = 0 для
core-точки и расстояние до k-го ближайшего соседа иначе. `lof` — локальный фактор
выброса, выброс при `score >` квантиля `1 − contamination`. `isolation_forest` —
100 деревьев по `min(256, n)` точкам, score — нормированная аномальность в `[0, 1]`,
выбросы — верхняя доля `contamination`.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | обязателен; непустой; только числовые колонки |
| `method` | `iqr` \| `zscore` \| `dbscan` \| `lof` \| `isolation_forest` | — | обязателен; способ поиска выбросов |
| `threshold` | number | `1.5` для `iqr`, `3` для `zscore` | положительный множитель ширины границы; только `iqr`/`zscore` |
| `eps` | number | `0.5` | положительный радиус соседства DBSCAN в стандартизованном пространстве |
| `min_points` | integer | `5` | положительный минимум соседей (с самой точкой) для core-точки |
| `k` | integer | `5` | положительное число соседей для score DBSCAN и плотности LOF; зажимается до `n − 1` |
| `contamination` | number | `0.05` | доля выбросов в `[0, 0.5]`; порог — квантиль `1 − contamination` |
| `seed` | integer | `42` | seed выборки isolation forest; одинаковый seed и вход дают одинаковые score |
| `max_rows` | integer | `50000` | положительный предел in-memory; больше — ошибка с подсказкой |

`iqr`/`zscore`: `outlier` добавляется последней колонкой; если такое имя уже занято —
ошибка run. NULL в проверяемой колонке не помечает строку (`IS NOT NULL` в условии).
При нулевом `stddev` границы `zscore` вырождаются в среднее, и строки со значением,
отличным от среднего, помечаются. Дубль колонки игнорируется, пустой список, нечисловая
или неизвестная колонка, `threshold ≤ 0` — ошибка run.

`dbscan`/`lof`/`isolation_forest`: добавляются `outlier` и `score`; занятое имя любой из
этих колонок — ошибка run. Строки с NULL или нефинитным значением в выбранной колонке в
модель не входят и получают `outlier = false`, `score = NULL`. Строки читаются в
лексикографическом порядке вектора признаков, поэтому результат не зависит от порядка
сканирования входа. `contamination = 0` отключает флаги LOF и isolation forest (порог
равен максимуму, сравнение строгое). Превышение `max_rows` — ошибка с подсказкой поднять
`max_rows` или отфильтровать вход.

```json
{
  "factory": "dap.outlier_detect",
  "settings": {"columns": ["sepal_length", "petal_length"], "method": "iqr", "threshold": 1.5}
}
```

```json
{
  "factory": "dap.outlier_detect",
  "settings": {"columns": ["x", "y"], "method": "dbscan", "eps": 0.5, "min_points": 3, "k": 5}
}
```

## 41. dap.resample

Раскладывает Timestamp-колонку по фиксированным интервалам через DuckDB `time_bucket`
и агрегирует каждый бакет. Старт бакета заменяет колонку времени (имя сохраняется),
результат упорядочен по ней; каждая агрегация может задать имя выходной колонки.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `timeColumn` | string | — | обязателен; колонка типа `Timestamp` |
| `every` | string | `1 day` | по умолчанию `1 day`; ширина бакета вида `1 day` или `15 minutes` |
| `aggregations` | array<{column, fn, output}> | — | обязателен; непустой, порядок = порядок колонок |
| `aggregations[].column` | string | — | агрегируемая колонка входа |
| `aggregations[].fn` | `mean` \| `sum` \| `count` \| `min` \| `max` | — | `mean` и `sum` требуют числовую колонку |
| `aggregations[].output` | string | `<Fn>(<column>)`, например `Mean(value)` | имя новой колонки |

`every` — положительное число и единица: `second`, `minute`, `hour`, `day`, `week`,
`month`, `year` (с `s` или без). Типы выхода: `mean` — `Number (Float)`, `count` —
`Number (Integer)`, `sum`/`min`/`max` — тип агрегируемой колонки. Пустые бакеты не
появляются — только бакеты, в которых есть строки. Не-Timestamp `timeColumn`,
интервал не вида `1 day`, неизвестная `fn`, нечисловая колонка для `mean`/`sum`,
дубль имени выхода и пустой список агрегаций — ошибка run.

```json
{
  "factory": "dap.resample",
  "settings": {
    "timeColumn": "ts",
    "every": "1 hour",
    "aggregations": [
      {"column": "value", "fn": "mean", "output": "value_mean"},
      {"column": "value", "fn": "count"}
    ]
  }
}
```

## 42. dap.rolling

Добавляет колонку агрегата по скользящему окну: `fn(column)` по текущей строке и
`window − 1` предыдущим строкам того же раздела в порядке `orderBy`. Окно задаётся
кадрами строк (`ROWS BETWEEN n−1 PRECEDING AND CURRENT ROW`), а не диапазоном времени,
поэтому первые строки раздела считают по неполному окну. Исходные колонки сохраняются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; колонка, по которой считается окно |
| `output` | string | — | обязателен; имя новой колонки |
| `window` | integer | — | обязателен; число строк окна, включая текущую; больше нуля |
| `fn` | `mean` \| `sum` \| `min` \| `max` | — | обязателен; `mean` и `sum` требуют числовую колонку |
| `orderBy` | array<{column, direction}> | — | обязателен; непустой; порядок внутри раздела |
| `orderBy[].direction` | `asc` \| `desc` | `asc` | `asc` — NULLS FIRST, `desc` — NULLS LAST |
| `partitionBy` | array<string> | — | колонки разделов; нет — вся таблица один раздел |

Типы выхода: `mean` — `Number (Float)`, `sum`/`min`/`max` — тип колонки. Занятое имя
выхода, `window ≤ 0`, неизвестная `fn`, нечисловая колонка для `mean`/`sum`, пустой
`orderBy`, дубль или неизвестная колонка в `partitionBy`/`orderBy` — ошибка run.

```json
{
  "factory": "dap.rolling",
  "settings": {
    "column": "amount",
    "output": "amount_ma7",
    "window": 7,
    "fn": "mean",
    "partitionBy": ["customer_id"],
    "orderBy": [{"column": "month", "direction": "asc"}]
  }
}
```

## 43. dap.diff

Добавляет колонку разности текущей и предыдущей строки того же раздела:
`column − LAG(column)` в порядке `orderBy`. Первая строка каждого раздела — NULL,
тип выходной колонки совпадает с типом входной. Исходные колонки сохраняются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; числовая колонка |
| `output` | string | — | обязателен; имя новой колонки |
| `orderBy` | array<{column, direction}> | — | обязателен; непустой; порядок внутри раздела |
| `orderBy[].direction` | `asc` \| `desc` | `asc` | `asc` — NULLS FIRST, `desc` — NULLS LAST |
| `partitionBy` | array<string> | — | колонки разделов; нет — вся таблица один раздел |

NULL в предыдущей строке даёт NULL в разности; NULL в текущей — тоже NULL. Занятое имя
выхода, нечисловая или неизвестная колонка, пустой `orderBy`, дубль или неизвестная
колонка в `partitionBy` — ошибка run.

```json
{
  "factory": "dap.diff",
  "settings": {
    "column": "amount",
    "output": "amount_delta",
    "partitionBy": ["customer_id"],
    "orderBy": [{"column": "month", "direction": "asc"}]
  }
}
```

## 44. dap.date_range

Источник: генерирует по одной Timestamp-строке на шаг между двумя включительными
границами. Границы принимают ISO-дату или timestamp, шаг — положительный интервал вида
`1 day` или `15 minutes`, единственная выходная колонка называется `date`.
Порты: входов нет; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `start` | string | — | обязателен; первая метка, ISO-дата или timestamp, включительно |
| `end` | string | — | обязателен; последняя метка, включительно, не раньше `start` |
| `step` | string | `1 day` | по умолчанию `1 day`; расстояние между строками |

Схема выхода: одна колонка `date` (`Date&Time (Timestamp)`). Дата без времени
трактуется как полночь. Обе границы включаются, если попадают на шаг: `2026-01-01 …
2026-01-03` с шагом `1 day` даёт три строки. `end` раньше `start`, не-ISO значение,
интервал не вида `1 day` и нулевой шаг — ошибка run.

```json
{
  "factory": "dap.date_range",
  "settings": {"start": "2026-01-01", "end": "2026-01-31", "step": "1 day"}
}
```

## 45. dap.excel_writer

Пишет входную таблицу в книгу `.xlsx` и возвращает её же без изменений (pass-through).
Apache POI не используется: книга собирается средствами JDK (`zip` + XML) из пяти
частей — `[Content_Types].xml`, `_rels/.rels`, `xl/workbook.xml`,
`xl/_rels/workbook.xml.rels`, `xl/worksheets/sheet1.xml`. Первая строка — имена колонок;
числа остаются числами, boolean — boolean-ячейками, даты и время пишутся как
`inlineStr` в ISO-виде (Excel покажет их текстом), NULL — пустая ячейка.
Порты: вход 0 — `Data`, выход 0 — `Data` (та же таблица).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; абсолютный путь к `.xlsx`; родительские каталоги создаются |
| `sheet` | string | `Sheet1` | имя единственного листа; символы `[]:*?/\` запрещены |

Запись атомарная: книга собирается во временный файл рядом с целевым и переименовывается
(`ATOMIC_MOVE`), поэтому сбой не оставляет обрезанный файл под путём пользователя;
существующий файл перезаписывается. Числа пишутся в обычной записи (не `1.0E10`), а
нечисловое значение числовой колонки и невалидный boolean — ошибка run. Книга
собирается в памяти целиком: таблица материализуется на run.

```json
{
  "factory": "dap.excel_writer",
  "settings": {"file": "/data/report.xlsx", "sheet": "Report"}
}
```

## 46. dap.markdown_report

Пишет детерминированный отчёт в формате `md` (по умолчанию), `html`, `pdf` или
`pptx` и возвращает таблицу входа порта 0 без изменений (pass-through). У узла восемь
входных портов; каждый раздел выбирает таблицу через `sections[].input` (0…7, по
умолчанию 0). Разделы рендерятся в порядке конфига: `table` — строки выбранного входа,
`profile` — статистика `dap.describe` (по колонкам), `text` — литеральный абзац,
`metrics` — счётчики строк/колонок и агрегаты по выбранным колонкам, `image` —
растровый файл, `page_break` — явный разрыв страницы. Файл пишется на run атомарно
(временный файл рядом с целевым + переименование), родительские каталоги создаются;
вывод не содержит времени и прочих недетерминированных данных.
Порты: входы 0–7 — `Data 1`…`Data 8`; выход 0 — `Data` (таблица входа порта 0).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь к выходному файлу |
| `format` | `md` \| `html` \| `pdf` \| `pptx` | `md` | формат отчёта |
| `title` | string | — | заголовок документа |
| `subtitle` | string | — | подзаголовок (html, pdf) |
| `author` | string | — | автор (html, pdf) |
| `toc` | boolean | `false` | оглавление по заголовкам разделов (html) |
| `theme` | `default` \| `light` \| `dark` \| `mono` | `default` | палитра; в pdf/pptx используется акцентный цвет |
| `page_size` | `a4` \| `letter` | — | бумага для html/pdf; по умолчанию A4 (html/pdf) или 16:9 (pptx) |
| `orientation` | `portrait` \| `landscape` | — | ориентация; по умолчанию portrait, для pptx — landscape |
| `sections` | array | — | обязателен, непустой; порядок = порядок вывода |

Раздел (`sections[]`):

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `kind` | `table` \| `profile` \| `text` \| `metrics` \| `image` \| `page_break` | — | обязателен |
| `title` | string | — | заголовок раздела (`##` в md) |
| `input` | integer | 0 | вход 0…7 для `table`/`profile`/`metrics`; порт должен быть подключён |
| `columns` | string[] | — | для `table`/`profile` — какие колонки показывать (пусто = все); для `metrics` — какие колонки суммировать (пусто = только `rows`/`columns`) |
| `limit` | integer | 100 (table), 1000 (profile) | 1…10000; для `table` — строк, для `profile` — строк статистики |
| `text` | string | — | обязателен для `kind: text` |
| `path` | string | — | обязателен для `kind: image`; PNG, JPEG или GIF |
| `alt` | string | — | альтернативный текст (md, html) |
| `width` | integer | — | ширина изображения в пикселях, 1…10000; в pdf/pptx масштабируется под страницу |

Семантика:

- `table` — шапка из имён колонок и до `limit` строк; NULL рендерится пустой
  ячейкой, `|` → `\|`, переводы строк → `<br>`, `&`/`<`/`>` → HTML-сущности.
- `profile` — одна строка на колонку с колонками `dap.describe`
  (`column`, `type`, `count`, `missing`, `distinct`, `min`, `max`, `mean`, `stddev`).
- `metrics` — таблица `metric`/`value`: всегда `rows` и `columns`, затем для каждой
  выбранной колонки `<col>.count`, `<col>.missing` и для числовых —
  `<col>.mean`, `<col>.min`, `<col>.max`.
- `image` — файл читается один раз при разборе конфига; отсутствующий файл — ошибка run.
- `page_break` — новая страница в pdf/html, `---` в md, в pptx пропускается.
- Один preview на вход (по максимальному `limit` среди его `table`-разделов), поэтому
  каждый вход читается один раз; ошибка конфига или пути видна на run (500).

Форматы:

- `md` — прежний Markdown: изображение рендерится как `![alt](path)`, разрыв — `---`;
  побайтово совместим с одно-входовым отчётом прежних версий.
- `html` — один самодостаточный файл: CSS внутри, таблицы, изображения как data URI,
  без внешних ссылок; `toc: true` добавляет оглавление со ссылками на разделы.
- `pdf` — минимальный writer без библиотек: A4/Letter, portrait/landscape, раскладка
  через Java2D (заголовки, текст с переносами, таблицы с повтором шапки на новой
  странице, изображения); PNG/JPEG декодируются в RGB и встраиваются Flate-потоком;
  в файле нет дат и id, поэтому вывод побайтово детерминирован. Символы вне WinAnsi
  (например кириллица) заменяются на `?`: шрифт не встраивается.
- `pptx` — минимальный OOXML-пакет: `[Content_Types].xml`, rels, `ppt/presentation.xml`,
  master/layout/theme и по одному слайду на раздел (`page_break` не создаёт слайд);
  слайд — заголовок плюс текст, таблица (до 12 строк и строка «… N more rows») или
  картинка в `ppt/media`. Zip детерминирован: фиксированные даты и порядок частей.

```json
{
  "factory": "dap.markdown_report",
  "settings": {
    "file": "/data/report.pdf",
    "format": "pdf",
    "title": "Sales report",
    "page_size": "a4",
    "orientation": "landscape",
    "sections": [
      {"kind": "table", "title": "Rows", "input": 0, "columns": ["id", "amount"], "limit": 50},
      {"kind": "metrics", "title": "Totals", "input": 0, "columns": ["amount"]},
      {"kind": "image", "title": "Chart", "input": 1, "path": "/data/chart.png", "width": 480},
      {"kind": "page_break"},
      {"kind": "text", "title": "Note", "text": "Generated by DAP."}
    ]
  }
}
```

## 47. dap.chart

Рисует график по входной таблице и возвращает её без изменений (pass-through).
`format: svg` — ручной XML: координаты округляются до двух знаков, в файле нет
времени и случайности, поэтому один и тот же вход даёт побайтово одинаковый
результат (golden). `format: png` — Java2D + `ImageIO` в headless-режиме
(`-Djava.awt.headless=true`); PNG побайтово не детерминирован и проверяется только
smoke-тестом (файл существует, размеры совпадают). Файл пишется атомарно,
родительские каталоги создаются.
Порты: вход 0 — `Data`, выход 0 — `Data` (та же таблица).

Типы: `bar`, `line`, `scatter`, `histogram`, `pie`, `boxplot`, `heatmap`, `map`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь к `.svg` или `.png` |
| `format` | `svg` \| `png` | `svg` | SVG детерминирован |
| `type` | `bar` \| `line` \| `scatter` \| `histogram` \| `pie` \| `boxplot` \| `heatmap` \| `map` | — | обязателен |
| `x` | string | — | категория для `bar`/`pie`/`boxplot`/`heatmap`; числовая колонка для `line`/`scatter`/`histogram`; у `map` не используется |
| `y` | string[] | — | `bar`/`pie`/`boxplot`/`heatmap` — ровно одна; `line`/`scatter` — одна или больше; `histogram`/`map` — не задаётся |
| `series` | string | — | колонка-серия: группы баров, цвет линий/точек или строка `heatmap`; с ней `y` — ровно одна колонка |
| `title` | string | — | заголовок; рисуется на канве и дублируется в `<title>` |
| `subtitle` | string | — | строка под заголовком |
| `x_label` / `y_label` | string | — | подписи осей; `y_label` поворачивается на −90° |
| `x_unit` / `y_unit` | string | — | единицы: подпись оси становится `label (unit)` |
| `sort` | `value` \| `label` \| `none` | зависит от типа | порядок категорий; для `bar`/`pie`/`boxplot` по умолчанию `value` |
| `sort_direction` | `asc` \| `desc` | `desc` для `value`, иначе `asc` | направление сортировки |
| `log_x` / `log_y` | boolean | `false` | логарифмические оси (только положительные значения); `log_x` — для `line`/`scatter`, `log_y` — для `bar`/`line`/`scatter`/`boxplot` |
| `grid` | boolean | `true` | горизонтальная сетка по делениям y |
| `data_labels` | boolean | `false` | значения у баров, точек, секторов и ячеек |
| `annotations` | array | `[]` | `{text, x?, y?, color?}`: `x`/`y` — координаты данных, `x` может быть именем категории; без координат — заметки в левом верхнем углу |
| `facet` | string | — | колонка фасета: малые кратные в одном файле, у каждой панели свои оси |
| `facet_columns` | integer | 2 | панелей в строке (1…12) |
| `lat` / `lng` | string | — | только `map`: широта `[-90, 90]` и долгота `[-180, 180]` |
| `size` / `color` / `label` | string | — | только `map`: размер и цвет пузыря, подпись точки |
| `width` / `height` | integer | 640 / 400 | 100…4000 |
| `limit` | integer | 1000 | 1…100000; сколько строк входа читать |
| `bins` | integer | 10 | только `histogram`, 1…1000 |

Семантика:

- Строки, где `x`/`y` (или `lat`/`lng`) NULL или не число, пропускаются.
- `bar` — значения суммируются по категории (и по значению `series`); при
  `series` бары группируются внутри категории; по умолчанию категории идут по
  убыванию суммы. Бары начинаются от 0, сверху 5 % запаса: верхнее деление всегда
  выше самого высокого бара.
- `line`/`scatter` — без `series` одна серия на колонку `y` (имя — имя колонки);
  с `series` — по серии на значение; точки сортируются по `x`. Оси получают 5 %
  запаса с обеих сторон.
- `histogram` — равные интервалы между min и max `x`; при min = max один бин.
- `pie` — доли одной колонки `y`; легенда и подписи показывают проценты с одним
  знаком; отрицательные значения и нулевая сумма — ошибка.
- `boxplot` — q1/медиана/q3, усы до 1.5×IQR и выбросы-точки; по умолчанию
  категории сортируются по медиане.
- `heatmap` — среднее `y` в ячейке `x` × `series`; последовательная синяя шкала и
  легенда min/max справа.
- `map` — equirectangular-проекция: контуры мира из ресурса
  `viz/world-110m.json` (Natural Earth 110m, public domain, см. `viz/NOTICE.txt`)
  и пузыри по `lat`/`lng`; `size` масштабируется в радиус (с легендой размеров),
  `color` — палитра по категориям, `label` — подпись точки (до 40 точек).
  Координаты вне диапазона — ошибка.
- `facet` — данные делятся по значениям колонки (отсортированы как категории),
  панели рисуются сеткой `facet_columns` в одном SVG/PNG; заголовок общий,
  у каждой панели свои оси и подпись значения фасета.
- Оси: деления «круглые» (1/2/5 × 10^n), границы расширены до целого деления,
  поэтому верхнее деление всегда выше максимума данных; подписи округляются до
  двух знаков; легенда рисуется при двух и более сериях.

```json
{
  "factory": "dap.chart",
  "settings": {
    "file": "/data/chart.svg",
    "format": "svg",
    "type": "bar",
    "x": "category",
    "y": ["amount"],
    "series": "region",
    "title": "Amount by category",
    "subtitle": "Q3 2026",
    "x_label": "Category",
    "y_label": "Amount",
    "y_unit": "EUR",
    "sort": "value",
    "sort_direction": "desc",
    "data_labels": true,
    "annotations": [{"text": "target", "x": "A", "y": 120}],
    "facet": "region",
    "facet_columns": 2,
    "width": 640,
    "height": 400
  }
}
```

## 48. dap.data_quality

Проверяет качество входной таблицы и возвращает таблицу результатов: один
совокупный агрегатный запрос вместо скана на каждую проверку. Колонки результата:
`check`, `column`, `status` (`PASS`/`FAIL`), `message`, `failed_rows`, `total_rows`.
Порты: вход 0 — `Data`, вход 1 — `Reference` (нужен только для `referential`),
выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `checks` | array | — | обязателен, непустой; порядок = порядок строк результата |
| `fail_on` | `error` \| `warn` \| `never` | `error` | `error` — падать на проваленных проверках severity `error`; `warn` — на любой проваленной; `never` — только отчёт |
| `output` | `long` \| `summary` | `long` | `long` — строка на проверку; `summary` — одна строка `check = summary` |

Проверка (`checks[]`):

| Ключ | Тип | Примечание |
|---|---|---|
| `kind` | `not_null` \| `unique` \| `range` \| `pattern` \| `referential` \| `row_count` \| `allowed_values` | обязателен |
| `column` | string | обязателен для всех, кроме `row_count`; для `referential` колонка с тем же именем должна быть во входе `Reference` |
| `min` / `max` | number \| string \| boolean | для `range` — границы значения (хотя бы одна), для `row_count` — границы числа строк (целые, хотя бы одна) |
| `regex` | string | для `pattern`: RE2-выражение DuckDB `regexp_matches` (поиск, не полное совпадение) |
| `values` | array | для `allowed_values`: непустой домен допустимых значений |
| `severity` | `error` \| `warn` | по умолчанию `error`; используется в `fail_on` |

Семантика:

- `failed_rows` — число строк, нарушивших проверку; NULL в колонке проваливает
  только `not_null` (для `unique` NULL — одно значение, как в `GROUP BY`).
- `unique` считает все строки, значение которых встречается больше одного раза
  (оконный `COUNT(*) OVER (PARTITION BY column)` в том же скане).
- `row_count` не смотрит на колонку: `failed_rows` = все строки при нарушении
  границ, иначе 0; `message` содержит фактическое число строк.
- `message` при успехе — `ok`; иначе детерминированный текст вида
  `2 rows sharing a duplicated value`, `1 row outside [0, 4]`,
  `row count 6 below 10`.
- `fail_on` срабатывает после расчёта: при провале узел падает (500, `node_failed`)
  с перечислением проваленных проверок; отчёт при этом не выдаётся.
- `summary` агрегирует: `status` — `FAIL`, если провалена хотя бы одна проверка;
  `message` — `N checks: P passed, F failed`; `failed_rows` — сумма, `total_rows` — строки входа.

```json
{
  "factory": "dap.data_quality",
  "settings": {
    "fail_on": "error",
    "output": "long",
    "checks": [
      {"kind": "not_null", "column": "id"},
      {"kind": "unique", "column": "email", "severity": "warn"},
      {"kind": "range", "column": "score", "min": 0, "max": 100},
      {"kind": "allowed_values", "column": "status", "values": ["new", "paid"]},
      {"kind": "row_count", "min": 1}
    ]
  }
}
```

## 49. dap.hypothesis_test

Один или несколько статистических тестов над входом: строка на сравнение с полями
`test`, `statistic`, `df`, `p_value`, `n`, `comparison`, `p_adjusted`,
`effect_size`, `effect_size_name`, `ci_low`, `ci_high`. p-value считается
движком (`engine/Distributions`: неполная бета/гамма и квадратура studentized
range), потому что в DuckDB нет `erf`/`beta`; статистики параметрических тестов и
Tukey — SQL-агрегаты, ранговые тесты считаются в Java с поправкой на связи.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `test` | `t_test_two_sample` \| `t_test_paired` \| `t_test_one_sample` \| `chi_square` \| `anova` \| `mann_whitney` \| `kruskal_wallis` \| `wilcoxon_signed_rank` \| `tukey_hsd` | — | обязателен |
| `value` | string | — | числовая колонка для t-тестов, ANOVA, Tukey и ранговых тестов; категориальная для `chi_square` |
| `group` | string | — | категориальная колонка для `t_test_two_sample`, `chi_square`, `anova`, `mann_whitney`, `kruskal_wallis`, `tukey_hsd` |
| `group_a` | string | — | для `t_test_two_sample`/`mann_whitney` — значение `group` (сравнивается как текст); для `t_test_paired`/`wilcoxon_signed_rank` — первая числовая колонка |
| `group_b` | string | — | вторая группа или вторая числовая колонка (paired/wilcoxon) |
| `mu` | number | `0` | нулевая гипотеза для `t_test_one_sample` |
| `alpha` | number | `0.05` | проверяется, что в `(0, 1)`; задаёт уровень доверия интервала эффекта |
| `alternative` | `two-sided` \| `less` \| `greater` | `two-sided` | хвост t-тестов, `mann_whitney` и `wilcoxon_signed_rank`; `chi_square`/`anova`/`kruskal_wallis`/`tukey_hsd` всегда верхний |
| `correction` | `none` \| `bonferroni` \| `holm` \| `bh` | `none` | поправка на множественные сравнения для `p_adjusted` по всем строкам узла |

Схема выхода: `test`, `comparison`, `effect_size_name` (`String`), `n`
(`Number (Integer)`), `statistic`, `df`, `p_value`, `p_adjusted`,
`effect_size`, `ci_low`, `ci_high` (`Number (Float)`). `comparison` —
`"A vs B"` для двухвыборочных/парных тестов, `"value vs group"` для
`chi_square`, `"value ~ group"` для `anova`/`kruskal_wallis`,
`"value vs mu"` для одновыборочного t.

Правила:

- `t_test_two_sample` — Welch (как `t.test` в R по умолчанию), `df` дробный; без
  `group_a`/`group_b` берутся ровно две различные группы колонки, иначе ошибка.
  Эффект — Cohen's `d` по объединённому sd, интервал `d ± t(1−α/2, n1+n2−2)·se`.
- `t_test_paired` — `group_a`/`group_b` задают имена двух числовых колонок; пары
  строятся по строкам, строки с NULL в любой из них исключаются; эффект —
  `cohens_dz` (средняя разность / sd разностей).
- `t_test_one_sample` — `(mean(value) − mu) / (sd / sqrt(n))`, `df = n − 1`,
  эффект — Cohen's `d`.
- `chi_square` — тест независимости по таблице сопряжённости `value` × `group`
  (NULL-категории исключаются), `df = (r−1)(c−1)`, эффект — Cramér's `V`.
- `anova` — однофакторный: `df` — числитель `k − 1`, знаменатель `n − k`;
  эффект — eta squared; интервала нет.
- `mann_whitney` — U по рангам (средние ранги для связей), нормальная
  аппроксимация с поправкой на связи и continuity; `statistic` — U первой группы,
  `df` NULL, эффект — rank-biserial `2U/(n1·n2) − 1`.
- `kruskal_wallis` — H с поправкой на связи, `df = k − 1`, p по χ²; эффект —
  epsilon squared `H/(N−1)`.
- `wilcoxon_signed_rank` — `group_a − group_b`, нулевые разности отбрасываются,
  `statistic` — W+, `df` NULL, нормальная аппроксимация с поправкой на связи;
  эффект — matched-pairs rank-biserial.
- `tukey_hsd` — строка на пару групп (в порядке имён групп), `statistic` — q
  studentized range (`Tukey-Kramer`: `se = sqrt(MSE/2·(1/n_i + 1/n_j))`),
  `p_value` — `1 − studentizedRangeCdf(q, k, N−k)` (уже учитывает множественность),
  `p_adjusted` — дополнительно поправленный p; эффект — Cohen's `d` по
  `sqrt(MSE)`, интервал через `t(1−α/2, N−k)`.
- `n` — число использованных наблюдений (сумма по группам; для Wilcoxon — число
  ненулевых пар).
- Меньше двух наблюдений в выборке/группе, меньше двух групп/категорий, ноль строк —
  ошибка конфигурации; при нулевой дисперсии/нулевом MSE `statistic`, `p_value`,
  `effect_size` и интервал — NULL, `df` и `n` заполнены.
- Ранговые тесты и Tukey читают значения в память: не более 100 000 строк, иначе
  ошибка с подсказкой.
- Поправки: `bonferroni` — `p·m`; `holm` — step-down с монотонностью;
  `bh` — Benjamini-Hochberg step-up с монотонностью. Для одной строки
  `p_adjusted = p_value`.

```json
{
  "factory": "dap.hypothesis_test",
  "settings": {
    "test": "tukey_hsd",
    "value": "sepal_length",
    "group": "species",
    "correction": "bh"
  }
}
```

## 50. dap.linear_regression

Линейная регрессия одной числовой колонки на один или несколько числовых
предикторов, при необходимости — отдельно по группам. В режиме `coefficients` —
строка на терм (`intercept` и каждый предиктор), в режиме `diagnostics` — строка
на подгонку, в режиме `predict` — все входные строки плюс предсказание, остаток и
интервал предсказания.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `x` | array<string> | — | обязателен; одна или несколько различных числовых колонок |
| `y` | string | — | обязателен; числовая колонка отклика |
| `group_by` | array<string> | — | необязательные колонки группировки: своя регрессия на группу |
| `mode` | `coefficients` \| `predict` \| `diagnostics` | `coefficients` | — |
| `intercept` | boolean | `true` | `false` — модель без свободного члена |
| `robust` | `none` \| `hc1` | `none` | `hc1` — гетероскедастичность-устойчивая ковариация `(X'X)⁻¹·meat·(X'X)⁻¹·n/(n−p)` |
| `alpha` | number | `0.05` | уровень значимости интервалов: `1 − alpha`; в `(0, 1)` |
| `sample_rows` | integer | `0` | `0` — лимит 100 000 строк; положительное N — детерминированная выборка первых N строк |
| `output` | string | `prediction` | имя колонки предсказания в режиме `predict` |

Выход `coefficients`: `[group_by…]`, `term`, `coefficient`, `std_error`,
`t_statistic`, `p_value`, `r2`, `n`, `adj_r2`, `f_statistic`, `f_p_value`,
`rmse`, `df_residual`, `vif`, `ci_low`, `ci_high`. Подгонка — обычный МНК по
`X'X` и его обратной матрице; стандартные ошибки — классические или HC1, p-value
из `engine/Distributions` (`t` с `df_residual = n − p`), интервал
`coefficient ± t(1−α/2, df)·se`. `vif` — `1/(1−R²_j)` регрессии предиктора на
остальные (только при `intercept`); у `intercept` — NULL.

Выход `diagnostics`: `[group_by…]`, `n`, `df_residual`, `r2`, `adj_r2`,
`f_statistic`, `f_p_value`, `rmse`, `durbin_watson`, `aic`, `bic`.
`f_statistic` — общий F модели (`df1 = p`, `df2 = n − p`), `rmse` — корень из
`MSE`, `aic = n·ln(SSE/n) + 2p`, `bic = n·ln(SSE/n) + ln(n)·p`.

Выход `predict`: входные колонки, затем `output`, `residual` (`y − prediction`),
`prediction_lower` и `prediction_upper` — интервал предсказания для нового
наблюдения: `prediction ± t(1−α/2, df)·sqrt(MSE + x₀'·Cov(β)·x₀)`, затем
`sampled_rows` и `total_rows` (`Number (Integer)`) — сколько строк реально вошло в
подгонку и сколько было доступно до `LIMIT`; по ним видно, что модель обучена на
выборке (`sampled_rows < total_rows`). При
`group_by` коэффициенты подставляются через литеральную таблицу, соединённую по
колонкам группировки (`IS NOT DISTINCT FROM`), поэтому порядок входных строк
сохраняется. `prediction` и интервалы NULL, если NULL предиктор.

Правила: строки с NULL в `x` или `y` исключаются из подгонки; `n ≤ p`
(включая свободный член) — ошибка с подсказкой; вырожденная (сингулярная) матрица
`X'X` — NULL-коэффициенты и NULL-статистики, `n` и `df_residual` заполнены;
идеальная подгонка (`std_error = 0`) даёт NULL `t_statistic`/`p_value`; `r2`
для модели без свободного члена считается по несцентрированному SST и может быть
отрицательным. Категориальные предикторы не поддерживаются — сначала `dap.encoder`.
Подгонка читает значения в память: не более 100 000 строк.
`sample_rows > 0` берёт первые N строк в каноническом порядке — `ORDER BY` по всем
колонкам группировки, предикторам и отклику (строки, равные по всем этим ключам,
взаимозаменяемы для МНК). Благодаря этому один и тот же вход даёт одни и те же
коэффициенты; `COUNT(*) OVER ()` в том же запросе считает `total_rows` до `LIMIT`.
Ограничение: сортировка перед `LIMIT` читает весь вход, поэтому выборка экономит
память подгонки, но не полный scan.

```json
{
  "factory": "dap.linear_regression",
  "settings": {
    "x": ["petal_length", "petal_width"],
    "y": "sepal_length",
    "group_by": ["species"],
    "mode": "coefficients",
    "robust": "hc1"
  }
}
```

## 51. dap.sql_query

Выполняет один read-only запрос `SELECT`/`WITH` по таблицам подключённых входов.
Без `inputs` каждый подключённый вход доступен под id узла-источника (в кавычках:
`FROM "1:1"`); `inputs` задаёт имена подключённых входов в порядке портов (id узла — допустимое имя);
при указании `inputs` должны быть названы все подключённые входы.
Входы ленивые:
их SQL оборачивается в CTE, данные читаются только в `/table`/`/profile`.
Схема выхода определяется запросом через `SELECT * FROM (<sql>) LIMIT 0` +
`ResultSetMetaData`; `LIST`, `STRUCT`, `MAP` и `TIMESTAMPTZ` отклоняются с именем
колонки — у движка нет для них Arrow-представления.
Порты: входы 0–7 — `Data` … `Data 8` (до восьми таблиц); выход 0 — `Data`.
Литералы путей в запросе (`read_parquet(...)`, `read_csv(...)`, `FROM '<path>'`)
проверяются allowlist-ом путей: файл вне разрешённых корней — 400 `path_not_allowed`;
URL `http(s)://`/`s3://` разрешены.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `sql` | string | — | обязателен; один оператор `SELECT` или `WITH` |
| `inputs` | array<string> | — | необязательно; имена всех подключённых входов в порядке портов (id узла или алиас); без поля входы доступны под id узлов |
| `limit` | integer | `10000` | максимум строк результата; от 1 до 1000000 |

Проверка до выполнения: запрос должен начинаться с `SELECT`/`WITH`; запрещены
`INSERT UPDATE DELETE CREATE DROP ALTER ATTACH DETACH COPY INSTALL LOAD PRAGMA SET
EXPORT IMPORT CALL` и второй оператор (`;` вне кавычек). Ключевые слова внутри
строковых литералов, quoted-идентификаторов и комментариев не считаются — поэтому
`SELECT 'a;b'` и колонка `created` допустимы. Нарушение — 400 `sql_not_allowed`
(в синхронном run виден как 500 `node_failed` с сообщением узла, как у любого узла).
Дубли имён колонок результата переименовываются детерминированно: `a`, `a_1`, `a_2` —
поэтому `/profile` работает и для запросов с одинаковыми алиасами.
Таймаут запроса — общий для `/table`/`/profile` (`DAP_QUERY_TIMEOUT_SEC`).

```json
{
  "factory": "dap.sql_query",
  "settings": {
    "sql": "SELECT a.category, COUNT(*) AS n FROM src a JOIN other b ON a.id = b.id GROUP BY a.category",
    "inputs": ["src", "other"],
    "limit": 1000
  }
}
```

## 52. dap.db_reader

Читает одну таблицу или результат read-only запроса из внешней БД и копирует его в
таблицу сессии: остальной граф видит обычную таблицу.
DuckDB-файл подключается нативно; SQLite, PostgreSQL и MySQL — через расширения
`sqlite_scanner`, `postgres_scanner`, `mysql_scanner` (ставятся один раз
`make extensions`; без расширения — ошибка `extension_missing` с этим hint).
Подключение `READ_ONLY` по умолчанию и отключается после копирования
(`DETACH`), поэтому файл не остаётся залоченным.
Порты: входов нет; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `driver` | string | — | обязателен: `duckdb`, `sqlite`, `postgres`, `mysql` |
| `path` | string | — | обязателен для `duckdb`/`sqlite`; абсолютный путь к файлу БД |
| `url` | string | — | обязателен для `postgres`/`mysql`; `user`/`password` можно передать отдельно |
| `table` | string | — | таблица; можно `schema.table`; ровно один из `table`/`query` |
| `query` | string | — | read-only `SELECT`/`WITH`; таблицы внешней БД доступны по имени; ровно один из `table`/`query` |
| `read_only` | boolean | `true` | `ATTACH ... (READ_ONLY)` |
| `user` | string | — | пользователь для `postgres`/`mysql`; не сохраняется в `workflow.json` |
| `password` | string | — | пароль; не сохраняется в `workflow.json` и маскируется в ошибках |

Схема выхода — колонки скопированной таблицы. `TIMESTAMPTZ`, `LIST`, `STRUCT`,
`MAP` из внешней БД отклоняются с именем колонки (нет Arrow-представления) —
выберите другую колонку или `query` с приведением типа.
При `POST /v1/workflows/{id}/save` из настроек `dap.db_reader` удаляются
`user`/`password`, а userinfo в `url` заменяется на `***`: после загрузки
workflow.json учётные данные нужно передать заново.
`query` видит все таблицы подключённой БД как CTE с именем таблицы (одна схема на
имя); при дубле имени между схемами используется первая по порядку схем.

```json
{
  "factory": "dap.db_reader",
  "settings": {
    "driver": "sqlite",
    "path": "/data/sales.db",
    "table": "orders"
  }
}
```

## 53. dap.file_list

Перечисляет каталог таблицей: `path` (абсолютный нормализованный), `name`,
`extension` (без точки; пусто у каталогов и файлов без расширения), `size`
(байты), `modified` (UTC, `Date&Time (Timestamp)`), `is_directory`.
Обход — Java NIO `Files.walk` без перехода по символическим ссылкам
(`LinkOption.NOFOLLOW_LINKS`), поэтому цикл ссылок не зацикливает обход; атрибуты
берутся из `BasicFileAttributes`. Скрытые записи (имя с точки) пропускаются, пока не
включён `include_hidden`. Сортировка и `limit` применяются после фильтрации, поэтому
результат детерминирован (тай-брейк — путь).
Порты: входов нет; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `directory` | string | — | обязателен; каталог должен существовать |
| `pattern` | string | `*` | glob по имени файла (`*.csv`), не по пути |
| `recursive` | boolean | `false` | обходить подкаталоги; по ссылкам не ходит |
| `include_hidden` | boolean | `false` | включить имена, начинающиеся с точки |
| `sort` | string | `name` | `name`, `size` или `modified`; по возрастанию |
| `limit` | integer | `1000` | максимум строк; от 1 до 1000000 |

Отсутствующий каталог, путь-не-каталог, невалидный glob и нецелый `limit` — ошибка
run. `modified` — в UTC, поэтому результат не зависит от таймзоны процесса.
Символическая ссылка попадает в список как запись с `is_directory=false`.

```json
{
  "factory": "dap.file_list",
  "settings": {"directory": "/data/incoming", "pattern": "*.csv", "recursive": true}
}
```

## 54. Рецепты SQL

Часть задач таксономии покрывается не отдельным узлом, а SQL-выражением поверх
`dap.sql_query` (YAGNI: одно выражение — не узел). Каждый рецепт имеет
coverage-сценарий в `tests/coverage/` и проверяется через `/v1` в `SqlRecipeTest`
(рецепты источников и трансформаций) и `SqlRecipeOutputTest` (итоговые рецепты 49.5–49.8).

### 49.1. Regex-извлечение (B9)

`regexp_extract(колонка, шаблон, группа)` — группа 1..n, 0 — всё совпадение;
выражение возвращает String. NULL, если шаблон не совпал.

```json
{
  "factory": "dap.sql_query",
  "settings": {
    "sql": "SELECT regexp_extract(code, '[a-z]+-([0-9]+)', 1) AS number FROM src ORDER BY number",
    "inputs": ["src"]
  }
}
```

### 49.2. Split колонки (B10)

`unnest(string_split(колонка, разделитель))` — одна строка на часть; пустые части
сохраняются (соседние разделители и хвост дают пустые строки). Порядок строк не
определён — добавьте `ORDER BY`, если он важен.

```json
{
  "factory": "dap.sql_query",
  "settings": {
    "sql": "SELECT unnest(string_split(tags, ',')) AS tag FROM src",
    "inputs": ["src"]
  }
}
```

### 49.3. Ковариация (E4)

`covar_samp(a, b)` — выборочная ковариация (делитель n-1); NULL-пары пропускаются,
меньше двух пар — NULL. Для корреляции есть отдельный узел `dap.correlation`.

```json
{
  "factory": "dap.sql_query",
  "settings": {
    "sql": "SELECT covar_samp(x, y) AS covariance FROM src",
    "inputs": ["src"]
  }
}
```

### 49.4. Чтение HTTP/API (A8)

`read_csv_auto(url)` / `read_json_auto(url)` через расширение `httpfs`: нужны
установленное расширение (`make extensions`) и сеть в момент чтения. Остальные
источники (S3, локальный файл) сети не требуют.

```json
{
  "factory": "dap.sql_query",
  "settings": {
    "sql": "SELECT COUNT(*) AS n FROM read_csv_auto('https://raw.githubusercontent.com/plotly/datasets/master/iris.csv')"
  }
}
```

### 49.5. Rollup/cube (C16)

`GROUP BY ROLLUP(...)` (и `GROUP BY CUBE(...)`) в `sql_query`: к каждой комбинации
ключей добавляется промежуточный итог, у итоговой строки ключ — NULL. `ROLLUP(a, b)`
даёт наборы `(a, b)`, `(a)`, `()`, а `CUBE` — все 2^n подмножеств. Итоговая строка
неотличима от строки с NULL-ключом, поэтому для детерминированного порядка добавьте
`ORDER BY ... NULLS LAST`.

```json
{
  "factory": "dap.sql_query",
  "settings": {
    "sql": "SELECT category, SUM(score) AS total FROM src GROUP BY ROLLUP(category) ORDER BY category NULLS LAST",
    "inputs": ["src"]
  }
}
```

На fixture `P2` (`category`/ `score`) это даёт строки `A 7.0`, `B 7.0`, `C 4.0` и
итог `NULL 18.0`.

### 49.6. Нормальность (E11)

`skewness(x)` и `kurtosis(x)` — выборочная асимметрия и excess kurtosis с поправкой на
смещение (те же определения, что `SKEW`/`KURT` в Excel и pandas); NULL игнорируются.
Для сводки добавьте `COUNT(*)`: на малом n значения нестабильны, интерпретируйте их
вместе с размером выборки.

```json
{
  "factory": "dap.sql_query",
  "settings": {
    "sql": "SELECT skewness(sepal_length) AS skewness, kurtosis(sepal_length) AS kurtosis, COUNT(*) AS n FROM src",
    "inputs": ["src"]
  }
}
```

Для iris `sepal_length` это `skewness = 0.3149`, `kurtosis = -0.5521`, `n = 150`.

### 49.7. HAVING (D6)

Фильтр после агрегации — это `dap.group_by` → `dap.row_filter` по имени агрегатной
колонки (`Count(id)`, `Mean(score)`, `Median(score)`, …). Условия те же, что у
`row_filter`; NULL-агрегат сравнение не проходит, как и `HAVING` в SQL.

```json
{
  "factory": "dap.row_filter",
  "settings": {"where": [{"column": "Count(id)", "op": "ge", "value": 2}]}
}
```

На `P2` с группировкой по `category` остаются `A 3` и `B 2` (у `C` одна строка).

### 49.8. Train/test split (C15)

Флаг разбиения считается в `sql_query`, затем две ветки `dap.row_filter` делят строки
по флагу. Важно: `sql_query` — ленивое представление, поэтому `random()` пересчитывается
при каждом чтении и две ветки увидят разные значения — разбиение не будет ни
воспроизводимым, ни полным. Документированный рецепт детерминирован: флаг выводится из
ключа, например `((id * 37) % 10) < 7 AS is_train` (примерно 70/30). Для именно
случайного сплита материализуйте флаг (writer → reader) или возьмите train через
`dap.sample_rows` (`mode: random`, `seed`), а test — anti-join в `sql_query`.

```json
{
  "factory": "dap.sql_query",
  "settings": {
    "sql": "SELECT *, ((id * 37) % 10) < 7 AS is_train FROM src",
    "inputs": ["src"]
  }
}
```

Ветки — два `dap.row_filter` с условиями `is_train eq true` и `is_train eq false`;
полноту разбиения проверяйте `dap.concatenate` (train + test = вход).

## 55. Порядок для агента

1. `POST /v1/datasets` (инструмент `dap_ingest`) — один раз привести CSV/Excel/JSON
   к Parquet-кэшу, если файл читается многократно.
2. `GET /v1/nodes?q=…` — найти `id`.
3. `GET /v1/nodes/{id}/dialog` — поля, типы, умолчания, допустимые значения.
4. `POST /v1/workflows/{id}/nodes` с `factory` и `settings`. Узлы, добавляющие
   колонку (`date_time`, `rule_engine`, `binner`, `normalizer`, `encoder`,
   `row_aggregator`, `window`, `rolling`, `diff`), требуют новое имя выхода: оно не
   должно совпадать с входной колонкой. `dap.outlier_detect` добавляет колонку с
   фиксированным именем `outlier`, `dap.date_range` — источник без входа, а
   `dap.describe`, `dap.value_counts`, `dap.correlation`, `dap.histogram` и
   `dap.quantile` возвращают не вход, а сводную таблицу: колонки входа после них
   в графе недоступны.
5. `POST /v1/workflows/{id}/connect` — связи (порты данных с 0). У `dap.joiner` два
   входа (0 — Left, 1 — Right), у `dap.concatenate` — (0 — Top, 1 — Bottom); оба входа
   обязательны.
6. `POST /v1/workflows/{id}/run` (или `/jobs`) → `GET .../table`, `.../profile`.

Примеры выше — тело запроса `POST /v1/workflows/{id}/nodes`.

## 56. dap.chart_grid

Рисует от 1 до 8 графиков в один файл — сетку панелей — и возвращает вход порта 0
без изменений (pass-through). Каждая панель читает свой вход и принимает те же
настройки, что `dap.chart` (кроме `file`/`format`/`width`/`height`: размер
панели задаёт сетка). `format: svg` — один детерминированный SVG; `format: png` —
Java2D; `format: html` — самодостаточная страница с `<h1>`, одним inline `<svg>` на
панель и без внешних ресурсов. Файл пишется атомарно, родительские каталоги
создаются.
Порты: входы 0…7 — `Data 1`…`Data 8` (подключаются только используемые), выход 0 —
`Data` (таблица входа 0).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь к `.svg`, `.png` или `.html` |
| `format` | `svg` \| `png` \| `html` | `svg` | `html` — один `<svg>` на панель |
| `title` | string | — | общий заголовок сетки |
| `columns` | integer | 2 | панелей в строке, 1…8 |
| `width` / `height` | integer | 1200 / 800 | 100…4000; панель получает width/columns и (height − заголовок)/строк |
| `charts` | array | — | обязателен, 1…8 объектов |
| `charts[].input` | integer | 0 | номер входного порта 0…7 |
| `charts[].type` | string | — | обязателен; те же типы, что у `dap.chart` |
| `charts[].x`, `.y`, `.series`, `.title`, … | — | — | остальные ключи — как у `dap.chart` |

Семантика:

- `input` указывает на подключённый порт: неподключённый порт — ошибка.
- Панели заполняются слева направо и сверху вниз; общий заголовок рисуется один раз.
- SVG детерминирован; PNG и HTML проверяются smoke-тестами (размеры, число `<svg>`).

```json
{
  "factory": "dap.chart_grid",
  "settings": {
    "file": "/data/dashboard.svg",
    "format": "svg",
    "title": "Sales dashboard",
    "columns": 2,
    "width": 1200,
    "height": 800,
    "charts": [
      {"input": 0, "type": "bar", "x": "category", "y": ["amount"], "title": "Amount"},
      {"input": 1, "type": "line", "x": "day", "y": ["amount"], "title": "Trend"},
      {"input": 2, "type": "pie", "x": "region", "y": ["amount"], "title": "Share"}
    ]
  }
}
```
## 57. dap.file_reader

Читает много файлов как одну таблицу: список файлов (`paths`), glob или каталог
(`path`). Форматы — `csv`, `parquet`, `json` (JSON-массив или newline-delimited).
Файлы раскрываются в движке: glob — `Files.walk` + `PathMatcher`, каталог —
`Files.walk` на глубину 1 или без ограничения при `recursive`; результат
сортируется по пути, ограничен 10 000 файлов, каждый файл проходит allowlist путей
(вне разрешённых корней — 400 `path_not_allowed`). Чтение всегда потоковое:
DuckDB получает список файлов и читает их `read_csv`/`read_parquet`/
`read_json_auto`, в heap ничего не загружается.
ORC и Avro не поддерживаются (в DuckDB 1.5.6 нет ORC-расширения для darwin/arm64,
Avro нет в ядре) — ошибка с подсказкой конвертировать в parquet/csv.
Порты: входов нет; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `path` | string | — | файл, каталог или glob; ровно один из `path`/`paths` |
| `paths` | array<string> | — | несколько файлов/каталогов/glob; ровно один из `path`/`paths` |
| `format` | `csv` \| `parquet` \| `json` | — | обязателен |
| `recursive` | boolean | `false` | обходить каталог рекурсивно |
| `union_by_name` | boolean | `false` | объединять разные схемы по именам колонок |
| `filename` | boolean | `false` | добавить колонку `filename` (Utf8) с путём файла |
| `delimiter` | string | `,` | CSV-разделитель, один символ (`\t` — табуляция) |
| `header` | boolean | `true` | первая строка CSV — заголовок |
| `quote` | string | — | CSV-quote, один символ |
| `escape` | string | — | CSV-escape, один символ |
| `nullstr` | string | — | текст, читаемый как NULL |
| `ignore_errors` | boolean | `false` | пропускать битые CSV-строки |

Пустой набор файлов — ошибка с перечнем путей; неизвестный формат — ошибка со
списком `csv`, `parquet`, `json`.

```json
{
  "factory": "dap.file_reader",
  "settings": {
    "path": "/data/events/*.parquet",
    "format": "parquet",
    "recursive": true,
    "union_by_name": true,
    "filename": true
  }
}
```

## 58. dap.db_writer

Пишет входную таблицу в DuckDB-файл, SQLite-файл или PostgreSQL/MySQL и возвращает
вход без изменений (pass-through), поэтому узел можно ставить в середину графа.
Цель подключается как DuckDB-база (`ATTACH ... (TYPE DUCKDB|SQLITE|POSTGRES|MYSQL)`),
таблица создаётся (`create`), пересоздаётся (`replace`) или дополняется
(`append`), затем `DETACH`. Локальный путь проверяется allowlist-ом
(`checkWrite`, вне корней — 400 `path_not_allowed`); пароль в DSN маскируется в
ошибках. Для sqlite/postgres/mysql нужно расширение DuckDB (`make extensions`),
иначе — 500 `extension_missing` с hint.
Порты: вход 0 — `Data`; выход 0 — `Data` (тот же вход).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `driver` | `duckdb` \| `sqlite` \| `postgres` \| `mysql` | — | обязателен |
| `connection` | string | — | путь к файлу для duckdb/sqlite или DSN для postgres/mysql |
| `table` | string | — | обязательна; `schema.table` допустим |
| `mode` | `create` \| `replace` \| `append` | `create` | `create` падает, если таблица уже есть |

```json
{
  "factory": "dap.db_writer",
  "settings": {
    "driver": "duckdb",
    "connection": "/data/analytics.duckdb",
    "table": "analytics.events",
    "mode": "replace"
  }
}
```

## 59. dap.text_normalize

Нормализует текстовые колонки и сохраняет исходные рядом с нормализованными
(`suffix`, по умолчанию `_normalized`) либо заменяет их (`replace: true`).
Поддержаны Unicode-формы NFC/NFD/NFKC/NFKD, снятие диакритики, регистр, удаление
пунктуации, схлопывание пробелов и trim. DuckDB 1.5.6 умеет только
`nfc_normalize`, поэтому NFC, регистр, пунктуация, пробелы и trim считаются SQL-ом,
а NFD/NFKD/NFKC и снятие диакритики — в Java по **уникальным значениям** выбранных
колонок (не более 100 000 уникальных; выше — ошибка с подсказкой отфильтровать
таблицу), после чего отображение джойнится обратно в SQL: число строк в heap не
попадает. Колонки должны быть `Utf8`.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | обязателен, непустой; только `Utf8` |
| `mode` | `none` \| `nfc` \| `nfd` \| `nfkc` \| `nfkd` | `none` | форма нормализации Unicode |
| `case` | `none` \| `lower` \| `upper` | `none` | регистр |
| `strip_accents` | boolean | `false` | снять диакритику (Café → Cafe) |
| `collapse_whitespace` | boolean | `false` | серии пробелов → один пробел |
| `trim` | boolean | `false` | обрезать пробелы по краям |
| `remove_punctuation` | boolean | `false` | удалить Unicode-пунктуацию |
| `suffix` | string | `_normalized` | суффикс новой колонки (когда `replace: false`) |
| `replace` | boolean | `false` | заменить значения исходных колонок |

```json
{
  "factory": "dap.text_normalize",
  "settings": {
    "columns": ["company"],
    "mode": "nfkc",
    "strip_accents": true,
    "case": "lower",
    "collapse_whitespace": true,
    "trim": true
  }
}
```

## 60. dap.fuzzy_join

Соединяет две таблицы по похожим текстовым ключам. Похожесть считает DuckDB:
`jaro_winkler_similarity`, `levenshtein` или `damerau_levenshtein` (для
расстояний — нормировка `1 - dist / max(length(l), length(r))`). Пары ниже
`threshold` отбрасываются, на каждую левую строку остаётся до `max_matches`
лучших правых (`row_number() OVER (PARTITION BY left_row ORDER BY similarity DESC)`);
`how: left` сохраняет левые строки без пары (NULL в similarity и правых колонках).
В выходе — все колонки обеих сторон плюс `similarity` (Float64); правая колонка с
совпадающим именем получает `suffix`. Оба входа материализуются как CTE с
синтетическим row id (`MATERIALIZED`), чтобы id были стабильны.
Порты: вход 0 — `Left`, 1 — `Right`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `left_key` | string | — | обязателен; `Utf8`-колонка левого входа |
| `right_key` | string | — | обязателен; `Utf8`-колонка правого входа |
| `method` | `jaro_winkler` \| `levenshtein` \| `damerau` | `jaro_winkler` | мера похожести |
| `threshold` | number | `0.8` | минимум похожести, 0..1 |
| `how` | `inner` \| `left` | `inner` | сохранять ли левые строки без пары |
| `max_matches` | integer | `1` | сколько правых строк может получить левая |
| `suffix` | string | `" (Right)"` | суффикс совпадающей правой колонки |

```json
{
  "factory": "dap.fuzzy_join",
  "settings": {
    "left_key": "name",
    "right_key": "legal_name",
    "method": "jaro_winkler",
    "threshold": 0.9,
    "how": "left",
    "max_matches": 1
  }
}
```

## 61. dap.geo_distance

Добавляет haversine-расстояние между двумя парами координат (широта/долгота в
десятичных градусах). Считается SQL-ом, средний радиус Земли 6371.0088 км; `unit`
`km` или `mi`. Нужны обе пары координат; колонки должны быть числовыми.
Исходные колонки сохраняются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `lat` | string | — | обязателен; широта первой точки |
| `lng` | string | — | обязателен; долгота первой точки |
| `lat2` | string | — | обязателен; широта второй точки |
| `lng2` | string | — | обязателен; долгота второй точки |
| `unit` | `km` \| `mi` | `km` | единица расстояния |
| `output` | string | `distance` | имя колонки с расстоянием |

Париж (48.8566, 2.3522) — Лондон (51.5074, −0.1278) даёт ≈ 343.5 км.

```json
{
  "factory": "dap.geo_distance",
  "settings": {
    "lat": "store_lat", "lng": "store_lng",
    "lat2": "client_lat", "lng2": "client_lng",
    "unit": "km", "output": "km_apart"
  }
}
```

## 62. dap.calendar

Добавляет календарные признаки даты/времени с учётом таймзоны: `year`, `quarter`,
`month`, `week` (ISO), `day` (день месяца), `dow` (0 = воскресенье), `doy`,
`is_weekend`, `is_holiday`. Колонка `Timestamp` трактуется как UTC и переводится
в `timezone` до взятия частей даты (2024-01-01 22:00 UTC — это уже 2024-01-02 в
Europe/Moscow); у `Date32` времени суток нет. `holidays` — список дат ISO
(`yyyy-MM-dd`), `holiday_column` — имя флага. Исходные колонки сохраняются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `column` | string | — | обязателен; `Date32` или `Timestamp` |
| `timezone` | string | — | IANA-таймзона (Europe/Moscow); для Timestamp — перевод из UTC |
| `features` | array<string> | все, кроме `is_holiday` | какие признаки добавить |
| `holidays` | array<string> | `[]` | даты-праздники ISO; включает `is_holiday` по умолчанию |
| `holiday_column` | string | `<column>_is_holiday` | имя флага праздника |

Неизвестная таймзона, неверная дата в `holidays`, `is_holiday` без `holidays` и
неизвестный признак — ошибка с сообщением. 2024-01-01 — понедельник
(`dow = 1`), `is_weekend = false`; 2024-01-06 — суббота, `is_weekend = true`.

```json
{
  "factory": "dap.calendar",
  "settings": {
    "column": "created_at",
    "timezone": "Europe/Moscow",
    "features": ["year", "month", "dow", "is_weekend", "is_holiday"],
    "holidays": ["2024-01-01", "2024-05-09"],
    "holiday_column": "is_holiday"
  }
}
```

## 63. dap.logistic_regression

Бинарная логистическая регрессия: отклик `y` — 0/1 (числовой) или boolean, предикторы
`x` — одна или несколько числовых колонок. Подгонка — IRLS (Newton-Raphson) по
`X'WX`; режим `coefficients` возвращает строку на терм (`intercept` и каждый
предиктор), режим `predict` — все входные строки плюс вероятность и класс.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `x` | array<string> | — | обязателен; одна или несколько различных числовых колонок |
| `y` | string | — | обязателен; бинарная колонка 0/1 или boolean, должны быть оба класса |
| `mode` | `coefficients` \| `predict` | `coefficients` | — |
| `robust` | `none` \| `hc1` | `none` | `hc1` — сэндвич-оценка `(X'WX)⁻¹·[Σ(y−p)²xx']·(X'WX)⁻¹·n/(n−p)` |
| `max_iterations` | integer | `50` | положительное; предел итераций Ньютона |
| `tolerance` | number | `1e-8` | положительное; остановка по максимальному изменению коэффициента |
| `l2` | number | `0` | необязательный ridge-штраф на диагональ `X'WX` для не-свободных термов |
| `alpha` | number | `0.05` | уровень значимости интервала лог-шансов: `1 − alpha` |
| `output` | string | `probability` | имя колонки вероятности в режиме `predict` |

Выход `coefficients`: `term`, `coefficient` (лог-шансы), `std_error`,
`z_statistic`, `p_value` (Wald, нормальный хвост), `ci_low`, `ci_high`
(интервал лог-шансов `coefficient ± z(1−α/2)·se`), `odds_ratio` (`exp(coefficient)`),
`n`, `pseudo_r2` (McFadden: `1 − LL/LL₀`), `auc` (ранговая, Манн-Уитни по
вероятностям), `iterations`, `converged`.

Выход `predict`: входные колонки, затем `output` (`Number (Float)` — сигмоида
линейного предиктора) и `prediction` (`Number (Integer)` — 1 при вероятности
`≥ 0.5`, иначе 0). `probability` и `prediction` NULL, если NULL предиктор.

Правила: строки с NULL в `x` или `y` исключаются; `y` вне `{0, 1}` — ошибка
`binary`; один класс в `y` — ошибка (нужны оба); `n ≤ p` (включая свободный
член) — ошибка с подсказкой; при идеальном разделении без `l2` итерации Ньютона
могут не сойтись — `converged = false`, `l2 > 0` даёт конечное решение; сингулярная
матрица `X'WX` оставляет коэффициенты NULL, `n` и `iterations` заполнены.
Подгонка читает значения в память: не более 100 000 строк.

```json
{
  "factory": "dap.logistic_regression",
  "settings": {
    "x": ["sepal_length", "sepal_width"],
    "y": "is_big",
    "robust": "hc1",
    "max_iterations": 100,
    "tolerance": 1e-8
  }
}
```


## 64. dap.cluster

Кластеризация числовых колонок: `kmeans` (k-means++, по умолчанию), `dbscan` или
иерархическая агломеративная кластеризация (`ward`, `complete`, `average`). К входу
добавляются Int64-колонка `cluster` (`-1` — шум DBSCAN) и Float64 `silhouette`
(на строку; NULL для шума и когда кластеров меньше двух). Признаки по умолчанию
стандартизуются (z-score). `kmeans` и `dbscan` реализованы в движке; иерархическая
кластеризация держит матрицу расстояний `n × n` (O(n²) памяти, O(n³) времени) и поэтому
ограничена 1000 строк.
## 65. dap.resample_test

Bootstrap-доверительные интервалы и перестановочный тест на две группы; считаются в
Java по строкам входа (не более `max_rows`), результат — литеральный план, как у
остальных in-memory статистик. В режиме `bootstrap` наблюдения пересэмплируются с
возвращением, выдаётся одна строка: `statistic`, `estimate`, `se` (стандартное
отклонение пересэмплированных статистик), `ci_low`/`ci_high` (перцентильный
интервал уровня `confidence`), `bias` (среднее пересэмплированных минус estimate),
`n`, `resamples`. Статистики: `mean`, `median`, `sd` (выборочное, n−1),
`correlation` (Пирсон, две колонки), `slope` (МНК-наклон второй колонки по первой).
В режиме `permutation` метки `group` переставляются между двумя уровнями `value`,
выдаётся одна строка: `statistic`, `observed` (разность средних или медиан: первая
группа в лексикографическом порядке минус вторая), `p_value` (оценка «+1»:
`(extreme + 1) / (permutations + 1)`, поэтому p никогда не 0), `n`, `permutations`.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | обязателен; непустой; только числовые колонки |
| `method` | `kmeans` \| `dbscan` \| `hierarchical` | `kmeans` | способ кластеризации |
| `k` | integer | `3` | положительный; для `kmeans`/`hierarchical`; `k > n` — ошибка; для `dbscan` игнорируется |
| `standardize` | boolean | `true` | z-score признаков перед кластеризацией; константная колонка обнуляется |
| `seed` | integer | `42` | seed k-means++; одинаковый seed и вход дают одинаковые кластеры |
| `max_iterations` | integer | `100` | положительный предел итераций Ллойда |
| `eps` | number | `0.5` | положительный радиус соседства DBSCAN |
| `min_points` | integer | `5` | положительный минимум соседей (с самой точкой) для core-точки |
| `linkage` | `ward` \| `complete` \| `average` | `ward` | правило слияния иерархической кластеризации |
| `max_rows` | integer | `50000` | положительный предел in-memory; больше — ошибка с подсказкой |

`cluster`/`silhouette` добавляются последними; занятое имя любой из них — ошибка run.
Строки с NULL или нефинитным значением в выбранной колонке в модель не входят и получают
`cluster = NULL`, `silhouette = NULL`. Силуэт: `a` — среднее расстояние до своего
кластера (без самой точки), `b` — минимум средних расстояний до других кластеров,
`s = (b − a) / max(a, b)`; для кластера из одной точки `s = 0`. Силуэт считается по
всем парам строк (O(n²) на запуск), поэтому большие входы заметно медленнее. Строки читаются в
лексикографическом порядке вектора признаков, поэтому результат не зависит от порядка
сканирования входа. `k > n` — ошибка `k=… exceeds the … rows`; превышение `max_rows` —
ошибка с подсказкой поднять `max_rows` или отфильтровать вход.

```json
{
  "factory": "dap.cluster",
  "settings": {"columns": ["sepal_length", "petal_length"], "method": "kmeans", "k": 3, "seed": 42}
}
```


## 66. dap.pca

Метод главных компонент по ковариационной матрице и разложению Якоби. Три режима:
`scores` добавляет к входу `pc1..pcN` (линейные комбинации центрированных, при
необходимости стандартизованных колонок), `loadings` выдаёт строку на компоненту и
колонку, `explained` — собственную ценность, долю объяснённой дисперсии и накопленную
долю на компоненту. Признаки по умолчанию стандартизуются (z-score). Знак каждой
компоненты фиксируется так, чтобы её наибольшая по модулю нагрузка была положительной —
это делает выход воспроизводимым.
| `mode` | `bootstrap` \| `permutation` | — | обязателен |
| `statistic` | `mean` \| `median` \| `sd` \| `correlation` \| `slope` \| `difference_mean` \| `difference_median` | — | обязателен; bootstrap принимает первые пять, permutation — две разности |
| `columns` | array<string> | — | 1 числовая колонка для mean/median/sd, 2 различные для correlation/slope (x, y) |
| `value` | string | — | permutation: числовая колонка значений |
| `group` | string | — | permutation: колонка ровно с двумя уровнями |
| `resamples` | integer | `1000` | положительное |
| `permutations` | integer | `1000` | положительное |
| `confidence` | number | `0.95` | в (0, 1); уровень перцентильного интервала |
| `alternative` | `two-sided` \| `less` \| `greater` | `two-sided` | хвост permutation p-value |
| `seed` | integer | `42` | сид одного `java.util.Random`; одинаковый сид — одинаковые числа |
| `max_rows` | integer | `100000` | больше строк — ошибка с подсказкой |

Строки с NULL в выбранных колонках исключаются. Для `sd` нужно ≥2 строк, для
`correlation`/`slope` — ≥3 и положительная дисперсия; вырожденные пересэмплы
(например, все x одинаковы) пропускаются. Seed детерминирует каждый draw.

```json
{
  "factory": "dap.resample_test",
  "settings": {
    "mode": "bootstrap",
    "statistic": "mean",
    "columns": ["revenue"],
    "resamples": 2000,
    "confidence": 0.95,
    "seed": 42
  }
}
```

## 67. dap.survival

Анализ выживаемости: кривая Каплана-Мейера и лог-ранк тест; считаются в Java по
строкам входа (не более 100 000), результат — таблица в сессии (у кривой бывает
строка на каждое время события) или литеральная строка для лог-ранка.
`time` — числовая колонка или `Date32` (неотрицательное время наблюдения),
`event` — boolean или 0/1 (1/true — событие, 0/false — цензурирование), `group` —
необязательная категориальная колонка. Строки с NULL во времени, событии или группе
исключаются.
Порты: вход 0 — `Data`; выход 0 — `Data`.

Режим `km` — строка на каждое различное время события: `group`? (если задана),
`time`, `n_at_risk` (наблюдения с временем ≥ t), `events` (события в t),
`censored` (цензурирования в t), `survival` (произведение `1 − d/n`),
`ci_low`/`ci_high` — интервал Гринвуда на лог-шкале
`exp(log S ∓ z·√(Σ d/(n(n−d))))`, зажатый в [0, 1] (как `survfit`,
`conf.type = "log"`). Если последнее наблюдение — событие, то S = 0, а дисперсия
Гринвуда бесконечна, поэтому интервал максимально широкий [0, 1]. Времена только
цензурирования строк не дают.

Режим `logrank` — одна строка: `test` (`logrank`), `statistic` (Mantel-Haenszel
`(O−E)′V⁻¹(O−E)` по первым k−1 группам), `df` (k−1), `p_value` (хи-квадрат с k−1
степенями свободы), `groups` (k). Нужны минимум две группы и хотя бы одно событие;
вырожденная (сингулярная) матрица дисперсии даёт ошибку с объяснением.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `time` | string | — | обязателен; числовая колонка или `Date32` |
| `event` | string | — | обязателен; boolean или 0/1 |
| `group` | string | — | необязательна для `km`, обязательна для `logrank` |
| `mode` | `km` \| `logrank` | `km` | — |

```json
{
  "factory": "dap.survival",
  "settings": {
    "time": "months",
    "event": "churned",
    "group": "plan",
    "mode": "km"
  }
}
```

## 68. dap.ab_test

A/B-тест: двухвыборочный z-тест для долей плюс мощность и необходимый размер выборки.
Счётчики агрегируются в SQL (`COUNT`/`SUM` по группам), строки в Java не читаются.
Два уровня `group` упорядочены лексикографически: первый — контроль, второй — вариант
(детерминированно и задокументировано). Выход — одна строка.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `group` | string | — | обязателен; колонка ровно с двумя уровнями |
| `value` | string | — | обязателен; boolean или числовая 0/1 (конверсии) |
| `alpha` | number | `0.05` | в (0, 1); двусторонний уровень теста и формул мощности |
| `power` | number | `0.8` | в (0, 1); целевая мощность для `required_n_per_group` |
| `mde` | number | — | относительный lift для `required_n_per_group`; без него — наблюдаемый lift |
| `baseline` | number | — | доля контроля для `required_n_per_group`; без неё — наблюдаемая доля контроля |

Выход: `control_rate`, `treatment_rate`, `lift` (относительный:
`treatment/control − 1`), `z_statistic` (z с объединённой дисперсией
`p̄(1−p̄)(1/n₁+1/n₂)`), `p_value` (двусторонний нормальный хвост), `power`
(достигнутая мощность для наблюдаемого эффекта и размеров групп), `required_n_per_group`
(формула Флейсса:
`(z(1−α/2)√(2p̄(1−p̄)) + z(power)√(p₁(1−p₁)+p₂(1−p₂)))² / (p₂−p₁)²`, округление
вверх; NULL, если эффект нулевой или доли вырождены), `mde` (использованный lift),
`alpha`. Достигнутая мощность использует
`Φ((|p₂−p₁|√n_eff − z(1−α/2)√(2p̄(1−p̄))) / √(p₁(1−p₁)+p₂(1−p₂)))` с
`n_eff = 2n₁n₂/(n₁+n₂)`. NULL-строки исключаются; значения вне 0/1 — ошибка.
Порты: вход 0 — `Data`; выход 0 — `Data`.

```json
{
  "factory": "dap.ab_test",
  "settings": {
    "group": "variant",
    "value": "converted",
    "alpha": 0.05,
    "power": 0.8,
    "mde": 0.2,
    "baseline": 0.1
  }
}
```

## 69. dap.time_series

Анализ временного ряда: декомпозиция, ACF/PACF, лаги, прогноз и тест стационарности.
Строки упорядочиваются по `time` внутри каждой группы `group_by`; Java-режимы читают
не более `max_rows` строк и возвращают результат таблицей в сессии (у декомпозиции
строка на наблюдение), режим `lag` остаётся ленивым SQL-планом с
`LAG(...) OVER (...)`. `time` — числовая колонка или `Date32`, `value` — числовая.
Выходная колонка времени называется `time`; колонки групп идут первыми.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | обязателен; непустой; только числовые колонки |
| `components` | integer | `2` | положительный; `components > columns` — ошибка |
| `standardize` | boolean | `true` | z-score признаков перед разложением; константная колонка обнуляется |
| `mode` | `scores` \| `loadings` \| `explained` | `scores` | что вернуть |
| `max_rows` | integer | `100000` | положительный предел in-memory; больше — ошибка с подсказкой |

Выход `scores`: входные колонки плюс `pc1..pcN` (Float64); занятое имя — ошибка run,
строка с NULL в любой выбранной колонке получает NULL во всех компонентах. Выход
`loadings`: `component` (Int64, с 1), `column` (Utf8), `loading` (Float64), строки в
порядке компонент, затем колонок. Выход `explained`: `component`, `eigenvalue`,
`explained_variance` (собственная ценность / сумма всех собственных ценностей),
`cumulative` (накопленная доля по выбранным компонентам). Нужно не менее двух полных
строк; превышение `max_rows` — ошибка с подсказкой поднять `max_rows` или отфильтровать
вход.

```json
{
  "factory": "dap.pca",
  "settings": {"columns": ["sepal_length", "petal_length"], "components": 2, "standardize": true, "mode": "scores"}
| `mode` | `decompose` \| `acf` \| `lag` \| `forecast` \| `stationarity` | — | обязателен |
| `time` | string | — | обязателен; числовая колонка или `Date32` |
| `value` | string | — | обязателен; числовая колонка ряда |
| `group_by` | array<string> | — | необязательные колонки: отдельный ряд на группу |
| `period` | integer | `12` | ≥2; период сезонности для `decompose` и `holt_winters` |
| `max_lag` | integer | `20` | максимальный лаг ACF или число лаговых колонок в `lag` |
| `horizon` | integer | `12` | число шагов прогноза |
| `method` | `additive` \| `multiplicative` \| `naive` \| `seasonal_naive` \| `mean` \| `drift` \| `holt_winters` | — | для `decompose` — additive/multiplicative, для `forecast` — остальные |
| `confidence` | number | `0.95` | в (0, 1); уровень интервала прогноза |
| `max_rows` | integer | `100000` | больше строк — ошибка с подсказкой |

Выходы:

- `decompose` → `time`, `observed`, `trend`, `seasonal`, `residual`: тренд —
  центрированное скользящее среднее порядка `period` (для чётного периода 2×period),
  сезонные индексы — средние детрендированного ряда по позициям, нормированные в сумму 0
  (additive) или в среднее 1 (multiplicative); `residual = observed − trend − seasonal`
  (или `observed / (trend·seasonal)`); на краях, где нет полного окна, `trend` и
  `residual` — NULL. Нужно ≥ 2·period строк на группу; multiplicative требует
  строго положительных значений.
- `acf` → `lag`, `acf`, `pacf`: `r_k = Σ(y_t−ȳ)(y_{t+k}−ȳ) / Σ(y_t−ȳ)²` для
  `k = 0..max_lag` (ACF(0) = 1), PACF — рекурсия Дарбина-Левинсона; нужно больше
  `max_lag` строк на группу.
- `lag` → все входные колонки плюс `value_lag_1..value_lag_<max_lag>` (`Number (Float)`).
- `forecast` → `time`, `forecast`, `lower`, `upper`, `method`: naive (последнее
  значение), seasonal_naive (значение того же сезона), mean, drift (последнее + средний
  наклон) или аддитивный Holt-Winters с фиксированным сглаживанием 0.3/0.1/0.1; время
  будущих точек — последнее + средний шаг × h. Интервал —
  `forecast ± z(confidence)·σ·√h`, где σ — стандартное отклонение одношаговых
  остатков выбранного метода (приближение, задокументировано).
- `stationarity` → `test` (`adf`), `statistic`, `p_value`, `critical_1`,
  `critical_5`, `critical_10`, `stationary`: ADF с константой и одним лагом разности,
  t-статистика коэффициента при `y_{t−1}`; критические значения МакКиннона
  (−3.43, −2.86, −2.57), p-value — нормальная аппроксимация, подогнанная под эти три
  значения; `stationary = statistic < −2.86` (отвергаем единичный корень на 5 %).
  Нужно ≥ 8 строк на группу.

```json
{
  "factory": "dap.time_series",
  "settings": {
    "mode": "forecast",
    "time": "month",
    "value": "revenue",
    "group_by": ["region"],
    "period": 12,
    "horizon": 6,
    "method": "seasonal_naive"
  }
}
```
