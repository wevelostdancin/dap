# DAP tests

Все проверки — нативные: KNIME не нужен, oracle не нужен, сеть не нужна.
Только Python 3 stdlib и собранный `engine-native/target/dap-native.jar`.

## Быстрый старт

    make check                 # JUnit + goldens + validate + MCP launch + MCP e2e + lint

По отдельности:

    make test-native           # JUnit-набор движка
    make goldens               # golden suite: сам поднимает движок на :7811 и гасит его
    python3 tests/run_goldens.py --keep   # оставить движок на :7811 для отладки
    python3 tests/native_smoke.py --base http://127.0.0.1:7800
    python3 tests/mcp_e2e.py   # MCP: create -> add_node -> connect -> run -> table -> delete

Резидентный сервис на `127.0.0.1:7800` не трогается: golden suite работает
на своём порту и в своём каталоге (`/tmp/dap-goldens`).

## Golden suite

`tests/goldens-native/` — эталонные сценарии:

    workflows/      13 workflow: описание графа + checks
    data/           фикстуры (CSV/XLSX) и datasets.json (схема, типы)
    expected.json   замороженные наблюдения (таблицы и профили)
    runner.py       прогон через /v1 и сравнение

Каждый workflow — JSON без привязки к реализации: логические узлы
(`table_source`, `row_filter`, `group_by`, ...), рёбра и проверки. Runner
разворачивает `dataset`-shorthand в реальный путь и биндит узел в `dap.*`.

Проверки: `table` (limit, order), `profile`, а для нативных сценариев —
`expect`/`expect_profile` (литеральные эталоны) и `equivalent` (сравнение
двух путей внутри native).

Правила сравнения — [docs/QUALITY.md](../docs/QUALITY.md): float 1e-9, точные
int/NULL, `""` — значение, ordered/unordered.

## Файлы

    tests/
      dap_client.py        HTTP-клиент /v1 (stdlib)
      run_goldens.py       поднять движок на :7811, прогнать runner.py, погасить
      goldens-native/      workflow, фикстуры, expected.json, runner.py
      native_smoke.py      black-box smoke: health, каталог, 5 узлов, ошибки
      mcp_e2e.py           MCP e2e поверх stdio
      test_mcp_launch.py   запуск/остановка движка адаптером
      bench.py             бенчмарк (cold start, 10M CSV, RAM, ветви)

## Добавить golden

1. Положить фикстуру в `goldens-native/data/` и запись в `datasets.json`.
2. Добавить `workflows/wf-*.json` с checks.
3. Для baseline-сценария — добавить наблюдение в `expected.json` вручную
   (CLI-флага `--record` нет: эталон фиксируется осознанно, а не автозаписью).
4. `make goldens` — зелёный.

Новый узел без golden и unit-теста не считается готовым.
