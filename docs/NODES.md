# NODES — каталог узлов

Каталог нативного движка: 16 узлов `dap.*`. Источник истины — JSON-спеки
`engine-native/src/main/resources/specs/*.json`, список — `specs/index.txt`;
`NodeRegistry` читает их из classpath и падает при отсутствующем или дублирующем
спеке. Все 16 узлов имеют `available: true` и `tier: core`.

## 1. Общие правила

- `id` узла — `dap.<type>`; он же передаётся в `factory` при
  `POST /v1/workflows/{id}/nodes`.
- Настройки — плоский JSON по ключам `configSchema`. Неизвестный ключ даёт 400
  сразу; значения проверяются исполнителем при run (ошибка значения — 500).
- `required` в `configSchema` влияет на `optional` в `/dialog`, а не на приём
  запроса: фактическую обязательность проверяет исполнитель.
- У всех узлов один табличный выход (порт 0). У источников нет входов, у `dap.joiner`
  два входа (0 — Left, 1 — Right), у остальных один вход (0).
- Писатели (`dap.csv_writer`, `dap.parquet_writer`, `dap.json_writer`) — Sink:
  один вход и один выход, входная таблица возвращается неизменной (pass-through),
  поэтому граф можно продолжать за писателем. Файл пишется на run, на
  job-соединении; родительские каталоги создаются.
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
`emptyStringIsNull` не включён. Дубли и пустые имена в заголовке отклоняются. При `header: true` каждая колонка
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

## 18. Порядок для агента

1. `POST /v1/datasets` (инструмент `dap_ingest`) — один раз привести CSV/Excel/JSON
   к Parquet-кэшу, если файл читается многократно.
2. `GET /v1/nodes?q=…` — найти `id`.
3. `GET /v1/nodes/{id}/dialog` — поля, типы, умолчания, допустимые значения.
4. `POST /v1/workflows/{id}/nodes` с `factory` и `settings`.
5. `POST /v1/workflows/{id}/connect` — связи (порты данных с 0).
6. `POST /v1/workflows/{id}/run` (или `/jobs`) → `GET .../table`, `.../profile`.

Примеры выше — тело запроса `POST /v1/workflows/{id}/nodes`.
