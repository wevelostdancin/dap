# DAP как готовый MCP-сервис для агентов

**Статус:** на ревью · **Дата:** 2026-11-15 · **Ветка:** phase3-native · **Целевая версия:** 1.1.0
**Связанные документы:** [README.md](../../../README.md), [ARCHITECTURE.md](../../ARCHITECTURE.md), [QUALITY.md](../../QUALITY.md), [ROADMAP.md](../../ROADMAP.md), [MCP.md](../../MCP.md).

## 1. Контекст

DAP 1.0.0 — локальный аналитический движок (Java 21, DuckDB 1.5.6, Arrow, Javalin) с HTTP `/v1` (27 операций) и stdio MCP-адаптером (22 инструмента, 44 узла). Он работает и проверен: `make check` зелёный (401 JUnit-тест, 15/15 goldens, MCP e2e), cold start 459 мс, 1M строк ingest 1.4 с.

Но это не «готовый сервис»: подключение к клиенту делается вручную, покрытие типовых аналитических задач ≈68% (47 из 69), а слой гарантий не закрыт — это P4 из ROADMAP. Аудит кода добавил три P0-находки: таблицы сессии не удаляются при удалении workflow (`dropTables` без вызовов), у `/table` и `/profile` нет таймаута (случайный cross-join вешает движок), jobs и сессии не эвиктятся.

Цель: превратить DAP в MCP-сервис, который Hermes Agent и DSH подключают одной командой и используют для 95% типовых аналитических пайплайнов с гарантированным детерминированным ответом; агент при этом может адаптировать пайплайн под задачу.

## 2. Критерии успеха

| # | Критерий | Как проверяется |
|---|---|---|
| S1 | Покрытие ≥67 из 69 задач таксономии (§6); 68 при доступной сети | `make coverage`: 69 сценариев, каждый через `/v1`, PASS/FAIL |
| S2 | Подключение одной командой для Hermes и DSH | `make install-e2e`: временный HOME, install, разбор конфига клиента, `tools/list` ≥23, smoke |
| S3 | Каждый вызов ограничен по времени и отменяем | fault-injection: timeout, cancel, рестарт движка, cross-join |
| S4 | Обнаружимые искажения данных не молчат | тесты типизации: `""`, NULL, mixed types; ошибка с hint там, где искажение можно обнаружить |
| S5 | Память ограничена | soak: 200 workflow × 10 сессий, heap в пределах лимита, эвикция и удаление таблиц работают |
| S6 | Детерминизм | goldens + coverage-сьют: повторный прогон даёт тот же результат |
| S7 | Адаптивность | сырой CSV → ответ за ≤3 вызова агента без ручной сборки графа (sql_query/рецепт) |
| S8 | Нет регрессий | `make check` зелёный на каждом этапе |

## 3. Не-цели

- Облако, multi-tenant, auth, HA: сервис локальный, один пользователь, loopback.
- GUI и паритет с 832 узлами KNIME.
- ML-обучение, прогнозирование временных рядов, глубокий NLP — только отдельным запросом.
- Docker как обязательный путь запуска; демон в ежедневном пути.
- Новые Maven-зависимости: jar 44.2 МиБ при лимите 50 МиБ (6 МиБ запаса), поэтому всё считается на DuckDB и стандартной библиотеке Java.

## 4. Текущее состояние (факты)

- 44 узла, 27 операций `/v1`, 22 инструмента MCP; jar 44.2 МиБ; JDK 21.
- Cold start 459 мс; idle-shutdown по `DAP_IDLE_TIMEOUT_SEC`; отмена через `DELETE /v1/jobs/{id}`.
- 401 JUnit-тест, 15 goldens (1563/1563 ячеек), MCP e2e, lint 0 ошибок.
- Покрытие таксономии: 47/69 (68%).
- Расширения DuckDB: загружены `core_functions`, `icu`, `json`, `parquet`; `sqlite_scanner`, `postgres_scanner`, `httpfs` проверены — официальные подписанные сборки 1.5.6, ставятся в отдельный каталог, после установки работают офлайн; SQLite attach и HTTP-чтение по HTTPS подтверждены.
- Дефекты: узел-призрак при невалидном `add_node`; `""` в числовой колонке делает её String или ломает ingest с явной схемой; лимит workflow отдаёт 500; `/table` до run отдаёт 400 и не отличает непрогнанный узел от упавшего; нецелый query-параметр отдаёт 500; сессии и jobs не эвиктятся; таблицы сессии не удаляются при удалении workflow; у `/table`/`/profile` нет таймаута; в `~/.dsh` нет записи DAP.

## 5. Архитектура

Компоненты (ничего не переписываем, добавляем слои):

1. **Движок** — Java-процесс: узлы, DuckDB, `/v1`. Добавляются узлы, таймауты чтения, эвикция и контракт ошибок.
2. **MCP-адаптер** — Python stdio, JSON-RPC 2.0. Добавляются resources, prompts, инструменты рецептов и `dap_sql`.
3. **CLI `dap-mcp`** — `run` (stdio, по умолчанию), `install`, `doctor`, `version`. Ставит и проверяет подключение к клиенту.
4. **Дистрибутив** — `make dist` → `dist/`: jar, адаптер, CLI, версия, checksum.
5. **Task suite** — 69 исполняемых сценариев покрытия в `tests/coverage/`.
6. **Рецепты** — сохранённые пайплайны (формат `dap-native-workflow/1` + метаданные) и библиотека `~/.dap/recipes/`.

Поток: клиент (Hermes/DSH) → stdio MCP → HTTP `127.0.0.1` → движок. Без демона: адаптер поднимает движок при первом вызове, idle-timeout гасит его.

## 6. Модель покрытия

### 6.1 Правило

Задача считается покрытой, если в `tests/coverage/` есть сценарий, который исполняется end-to-end через `/v1` и проверяет результат (строки, колонки, значения). Сценарий может использовать один узел или документированный рецепт (композицию узлов). Покрытие = PASS / 69.

Расширения DuckDB `sqlite_scanner`, `postgres_scanner` и `httpfs` проверены: официальные подписанные сборки 1.5.6, установка ~2 с, кэш ~74 МБ, после установки работают офлайн. Поэтому A6 покрывается через SQLite офлайн, A7 — нативным attach; A8 (HTTP) требует сети в момент чтения. Офлайн-цель — **67/69**, при доступной сети — **68/69**; единственный гарантированный пропуск — F6 (прогноз, не-цель).

### 6.2 Таксономия (69 задач)

| ID | Задача | Механизм | Сейчас |
|---|---|---|---|
| A1 | Чтение CSV | `dap.csv_reader` | ✅ |
| A2 | Чтение Excel | `dap.excel_reader` | ✅ |
| A3 | Чтение JSON/JSONL | `dap.json_reader` | ✅ |
| A4 | Чтение Parquet | `dap.parquet_reader` | ✅ |
| A5 | Сжатый CSV (gzip) | `dap.csv_reader.compression` | ❌ |
| A6 | Чтение внешней SQL БД | `dap.db_reader` (SQLite офлайн; Postgres/MySQL — сервер) | ❌ |
| A7 | Чтение файла DuckDB | `dap.db_reader` (attach, офлайн) | ❌ |
| A8 | Чтение HTTP/API | рецепт `sql_query` (`read_csv_auto` через httpfs, сеть) | ❌ |
| A9 | Список файлов каталога | `dap.file_list` | ❌ |
| B1 | Фильтр строк | `dap.row_filter` | ✅ |
| B2 | Выбор/переименование/порядок колонок | `column_filter`/`column_renamer`/`column_resorter` | ✅ |
| B3 | Приведение типов | `dap.type_cast` | ✅ |
| B4 | Пропуски | `dap.missing_value` | ✅ |
| B5 | Дубликаты | `dap.duplicate_filter` | ✅ |
| B6 | Замена значений | `dap.value_replace` | ✅ |
| B7 | Фильтр выбросов | `dap.outlier_filter` | ✅ |
| B8 | Строки: case/trim/replace/concat | `dap.string_manipulation` | ✅ |
| B9 | Regex-извлечение | рецепт `sql_query` (`regexp_extract`) | ❌ |
| B10 | Split колонки | рецепт `sql_query` (`string_split`+`unnest`) | ❌ |
| C1 | Сортировка | `dap.sorter` | ✅ |
| C2 | Формула/производная колонка | `dap.math_formula` | ✅ |
| C3 | Бизнес-правила | `dap.rule_engine` | ✅ |
| C4 | Бины | `dap.binner` | ✅ |
| C5 | Нормализация | `dap.normalizer` | ✅ |
| C6 | Кодирование | `dap.encoder` | ✅ |
| C7 | Выборка | `dap.sample_rows` | ✅ |
| C8 | Join inner/left/right | `dap.joiner` | ✅ |
| C9 | Join full/semi/anti | `dap.joiner.how` | ❌ |
| C10 | Объединение таблиц | `dap.concatenate` | ✅ |
| C11 | Pivot | `dap.pivot` | ✅ |
| C12 | Unpivot | `dap.unpivot` | ✅ |
| C13 | Window (rank/lag/lead/agg) | `dap.window` | ✅ |
| C14 | Top-N по группе | `dap.top_n_per_group` | ✅ |
| C15 | Train/test split | рецепт `sql_query` (`random()` + два `row_filter`) | ❌ |
| C16 | Rollup/cube | рецепт `sql_query` | ❌ |
| D1 | Group by (mean/sum/count/min/max) | `dap.group_by` | ✅ |
| D2 | Count distinct | `dap.group_by` (fn) | ❌ |
| D3 | Median/percentile-агрегат | `dap.group_by` (fn) | ❌ |
| D4 | Построчные агрегаты | `dap.row_aggregator` | ✅ |
| D5 | Уникальные строки | `dap.duplicate_filter` | ✅ |
| D6 | HAVING (фильтр после агрегации) | рецепт `group_by` → `row_filter` | ✅ |
| E1 | Describe/сводка | `dap.describe` | ✅ |
| E2 | Частоты значений | `dap.value_counts` | ✅ |
| E3 | Корреляция | `dap.correlation` | ✅ |
| E4 | Ковариация | рецепт `sql_query` (`covar_samp`) | ❌ |
| E5 | Гистограмма | `dap.histogram` | ✅ |
| E6 | Квантили | `dap.quantile` | ✅ |
| E7 | Детект выбросов | `dap.outlier_detect` | ✅ |
| E8 | t-тест | `dap.hypothesis_test` | ❌ |
| E9 | χ²/ANOVA | `dap.hypothesis_test` | ❌ |
| E10 | Линейная регрессия | `dap.linear_regression` | ❌ |
| E11 | Нормальность (skew/kurtosis) | рецепт `sql_query` | ❌ |
| F1 | Resample | `dap.resample` | ✅ |
| F2 | Rolling-окно | `dap.rolling` | ✅ |
| F3 | Diff | `dap.diff` | ✅ |
| F4 | Date range | `dap.date_range` | ✅ |
| F5 | Date parse/format/extract | `dap.date_time` | ✅ |
| F6 | Сезонность/прогноз | не-цель | ❌ |
| G1 | Конкатенация текста | `dap.string_manipulation` | ✅ |
| H1 | Запись CSV | `dap.csv_writer` | ✅ |
| H2 | Запись Excel | `dap.excel_writer` | ✅ |
| H3 | Запись JSON | `dap.json_writer` | ✅ |
| H4 | Запись Parquet | `dap.parquet_writer` | ✅ |
| H5 | Markdown-отчёт | `dap.markdown_report` | ❌ |
| H6 | График SVG/PNG | `dap.chart` | ❌ |
| I1 | Произвольный SQL | `dap.sql_query` | ❌ |
| I2 | Сохранённый рецепт | `save`/`load` | ✅ |
| J1 | Профиль таблицы | `/profile` | ✅ |
| J2 | Проверки качества данных | `dap.data_quality` | ❌ |

Итог: 47 покрыто, 22 нет; цель — 66/69 офлайн.

### 6.3 Новые узлы, расширения и рецепты

**Новые узлы (8):** `dap.sql_query`, `dap.db_reader` (DuckDB-файл read-only attach; SQLite офлайн через расширение; Postgres/MySQL — расширение и сервер), `dap.file_list`, `dap.hypothesis_test`, `dap.linear_regression`, `dap.markdown_report`, `dap.chart`, `dap.data_quality`.

**Расширения (3):** `csv_reader.compression` (auto/none/gzip; zstd отложен — нужен zstd-jni), `joiner.how` (+full/semi/anti), `group_by.fn` (+count_distinct/median/percentile с параметром `p`).

**Рецепты вместо узлов (8):** regex_extract, split_column, covariance, rollup/cube, normality, HAVING, train/test split, HTTP-чтение (`read_csv_auto`/`read_json_auto` через httpfs) — каждая задача выражается SQL-выражением; отдельные узлы не добавляем (YAGNI), но каждая получает coverage-сценарий и документированный рецепт.

Правило репозитория сохраняется: spec + executor + ≥3 unit-теста + golden + строка в NODES.md; правка `/v1` — openapi + API.md.

## 7. Подпроект 1 — Connect

`dap-mcp` — CLI на Python (stdlib), подкоманды:

- `run` — stdio MCP-сервер (текущее поведение `dap_mcp.py`), по умолчанию.
- `install --client hermes|dsh [--dry-run] [--force]` — находит конфиг клиента, делает бэкап, добавляет stdio-запись `dap`, идемпотентно; при существующей записи обновляет её.
- `doctor [--client ...]` — проверяет Python, jar, health, `tools/list` ≥23, пробный CSV → таблица, разбор конфига, свободный порт; печатает PASS/FAIL по каждому пункту.
- `version` — версия адаптера, движка и контракта.

**Hermes** (проверено по документации и исходникам): `~/.hermes/config.yaml`, ключ `mcp_servers.dap`; поля `command`, `args`, `env`, `cwd`, `enabled`, `timeout` (по умолчанию 300 с), `connect_timeout` (60 с), `lazy`, `tools.include/exclude`, `tools.resources`, `tools.prompts`. `install` пишет `command: python3`, `args: [<abs>/adapters/mcp/dap_mcp.py]`, `env: {DAP_HOME, JAVA_HOME, DAP_PORT, DAP_HEAP}`, `tools: {resources: true, prompts: true}`; окружение Hermes не наследует shell, поэтому все пути — абсолютные. Проверка: `hermes mcp test dap` (код 0) и `tools/list`; если CLI Hermes недоступен (например, install state не writable), `doctor` делает прямой stdio-прогон адаптера (`initialize` + `tools/list`) и не зависит от CLI.

**DSH** (проверено по asar и профилю): конфиг — `~/.dsh/profiles/desktop/cordis.patch.yml`, YAML-массив патчей профиля. Запись — элемент `id: mcp-manager`, `config.servers.dap`: `transport: stdio`, `command`, `args`, `env`, `cwd`, `enabled`, `startupTimeoutMs`, `toolCallTimeoutMs`, `reconnect`. Ключ `dap` — имя сервера, инструменты видны как `mcp__dap__*`. Патч заменяет весь `servers`-map, поэтому install мержит: если `mcp-manager` в патче уже есть — обновляет только `servers.dap`, сохраняя остальные ключи; если записи нет (текущий профиль) — добавляет элемент в конец массива, сохраняя комментарии и другие записи; если структура нестандартная (flow-style, якоря, вложенный `servers` в другой записи) — печатает фрагмент и завершается с кодом 2. Ручные правки применяются после перезапуска DSH; `doctor` проверяет сервер напрямую через stdio (`initialize` + `tools/list`). Окружение дочернего процесса DSH вычищается, поэтому `command` — абсолютный путь к Python, а `JAVA_HOME`, `DAP_HOME`, `DAP_PORT` передаются явно.

YAML-редактор: минимальный блочный, два режима — Hermes: вставка/обновление блока под `mcp_servers:`; DSH: запись `- id: mcp-manager` в массив патчей. Бэкап, `--dry-run`, проверка разбора после записи, откат при ошибке. PyYAML в окружении отсутствует, поэтому редактор строчный; нестандартный YAML → фрагмент и код 2.

Дистрибутив: `make dist` собирает `dist/` (jar, `adapters/mcp`, `scripts/dap-mcp`, VERSION, sha256). Установка — распаковка в `~/.dap/`; `DAP_HOME` указывает на неё.

Приёмка: `make install-e2e` на временном HOME: install → разбор конфига клиента → запуск адаптера → `tools/list` ≥23 → smoke-пайплайн → PASS.

## 8. Подпроект 2 — Coverage

Семантика новых узлов (детали config — в NODES.md при реализации):

- `sql_query`: read-only SELECT/WITH по таблицам — выходам узлов workflow (id узла как имя таблицы в кавычках) и загруженным датасетам; схема — `SELECT * FROM (<sql>) LIMIT 0` + `ResultSetMetaData`; запрет DDL/DML/ATTACH/COPY/INSTALL/LOAD/PRAGMA/SET и нескольких стейтментов; лимит строк (по умолчанию 10 000, максимум 1 000 000) и таймаут; LIST/STRUCT/MAP/TIMESTAMPTZ отклоняются с именем колонки.
- `db_reader`: `driver[duckdb|sqlite|postgres|mysql]`, `path` или `url`, `table` или `query`, `read_only: true`, `user`/`password`. DuckDB-файл — нативный `ATTACH ... (READ_ONLY)`; SQLite — `ATTACH ... (TYPE SQLITE, READ_ONLY)` через расширение; Postgres/MySQL — расширение и сервер. Расширения ставятся один раз в `DAP_DUCKDB_EXTENSION_DIR` (`make extensions`), после чего работают офлайн; при отсутствии расширения — ошибка с hint. Учётные данные не попадают в `workflow.json` и логи (маскируются).
- `file_list`: `directory`, `pattern`, `recursive`, `include_hidden`, `sort`, `limit`; Java NIO `Files.walk` + `BasicFileAttributes` (у `glob()` нет size/mtime) → path/name/extension/size/modified/is_directory.
- `hypothesis_test`: `test[t_test_two_sample|paired|one_sample|chi_square|anova]`, `value`, `group`, `group_a/b`, `mu`, `alpha`, `alternative`; выход — statistic, df, p_value, n. В DuckDB нет `erf`/`beta`, поэтому p-value считается своей реализацией неполной беты (~100 строк, без зависимости) и сверяется с эталонными значениями.
- `linear_regression`: коэффициенты через `regr_slope/intercept/r2/count`; стандартные ошибки и p-value — на той же математике, что и `hypothesis_test`; `mode[coefficients|predict]`.
- `markdown_report`: `file`, `title`, `sections[{kind: table|profile|text|metrics, title, columns, limit}]`; pass-through входа, детерминированный вывод, экранирование `|` и переводов строк.
- `chart`: `file`, `format[svg|png]`, `type[bar|line|scatter|histogram]`, `x`, `y[]`, `series`, `title`, `width/height`, `limit`; SVG — ручной XML (детерминирован, годится для golden), PNG — Java2D + `ImageIO` с `-Djava.awt.headless=true` (побайтово недетерминирован, golden только для SVG).
- `data_quality`: `checks[{kind: not_null|unique|range|pattern|referential|row_count|allowed_values, column, min, max, regex, values, severity}]`, `fail_on[error|warn|never]`, `output[long|summary]`; один совокупный проход, а не N сканов.

Рецепты (6) документируются в NODES.md и получают coverage-сценарии: regex_extract, split_column, covariance, rollup, normality, HAVING.

## 9. Подпроект 3 — Guarantee

1. **Таймауты чтения.** `/table` и `/profile` исполняются под `DAP_QUERY_TIMEOUT_SEC` (по умолчанию 60); по истечении соединение закрывается → 408 `query_timeout`, следующий вызов работает. Закрывает P0 «случайный cross-join вешает движок».
2. **Освобождение таблиц.** При удалении workflow вызывается `dropTables` для таблиц его reader-узлов (сейчас метод без вызовов); регистрация имён — в `loadInto`.
3. **Эвикция.** TTL сессии 1800 с простоя; максимум 64 сессии (LRU); cap терминальных jobs 200 на сессию (удаляются старейшие, активные не трогаются); `GET /v1/sessions` и `DELETE /v1/sessions/{id}`.
4. **Коды.** Лимит workflow → 409 `workflow_limit` (не 500); `/table`/`/profile` до run → 409 `not_materialized`, упавший узел → 409 `node_failed` с сообщением узла; неизвестный узел → 404 `unknown_node` во всех маршрутах; нецелый query-параметр → 400 `invalid_query_param`; таймаут sync-run → 408 (как сейчас).
5. **Ошибки.** Тело ошибки расширяется аддитивно: `{"error": "...", "code": "...", "hint": "..."}`; обновляются openapi.yaml и API.md; старые поля не ломаются. Адаптер передаёт `code` и `hint` в JSON-RPC error.data.
6. **Типизация.** `emptyStringIsNull` остаётся `false` (контракт не ломаем), но обе ветки ловушки `""` получают hint: при явной схеме — «quoted empty string; set emptyStringIsNull=true or type the column as Utf8», при `group_by`/агрегации по String — тот же hint. Описание `csv_reader` дополняется.
7. **Узел-призрак.** `add_node` валидирует settings до добавления узла (или откатывает при 400); тест на отсутствие узла после ошибки.
8. **Рестарт движка.** `/v1/health` отдаёт `generation`; адаптер при смене generation добавляет к следующей ошибке «engine restarted; workflows lost» вместо молчаливых 404.
9. **Гонки.** `openRead`/`interruptReads` — под одним локом (или generation-счётчик); `workflowLocks` очищается при удалении workflow; `Dag.close()` — bounded `shutdownNow()` + `awaitTermination(5s)` вместо блокирующего try-with-resources.
10. **Тесты.** Fault-injection: отмена посреди ingest, timeout, отсутствующий файл, битый CSV, cross-join под таймаутом, 100 сессий, рестарт движка. Soak: 200 × create→run→table→delete, heap в пределах лимита, `sessionCount()==1`, jobs ≤ cap.
11. **Расширения.** `DAP_DUCKDB_EXTENSION_DIR` (по умолчанию `$DAP_HOME/build/duckdb-ext`), `make extensions` ставит sqlite/postgres/httpfs, `doctor` показывает набор; движок задаёт `extension_directory` и не полагается на autoinstall (сеть — только при явной установке).

## 10. Подпроект 4 — Adaptability

- `sql_query` (узел) + инструмент `dap_sql` — главный рычаг адаптации.
- Рецепты: формат `workflow.json` + `recipe` (name, description, tags, параметры датасета). Библиотека `~/.dap/recipes/`; инструменты `dap_recipe_list`, `dap_recipe_save`, `dap_recipe_run`; MCP resources `dap://catalog`, `dap://recipes/{name}`; prompts `analyze_csv`, `quality_check`, `profile_report`.
- Сценарий приёмки: агент получает CSV, делает ingest, выбирает рецепт или пишет SQL, получает таблицу и markdown-отчёт — 3 вызова, без ручной сборки графа.

## 11. Порядок и параллельность

| Этап | Worktree | Содержание | Зависит от |
|---|---|---|---|
| 0 | — | спека, план | — |
| 1 | `p5/guarantee` | таймауты чтения, dropTables, эвикция, коды, ошибки, узел-призрак, рестарт, гонки, fault/soak-тесты | 0 |
| 1 | `p5/connect` | CLI, install/doctor, dist, install-e2e | 0 |
| 2 | `p5/sql-io` | sql_query, db_reader (duckdb), file_list, csv gzip, рецепты regex/split/covariance | 1 (коды/ошибки) |
| 2 | `p5/stats` | group_by ext, joiner ext, hypothesis_test, linear_regression | 1 |
| 2 | `p5/output` | markdown_report, chart, data_quality, рецепты rollup/normality/HAVING/train-test | 1 |
| 3 | `p5/recipes` | ресурсы, prompts, инструменты рецептов, dap_sql | 1 (connect), 2 (sql_query) |
| 4 | — | интеграция: make check, coverage, install-e2e, soak, docs, релиз 1.1.0 | 1–3 |

Правила: один писатель на worktree; общие файлы (`Makefile`, `NODES.md`, `api/openapi.yaml`) правит только интегратор при слиянии; после каждого слияния — `make check`; финальное ревью — broad review по всей ветке.

## 12. Приёмка

- `make check` — без регрессий.
- `make coverage` — ≥67/69 офлайн (68 при сети); `make extensions` — расширения установлены, `doctor` подтверждает.
- `make install-e2e` — Hermes и DSH: install + doctor PASS.
- `make soak` — 200 workflow × 10 сессий, heap в пределах лимита, эвикция и dropTables сработали.
- Fault-injection зелёный; goldens 15/15 + новые.
- Документы: README, MCP.md, API.md, NODES.md, QUALITY.md, ROADMAP.md обновлены.

## 13. Риски

| Риск | Мера |
|---|---|
| Патч DSH заменяет весь `servers`-map; ручные правки требуют перезапуска | install мержит только `servers.dap` и сохраняет остальные ключи; при нестандартном YAML — фрагмент + код 2; `doctor` проверяет stdio напрямую |
| Расширения DuckDB требуют сети при установке | проверено: подписанные сборки 1.5.6, ~74 МБ кэш, установка ~2 с, после — офлайн; `make extensions` + `doctor`; autoinstall выключен |
| p-value для тестов и регрессии | своя неполная бета (~100 строк), без зависимости; сверка с эталонными значениями R/scipy |
| sql_query read-only нельзя гарантировать через `enable_external_access=false` | subquery-обёртка + deny-list ключевых слов + лимиты; тесты на multi-statement и DDL |
| PNG недетерминирован (AA/шрифты) | golden только для SVG; PNG — smoke-проверка «файл создан, размер > 0» |
| Конфликты слияния в общих файлах | один писатель на файл, интегратор сливает последовательно |
| Рост jar выше 50 МиБ | только существующие зависимости; проверка размера в lint |

## 14. Открытые вопросы (решаются на этапе плана)

1. Формат p-value в v1: statistic+df+p_value сразу или statistic+df с p_value во втором шаге — решается по объёму математики в плане.

## 15. Что дальше

После ревью спеки — план реализации (`superpower-writing-plans`), затем исполнители в worktrees по этапам §11.
