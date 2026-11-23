# Рецепты DAP

В каталоге лежат восемь готовых пайплайнов формата `dap-native-workflow/1`.
Каждый файл содержит блок `recipe`: объявление входа `dataset`,
параметры `params` и выходной узел `output`. Рецепты читаются адаптером
из `$DAP_HOME/recipes/`.

## Таблица рецептов

| Рецепт | Демо-данные | Узлы | Параметры |
|---|---|---|---|
| `data_quality` | `examples/data/quality.csv` | parquet_reader → data_quality → markdown_report | `dataset`, `report` |
| `cohort_analysis` | `examples/data/cohort.csv` | parquet_reader → sql_query → markdown_report | `dataset`, `report` |
| `ab_test` | `examples/data/ab_test.csv` | parquet_reader → ab_test → markdown_report | `dataset`, `report` |
| `time_series` | `examples/data/timeseries.csv` | parquet_reader → resample → type_cast → rolling → time_series → markdown_report | `dataset`, `report` |
| `segmentation` | `examples/data/segmentation.csv` | parquet_reader → cluster → group_by → markdown_report | `dataset`, `report` |
| `regression` | `examples/data/regression.csv` | parquet_reader → linear_regression → markdown_report | `dataset`, `report` |
| `funnel` | `examples/data/funnel.csv` | parquet_reader → sql_query → markdown_report | `dataset`, `report` |
| `exec_report` | `examples/data/sales.csv` | parquet_reader → group_by → chart → markdown_report | `dataset`, `report`, `chart` |

`dataset` подставляется в `settings.file` узла `src` (`dap.parquet_reader`).
`report` подставляется в `settings.file` узла `report`.
`chart` (только в `exec_report`) подставляется в `settings.file` узла `chart`;
если параметр не передан, используется имя `chart.svg` из файла рецепта.

## Запуск

Сначала превратите CSV в Parquet-кэш и передайте путь как `dataset`:

```text
dap_ingest(file="examples/data/sales.csv") -> path
dap_recipe_run(
  name="exec_report",
  dataset=<path из dap_ingest>,
  params={ "report": "/tmp/exec_report.md", "chart": "/tmp/chart.svg" }
)
```

`dap_recipe_run` возвращает `table` выходного узла и `applied` — фактически
подставленные значения. Для остальных рецептов достаточно `params={"report": ...}`.
Список рецептов: `dap_recipe_list`; содержимое: `resources/read` на
`dap://recipes/<name>`.

Полный прогон всех восьми рецептов на демо-данных:

```bash
python3 tests/recipe_library_e2e.py --timeout 600
```

## Адаптация колонок

Рецепт привязан к именам колонок демо-набора. Если в ваших данных другие имена,
есть два варианта.

1. Переименуйте колонки до запуска: `dap_sql` с `SELECT old AS expected, ...`,
   затем `dap_ingest` результата и обычный `dap_recipe_run`.
2. Соберите эквивалент через `dap_workflow_build` / `dap_add_node`, заменив
   имена в `settings` узлов. Типы узлов и допустимые ключи настроек смотрите в
   `engine-native/src/main/resources/specs/dap.*.json` или через
   `dap_node_dialog_schema`.

Частые замены:

- `data_quality`: `checks[].column` (`id`, `amount`), границы `min`/`max`.
- `cohort_analysis`: `user_id`, `signup_date`, `activity_date` в SQL узла `cohorts`.
- `ab_test`: `group`, `value`.
- `time_series`: `timeColumn` и колонка агрегата в `resample`, `column`/`orderBy`
  в `rolling`, `time`/`value` в `forecast`. Вход `resample` должен быть
  `Timestamp`; узел `cast` переводит сгенерированный bucket в `Date32`, потому
  что `dap.time_series` принимает только число или дату. Для другого формата
  дат замените тип в `cast` или уберите узел.
- `segmentation`: `columns` кластера и `keys`/`aggregations` группировки;
  имя колонки метки — `cluster`.
- `regression`: `x`, `y`.
- `funnel`: `step`, `user_id` в SQL узла `steps`; эталонный шаг — `view`.
- `exec_report`: `keys`, `aggregations`, затем `x` и `y` узла `chart` —
  имя `y` должно совпадать с именем агрегата (`Sum(amount)`).

Рецепты не содержат сетевых вызовов и секретов. Пути в закоммиченных JSON —
заглушки; реальные пути приходят из `dataset` и `params`.