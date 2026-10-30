# Corpus — 13 workflows для dual-run parity

Корпус описывает **реальные аналитические сценарии**, а не проверку API: фильтрация,
группировка, соединение, производные колонки, очистка NULL. Каждый workflow — JSON без
привязки к движку; `tests/parity.py` биндит логические узлы к oracle (KNIME) и к
`dap-native`. Почему биндинги именно такие — см. [docs/PARITY.md](../../docs/PARITY.md) §6.

```
wf-01-iris-filter-sort.json        IRIS   filter + 4-key sort
wf-02-iris-groupby.json            IRIS   mean sepal_length by species
wf-03-adult-filter-groupby.json    ADULT  2-predicate filter + count by education
wf-04-adult-string-columns.json    ADULT  upper(occupation) + column pruning + count
wf-05-iris-math.json               IRIS   native-only: petal_area formula + mean by species
wf-06-worldbank-missing.json       WB     fill NULL currency + count by income group
wf-07-adult-join.json              ADULT  aggregate joined back to every row
wf-08-air-quality-filter.json      AIR    numeric filter + 3-key sort
wf-09-empty-result.json            IRIS   filter matching nothing (schema stays)
wf-10-iris-pipeline.json           IRIS   native-only: 5-node chain with a formula
wf-n1-csv-source.json              native-only: csv_reader equivalence
wf-n2-excel-source.json            native-only: excel_reader equivalence
wf-n3-iris-renamer.json            IRIS   native-only: column_renamer + mean by species
data/                              фикстуры + datasets.json (схема, NULL, строки)
```

8 workflow исполняются на oracle (`dual-run`); 5 помечены `native-only` с причиной
`oracle_skip_reason` — для них baseline хранит `status: skipped`, а проверки
(`expect`, `expect_profile`, `equivalent`) исполняются на native: `wf-05`, `wf-10`, `wf-n1`,
`wf-n2` — с Фазы 2, `wf-n3-iris-renamer` — с Фазы 3.

## Схема workflow

```json
{
  "id": "wf-01-iris-filter-sort",
  "title": "одна строка для отчёта",
  "rationale": "зачем этот сценарий в корпусе",
  "kind": "dual-run | native-only",
  "nodes": [
    {"id": "src",    "type": "table_source", "config": {"dataset": "IRIS"}},
    {"id": "filter", "type": "row_filter",   "config": {"where": [{"column": "species", "op": "eq", "value": "setosa"}]}},
    {"id": "sort",   "type": "sorter",       "config": {"keys": [{"column": "sepal_length", "direction": "desc"}]}}
  ],
  "edges": [{"from": "src", "to": "filter"}, {"from": "filter", "to": "sort"}],
  "checks": [
    {"node": "sort", "kind": "table", "limit": 50, "order": "ordered"},
    {"node": "sort", "kind": "profile"}
  ]
}
```

- `edges[].fromPort` / `toPort` — индексы входных портов (у Joiner правый вход = 1),
  по умолчанию 0.
- `checks[]`: `table` (`limit`, `order`), `profile`, `equivalent` (`workflow` + `node_ref`,
  native-only), `expect` / `expect_profile` (литеральные эталоны, native-only).
- `order: "ordered"` — только там, где порядок задан Sorter'ом и ключи образуют полный
  порядок; иначе `unordered`.

## Словарь логических узлов

| type | config | ограничения корпуса |
|---|---|---|
| `table_source` | `{"dataset": "<имя>"}` | shipped-датасет KNIME |
| `csv_reader` / `excel_reader` | `{"dataset": "<имя>"}` | файл и схема — в `data/datasets.json`; native-only |
| `row_filter` | `{"logic": "and"\|"or", "where": [{"column", "op", "value"}]}`, op: `eq ne lt le gt ge is_null not_null` | `ne` в oracle не выражается |
| `sorter` | `{"keys": [{"column", "direction": "asc"\|"desc"}]}` | — |
| `group_by` | `{"keys": [<колонка>], "aggregations": [{"column", "fn": "mean"\|"count"\|"sum"}]}` | **ровно один** ключ и один агрегат (oracle) |
| `joiner` | `{"how": "inner"\|"left", "left_keys": [], "right_keys": [], "merge_keys": bool}` | в корпусе только `inner` |
| `column_filter` | `{"exclude": []}` | `include` не выражается в oracle |
| `column_renamer` | `{"renames": [{"from": "<колонка>", "to": "<новое имя>"}]}` | native-only: у oracle нет биндинга |
| `missing_value` | `{"rules": [{"column", "method": "constant", "value": <литерал>}]}` | один столбец, только `constant` |
| `string_manipulation` | `{"column", "op": "upper", "mode": "replace"}` | в корпусе только `upper` |
| `math_formula` | `{"outputs": [{"name", "expression": "<арифметика над числовыми колонками>"}]}` | native-only (oracle-блокер) |

Имена агрегатных колонок native формирует как KNIME: `Mean(<column>)`, `Count(<column>)`,
`Sum(<column>)` — см. docs/PARITY.md §6.2.

## Данные

`data/*.csv` — точный экспорт shipped-датасетов KNIME (`Example Data Reader`) через
`GET /v1/.../table`, сделанный один раз с oracle. `data/datasets.json` фиксирует:

```json
{"IRIS": {"file": "iris.csv", "rows": 150, "delimiter": ",", "header": true,
          "columns": [{"name": "sepal_length", "knime": "Number (Float)", "arrow": "Float64", "missing": 0}]}}
```

Почему экспорт, а не прямая ссылка на KNIME: oracle не умеет исполнять файловые ридеры,
а native не должен читать `.table`-формат KNIME. Даты/время экспортированы строками ISO
(`2004-03-10`, `18:00`) и в native читаются явными типами `Date32`/`Time64`.

`data/iris.xlsx` — та же таблица IRIS в Excel для native-only проверки `excel_reader`.

## Проверка

```bash
python3 tests/parity.py --list
python3 tests/parity.py --validate
python3 tests/parity.py --engine oracle --check-baseline
```
