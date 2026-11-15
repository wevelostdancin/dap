# NODES — каталог узлов

Каталог нативного движка: 53 узла `dap.*`. Источник истины — JSON-спеки
`engine-native/src/main/resources/specs/*.json`, список — `specs/index.txt` (53 строки);
`NodeRegistry` читает их из classpath и падает при отсутствующем или дублирующем
спеке. Все 53 узла имеют `available: true` и `tier: core`.

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
    строки с NULL в любой колонке пары исключены.
  - `dap.histogram` — выдаются все бины, включая пустые (`count: 0`); последний бин
    включает максимум; константная колонка попадает целиком в первый бин.
  - `dap.quantile` — вероятности в `[0, 1]` включительно; `QUANTILE_CONT` интерполирует
    между соседними значениями, строка на каждую вероятность в порядке массива.
  - `dap.outlier_detect` — добавляет Boolean-колонку `outlier`; NULL не бывает выбросом;
    статистика берётся по всему входу, а не по окну.
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
| `dap.csv_reader` | CSV Reader | `/IO/Read` | — |
| `dap.excel_reader` | Excel Reader | `/IO/Read` | — |
| `dap.parquet_reader` | Parquet Reader | `/IO/Read` | — |
| `dap.json_reader` | JSON Reader | `/IO/Read` | — |
| `dap.row_filter` | Row Filter | `/Manipulation/Row/Filter` | 1 |
| `dap.column_filter` | Column Filter | `/Manipulation/Column/Filter` | 1 |
| `dap.column_renamer` | Column Renamer | `/Manipulation/Column` | 1 |
| `dap.sorter` | Sorter | `/Manipulation/Row/Sort` | 1 |
| `dap.missing_value` | Missing Value | `/Manipulation/Column/Missing` | 1 |
| `dap.group_by` | Group By | `/Manipulation/Row/GroupBy` | 1 |
| `dap.joiner` | Joiner | `/Manipulation/Row/Join` | 2 |
| `dap.string_manipulation` | String Manipulation | `/Manipulation/Column/String` | 1 |
| `dap.math_formula` | Math Formula | `/Manipulation/Column/Math` | 1 |
| `dap.csv_writer` | CSV Writer | `/IO/Write` | 1 (pass-through) |
| `dap.parquet_writer` | Parquet Writer | `/IO/Write` | 1 (pass-through) |
| `dap.json_writer` | JSON Writer | `/IO/Write` | 1 (pass-through) |
| `dap.type_cast` | Type Cast | `/Manipulation/Column/Type` | 1 |
| `dap.duplicate_filter` | Duplicate Filter | `/Manipulation/Row` | 1 |
| `dap.value_replace` | Value Replace | `/Manipulation/Column` | 1 |
| `dap.column_resorter` | Column Resorter | `/Manipulation/Column` | 1 |
| `dap.outlier_filter` | Outlier Filter | `/Manipulation/Row/Filter` | 1 |
| `dap.date_time` | Date & Time | `/Manipulation/Column/Date` | 1 |
| `dap.rule_engine` | Rule Engine | `/Manipulation/Column` | 1 |
| `dap.binner` | Binner | `/Manipulation/Column` | 1 |
| `dap.normalizer` | Normalizer | `/Manipulation/Column` | 1 |
| `dap.encoder` | Encoder | `/Manipulation/Column` | 1 |
| `dap.sample_rows` | Sample Rows | `/Manipulation/Row` | 1 |
| `dap.row_aggregator` | Row Aggregator | `/Manipulation/Column` | 1 |
| `dap.pivot` | Pivot | `/Manipulation/Column` | 1 |
| `dap.unpivot` | Unpivot | `/Manipulation/Column` | 1 |
| `dap.window` | Window | `/Manipulation/Column` | 1 |
| `dap.top_n_per_group` | Top N per Group | `/Manipulation/Row` | 1 |
| `dap.concatenate` | Concatenate | `/Manipulation/Row/Join` | 2 |
| `dap.describe` | Describe | `/Statistics` | 1 |
| `dap.value_counts` | Value Counts | `/Statistics` | 1 |
| `dap.correlation` | Correlation | `/Statistics` | 1 |
| `dap.histogram` | Histogram | `/Statistics` | 1 |
| `dap.quantile` | Quantile | `/Statistics` | 1 |
| `dap.outlier_detect` | Outlier Detect | `/Statistics` | 1 |
| `dap.data_quality` | Data Quality | `/Statistics` | 2 (1 — опционален) |
| `dap.hypothesis_test` | Hypothesis Test | `/Statistics` | 1 |
| `dap.linear_regression` | Linear Regression | `/Statistics` | 1 |
| `dap.resample` | Resample | `/Time Series` | 1 |
| `dap.rolling` | Rolling | `/Time Series` | 1 |
| `dap.diff` | Diff | `/Time Series` | 1 |
| `dap.date_range` | Date Range | `/Time Series` | — |
| `dap.excel_writer` | Excel Writer | `/IO/Write` | 1 (pass-through) |

| `dap.markdown_report` | Markdown Report | `/IO/Write` | 1 (pass-through) |
| `dap.chart` | Chart | `/Visualization` | 1 (pass-through) |
| `dap.chart_grid` | Chart Grid | `/Visualization` | 8 (pass-through порта 0) |

## 2. dap.csv_reader

Читает delimited-файл в таблицу. Схема берётся из заголовка с выводом типов или из
явного `columns`. Файл читается и загружается в DuckDB уже на run.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь к файлу |
| `delimiter` | string | `,` | ровно один символ; `"\t"` означает табуляцию |
| `header` | boolean | `true` | первая строка — имена колонок |
| `columns` | array<{name, type}> | — | явная схема; `type`: `Int64`, `Float64`, `Utf8`, `Boolean`, `Date32`, `Time64`, `Timestamp`, `Decimal128` |
| `emptyStringIsNull` | boolean | `false` | считать кавыченную пустую строку NULL |

Правила: пустое некавыченное поле — всегда NULL; `""` — пустая строка, если
`emptyStringIsNull` не включён. Кавыченная пустая строка в числовой/датовой
колонке даёт ошибку с подсказкой «set emptyStringIsNull=true or type the column
as Utf8». Дубли и пустые имена в заголовке отклоняются. При `header: true` каждая колонка
явной схемы должна найтись в заголовке; при `header: false` схема сопоставляется
по позиции.

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

Соединяет два входа по ключам: `inner`, `left` или `right`. Ключи сопоставляются
позиционно; `left_keys` и `right_keys` должны быть одной длины. При `merge_keys`
ключевые колонки сливаются в одну (`COALESCE(left, right)`, чтобы right-only строка
не теряла ключ). Колонка правого входа, имя которой уже есть в выходе, получает
суффикс `" (Right)"`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `how` | `inner` \| `left` \| `right` | `inner` | режим соединения |
| `left_keys` | array<string> | — | обязателен, непустой |
| `right_keys` | array<string> | — | обязателен, та же длина |
| `merge_keys` | boolean | `true` | слить ключи в одну колонку |

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

Считает парные корреляции двух и более числовых колонок в длинном формате: одна строка
на неупорядоченную пару `i < j` в порядке `columns`. `pearson` — `CORR` по сырым
значениям; `spearman` — `CORR` по `RANK()` каждой колонки, причём равные значения
делят ранг. Строки с NULL хотя бы в одной колонке пары исключены.
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | обязателен; не менее двух различных числовых колонок |
| `method` | `pearson` \| `spearman` | `pearson` | по умолчанию `pearson`; `pearson` — сырые значения, `spearman` — ранги |

Схема выхода: `column_a`, `column_b` (`String`), `correlation` (`Number (Float)`).
Число строк — `n·(n−1)/2`. Меньше двух колонок, дубль, неизвестная или нечисловая
колонка и неизвестный `method` — ошибка run; на вырожденной паре (меньше двух полных
строк или нулевой разброс) `CORR` даёт NULL.

```json
{
  "factory": "dap.correlation",
  "settings": {"columns": ["sepal_length", "sepal_width", "petal_length"], "method": "spearman"}
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
Порты: вход 0 — `Data`; выход 0 — `Data`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `columns` | array<string> | — | обязателен; непустой; только числовые колонки |
| `method` | `iqr` \| `zscore` | — | обязателен; способ границ |
| `threshold` | number | `1.5` для `iqr`, `3` для `zscore` | положительный множитель ширины границы |

`outlier` добавляется последней колонкой; если такое имя уже занято — ошибка run.
NULL в проверяемой колонке не помечает строку (`IS NOT NULL` в условии). При нулевом
`stddev` границы `zscore` вырождаются в среднее, и строки со значением, отличным от
среднего, помечаются. Дубль колонки игнорируется, пустой список, нечисловая или
неизвестная колонка, `threshold ≤ 0` — ошибка run.

```json
{
  "factory": "dap.outlier_detect",
  "settings": {"columns": ["sepal_length", "petal_length"], "method": "iqr", "threshold": 1.5}
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

Пишет детерминированный Markdown-отчёт и возвращает входную таблицу без изменений
(pass-through). Разделы рендерятся в порядке конфига: `table` — строки входа,
`profile` — статистика `dap.describe` (по колонкам), `text` — литеральный абзац,
`metrics` — счётчики строк/колонок и агрегаты по выбранным колонкам. Файл пишется
на run атомарно (временный файл рядом с целевым + переименование), родительские
каталоги создаются; вывод не содержит времени и прочих недетерминированных данных.
Порты: вход 0 — `Data`, выход 0 — `Data` (та же таблица).

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `file` | string | — | обязателен; путь к выходному `.md` |
| `title` | string | — | заголовок документа (`#`); пустой — без заголовка |
| `sections` | array | — | обязателен, непустой; порядок = порядок вывода |

Раздел (`sections[]`):

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `kind` | `table` \| `profile` \| `text` \| `metrics` | — | обязателен |
| `title` | string | — | заголовок раздела (`##`) |
| `columns` | string[] | — | для `table`/`profile` — какие колонки показывать (пусто = все); для `metrics` — какие колонки суммировать (пусто = только `rows`/`columns`) |
| `limit` | integer | 100 (table), 1000 (profile) | 1…10000; для `table` — строк, для `profile` — строк статистики |
| `text` | string | — | обязателен для `kind: text` |

Семантика:

- `table` — шапка из имён колонок и до `limit` строк; NULL рендерится пустой
  ячейкой, `|` → `\|`, переводы строк → `<br>`, `&`/`<`/`>` → HTML-сущности.
- `profile` — одна строка на колонку с колонками `dap.describe`
  (`column`, `type`, `count`, `missing`, `distinct`, `min`, `max`, `mean`, `stddev`).
- `metrics` — таблица `metric`/`value`: всегда `rows` и `columns`, затем для каждой
  выбранной колонки `<col>.count`, `<col>.missing` и для числовых —
  `<col>.mean`, `<col>.min`, `<col>.max`.
- Один preview на все `table`-разделы (по максимальному `limit`), поэтому отчёт
  читает вход один раз; ошибка конфига или пути видна на run (500).

```json
{
  "factory": "dap.markdown_report",
  "settings": {
    "file": "/data/report.md",
    "title": "Sales report",
    "sections": [
      {"kind": "table", "title": "Rows", "columns": ["id", "amount"], "limit": 50},
      {"kind": "metrics", "title": "Totals", "columns": ["amount"]},
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

Один статистический тест над входом: выход — ровно одна строка `test`,
`statistic`, `df`, `p_value`, `n`. p-value считается движком
(`engine/Distributions`, неполная бета/гамма), потому что в DuckDB нет
`erf`/`beta`; статистика и `df` считаются SQL-агрегатами.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `test` | `t_test_two_sample` \| `t_test_paired` \| `t_test_one_sample` \| `chi_square` \| `anova` | — | обязателен |
| `value` | string | — | числовая колонка для t-тестов и ANOVA, категориальная для `chi_square` |
| `group` | string | — | категориальная колонка для `t_test_two_sample`, `chi_square`, `anova` |
| `group_a` | string | — | для `t_test_two_sample`/`chi_square` — значение `group` (сравнивается как текст); для `t_test_paired` — первая числовая колонка |
| `group_b` | string | — | вторая группа или вторая числовая колонка (paired) |
| `mu` | number | `0` | нулевая гипотеза для `t_test_one_sample` |
| `alpha` | number | — | проверяется, что в `(0, 1)`; на выход не влияет |
| `alternative` | `two-sided` \| `less` \| `greater` | `two-sided` | хвост t-тестов; `chi_square`/`anova` всегда верхний |

Правила:

- `t_test_two_sample` — Welch (как `t.test` в R по умолчанию), `df` дробный; без
  `group_a`/`group_b` берутся ровно две различные группы колонки, иначе ошибка.
- `t_test_paired` — `group_a`/`group_b` задают имена двух числовых колонок; пары
  строятся по строкам, строки с NULL в любой из них исключаются.
- `t_test_one_sample` — `(mean(value) - mu) / (sd / sqrt(n))`, `df = n - 1`.
- `chi_square` — тест независимости по таблице сопряжённости `value` × `group`
  (NULL-категории исключаются), `df = (r-1)(c-1)`.
- `anova` — однофакторный: `df` — числитель `k - 1`, знаменатель `n - k`
  восстанавливается из `n` и числа групп; `n` — число строк.
- `n` — число использованных наблюдений (сумма по группам).
- Меньше двух наблюдений в выборке, меньше двух групп/категорий, ноль строк —
  ошибка конфигурации; при нулевой дисперсии `statistic` и `p_value` — NULL,
  `df` и `n` заполнены.

```json
{
  "factory": "dap.hypothesis_test",
  "settings": {
    "test": "t_test_two_sample",
    "value": "sepal_length",
    "group": "species",
    "group_a": "setosa",
    "group_b": "versicolor"
  }
}
```

## 50. dap.linear_regression

Парная линейная регрессия одной числовой колонки на ровно один числовой
предиктор, при необходимости — отдельно по группам. В режиме `coefficients` —
одна строка на терм (`intercept` и колонка `x`), в режиме `predict` — все
входные строки плюс `prediction` и `residual`.

| Ключ | Тип | По умолчанию | Примечание |
|---|---|---|---|
| `x` | array<string> | — | обязателен; ровно одна числовая колонка |
| `y` | string | — | обязателен; числовая колонка отклика |
| `group_by` | array<string> | — | необязательные колонки группировки: своя регрессия на группу |
| `mode` | `coefficients` \| `predict` | `coefficients` | — |
| `intercept` | boolean | `true` | `false` — модель `y = slope * x` без свободного члена |
| `output` | string | `prediction` | имя колонки предсказания в режиме `predict` |

Выход `coefficients`: `[group_by…]`, `term`, `coefficient`, `std_error`,
`t_statistic`, `p_value`, `r2`, `n`. Коэффициенты — из `regr_slope`,
`regr_intercept`, `regr_r2`, `regr_count`; стандартные ошибки и p-value — из
`engine/Distributions` (`t` с `df = n - 2`, для модели без свободного члена
`df = n - 1` и несцентрированный `r2 = 1 - SSE / sum(y^2)`).

Выход `predict`: входные колонки, затем `output` и `residual`
(`y - prediction`); при `group_by` коэффициенты считаются оконными агрегатами
по каждой группе. `prediction` NULL, если NULL предиктор; `residual` NULL, если
NULL отклик.

Правила: строки с NULL в `x` или `y` исключаются из подгонки; меньше двух таких
строк — ошибка; константный `x` даёт NULL-коэффициенты и NULL-`r2`; идеальная
подгонка (`std_error = 0`) даёт NULL `t_statistic`/`p_value`; `r2` для модели
без свободного члена может быть отрицательным. Режим `predict` ленивый (оконные
агрегаты в SQL), `coefficients` считает подгонку на run.

```json
{
  "factory": "dap.linear_regression",
  "settings": {
    "x": ["petal_length"],
    "y": "sepal_length",
    "group_by": ["species"],
    "mode": "coefficients"
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
Порты: входы 0 — `Data`, 1 — `Data 2`; выход 0 — `Data`.

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
таблицу сессии: остальной граф видит обычную таблицу, как после `dap.csv_reader`.
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
