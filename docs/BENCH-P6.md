# BENCH-P6 — бенчмарк аналитического пути P6

Харнесс — `tests/p6_bench.py` (Python 3, только stdlib: `urllib`/`http.client`,
`subprocess`, `json`, `time`, `statistics`, `pathlib`). Он меряет путь аналитика
целиком: ingest CSV → Parquet-кэш, workflow чтения, `/table` (страница и пагинация),
`/profile`, `/explore`, SQL-агрегат, опционально корреляцию и линейную регрессию,
RSS/heap движка и накладные расходы MCP-адаптера.

Отчёт — `<out>/summary.md`; машинные данные — `<out>/summary.json` и
`<out>/<dataset>.json`. Таблицы §4–§10 — шаблон: числа заполняются прогоном на
больших датасетах (§2), пока стоят прочерки. Реальный smoke-прогон на
`tests/p6_bench_manifest.example.json` — §11.

## 1. Статус

- Харнесс готов: engine lifecycle, HTTP-путь, MCP-путь, точность по
  `reference.json`, устойчивость к ошибкам (каждый шаг под таймаутом, ошибка
  записывается и прогон продолжается).
- Unit-тесты чистых хелперов — `tests/test_p6_bench.py` (52 кейса, движок не
  нужен): `python3 -m unittest tests.test_p6_bench -v`.
- Smoke на example-манифесте: 2 датасета, 34 шага, 0 ошибок, MCP 9 вызовов,
  0 ошибок (§11).
- Прогон на больших датасетах не входит в эту задачу: манифест и `data/`
  готовит интегратор (§2).

## 2. Как запустить

Smoke (example-манифест, iris + p2 из `tests/goldens-native/data/`):

```bash
python3 tests/p6_bench.py \
  --manifest tests/p6_bench_manifest.example.json \
  --data-dir build/p6-bench/data \
  --out build/p6-bench/smoke \
  --start --java "$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home/bin/java"
```

Большие датасеты (манифест и `data/` готовит интегратор):

```bash
python3 tests/p6_bench.py \
  --manifest build/p6-bench/manifest.json \
  --data-dir build/p6-bench/data \
  --out build/p6-bench/results \
  --start --heap 6g --duckdb-memory 2GB --port 7811 \
  --java "$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home/bin/java"
```

Полезные ключи:

| Ключ | По умолчанию | Смысл |
|---|---|---|
| `--jar` | `engine-native/target/dap-native.jar` | собранный движок (`make build-native`) |
| `--data-dir` | `build/p6-bench/data` | каталог датасетов; отсюда же читается `reference.json` |
| `--manifest` | `tests/p6_bench_manifest.example.json` | JSON-массив датасетов |
| `--out` | `build/p6-bench/results` | результаты, `engine.log`, `mcp/`, `duckdb-tmp/`, `cache/` |
| `--base` | `http://127.0.0.1:7811` | адрес движка для `--no-start` |
| `--start / --no-start` | `--start` | поднять и погасить движок самому |
| `--port` | `7811` | порт своего движка |
| `--heap` | `4g` | `-Xmx` JVM |
| `--duckdb-memory` | `1GB` | `DAP_DUCKDB_MEMORY_LIMIT` |
| `--datasets` | все | список имён через запятую |
| `--timeout` | `900` | таймаут одной операции, с |
| `--mcp / --no-mcp` | `--mcp` | мерить MCP-адаптер |
| `--java` | `$DAP_JDK`, `$JAVA_HOME`, PATH | java для своего движка |

Код возврата: `0` — summary записан (упавшие шаги — это данные, а не сбой
харнесса), `2` — движок не поднялся/манифест невалиден. Упавшие шаги видны в
`totals.failed` и в таблицах `summary.md`.

## 3. Метод

Движок поднимается как `java -Xmx<heap> -jar <jar>` с окружением:

| Переменная | Значение |
|---|---|
| `DAP_HOME` | корень репозитория |
| `DAP_PORT` | `--port` |
| `DAP_BIND` | `127.0.0.1` |
| `DAP_DUCKDB_MEMORY_LIMIT` | `--duckdb-memory` |
| `DAP_DUCKDB_TEMP_DIR` | `<out>/duckdb-tmp` |
| `DAP_CACHE_DIR` | `<out>/cache` |
| `DAP_ALLOWED_ROOTS` | `<data-dir>:<out>:<repo>:/tmp` |

Старт — до первого `200 {ok:true}` от `/v1/health` (таймаут 180 с), stderr/stdout
пишутся в `<out>/engine.log`, остановка — `POST /v1/shutdown` (fallback:
terminate/kill). `--no-start` только проверяет здоровье `--base` и не гасит чужой
движок. Каждый шаг меряется монотонными часами вокруг HTTP-вызова, в JSON
пишутся `ms`, `bytes` (размер тела ответа) и `ok`/`error`.

Манифест — JSON-массив:

| Поле | Обяз. | Смысл |
|---|---|---|
| `name` | да | `[A-Za-z0-9._-]{1,64}`, уникальное; имя файлов результатов |
| `path` | да | CSV/XLSX/JSON/Parquet; относительный путь — от корня репозитория |
| `format` | нет | `csv`/`excel`/`json`/`parquet`; по умолчанию из расширения |
| `sql` | нет | read-only SELECT/WITH, вход доступен как `src`; по умолчанию `SELECT COUNT(*) AS n FROM src` |
| `numeric_columns` | нет | числовые колонки для корреляции (нужно ≥ 2) |
| `group_column` | нет | колонка для group counts (точность) и `group_by` регрессии |
| `value_column` | нет | отклик регрессии; предикторы — остальные `numeric_columns` |

Шаги на датасет (имена в `steps`):

| Шаг | Что меряется |
|---|---|
| `ingest` | `POST /v1/datasets` → `rows`, `columns`, `cached`, размер Parquet |
| `reader_build` | создать workflow + `dap.parquet_reader` (без run) |
| `reader_run` | `POST .../run` (план + исполнение) |
| `table_first` | `GET .../table?limit=20` |
| `table_page` | `GET .../table?offset=1000&limit=20` |
| `profile` | `GET .../profile` |
| `estimate` | `GET .../estimate` |
| `explore` | `POST /v1/explore {dataset, sampleRows: 1000}` |
| `sql_build` / `sql_run` / `sql_table` | `parquet_reader → dap.sql_query` (манифестный SQL) |
| `correlation_build` / `_run` / `_table` | `dap.correlation` по `numeric_columns` (≥ 2) |
| `regression_build` / `_run` / `_table` | `dap.linear_regression`, если задан `value_column` |
| `group_counts_build` / `_run` / `_table` | `SELECT group, COUNT(*) ... GROUP BY 1`, если задан `group_column` |

MCP-режим поднимает `adapters/mcp/dap_mcp.py` (stdio JSON-RPC, `DAP_HOME`,
`DAP_PORT`), делает `initialize` и вызывает `dap_explore`, `dap_analyze`,
`dap_get_table` (offset 1000 + проекция + `format: csv`), `dap_estimate`,
`dap_report` (`format: html`). Для `dap_get_table` нужен workflow в сессии
адаптера, поэтому дополнительно меряются `dap_workflow_create`, `dap_add_node`,
`dap_run`, `dap_delete_workflow`; они тоже входят в `callCount`. На каждый вызов
пишутся `ms`, `bytes` (размер текстового ответа), `tokens ≈ bytes/4`, `ok`.
Одновызовные инструменты сравниваются с эквивалентным HTTP-путём:

| MCP-инструмент | HTTP-эквивалент |
|---|---|
| `dap_explore` | `explore` |
| `dap_analyze` | `reader_build + reader_run + table_first + profile` |
| `dap_get_table` | `table_page` |
| `dap_estimate` | `estimate` |
| `dap_report` | `reader_build + reader_run + table_first + profile` |

`speedup = HTTP ms / MCP ms` (> 1 — MCP быстрее). MCP-секция меряется на первом
датасете выборки (адаптер — отдельный процесс; повтор на каждом датасете
умножал бы время прогона).

## 4. Окружение

| Что | Значение |
|---|---|
| Машина | MacBook (Apple silicon), локальный диск |
| ОС / arch | macOS-27.2-arm64-arm-64bit-Mach-O |
| JDK | openjdk version "21.0.13" 2026-10-20 LTS |
| Python | 3.13.18 |
| Jar | DAP 1.2.0, 44.8 МиБ |
| JVM | -Xmx6g |
| DuckDB | memory limit 2GB, temp dir в --out |

## 5. Датасеты

| Датасет | Строк | Колонок | CSV, МБ | Parquet, МБ | cached |
|---|---|---|---|---|---|
| amazon-fine-food | 568454 | 10 | 287 | 168 | False |
| amazon-reviews | 3600000 | 3 | 1512 | 943 | False |
| chicago-crimes | 2688710 | 23 | 631 | 184 | False |
| flight-delays | 5819079 | 31 | 565 | 138 | False |
| h1b-visa | 2895203 | 11 | 431 | 80 | False |
| home-credit | 13605401 | 8 | 690 | 325 | False |
| lending-club | 27648741 | 9 | 1700 | 191 | False |
| nyc-taxi | 12748986 | 19 | 1894 | 368 | False |
| santander | 200000 | 202 | 288 | 221 | False |
| us-accidents | 7728394 | 46 | 2917 | 775 | False |

## 6. Ingest

| Датасет | DAP ms | МБ/с | DuckDB ms | DAP/DuckDB | rows | columns |
|---|---|---|---|---|---|---|
| amazon-fine-food | 530 | 542 | 988 | 0.54 | 568454 | 10 |
| amazon-reviews | 2330 | 649 | 3049 | 0.76 | 3600000 | 3 |
| chicago-crimes | 1383 | 456 | 1652 | 0.84 | 2688710 | 23 |
| flight-delays | 2383 | 237 | 2642 | 0.90 | 5819079 | 31 |
| h1b-visa | 819 | 526 | 916 | 0.89 | 2895203 | 11 |
| home-credit | 2050 | 336 | 1673 | 1.23 | 13605401 | 8 |
| lending-club | 3558 | 478 | 4205 | 0.85 | 27648741 | 9 |
| nyc-taxi | 3567 | 531 | 3957 | 0.90 | 12748986 | 19 |
| santander | 1751 | 165 | 5981 | 0.29 | 200000 | 202 |
| us-accidents | 7203 | 405 | 8736 | 0.82 | 7728394 | 46 |

DAP и DuckDB читают один и тот же CSV; DuckDB-число — read_csv_auto → COPY в Parquet в отдельном процессе, DAP — POST /v1/datasets. Разница в пределах page cache/JIT: DAP быстрее или на уровне DuckDB на 9 из 10 датасетов (0.29–0.90x); home-credit — 1.23x, это единственный случай медленнее baseline.

## 7. Query / profile / explore latency

| Датасет | reader_build | reader_run | table_first | table_page | profile | estimate | explore | sql_run | correlation_run | regression_run |
|---|---|---|---|---|---|---|---|---|---|---|
| amazon-fine-food | 2 | 2 | 30 | 32 | 105 | 2 | 278 | 4 | 12 | 17 |
| amazon-reviews | 8 | 6 | 58 | 52 | 566 | 3 | 638 | 3 | — | — |
| chicago-crimes | 2 | 2 | 16 | 15 | 171 | 3 | 534 | 3 | 380 | 212 |
| flight-delays | 3 | 3 | 12 | 11 | 334 | 5 | 2510 | 7 | 225 | 328 |
| h1b-visa | 1 | 2 | 9 | 8 | 99 | 3 | 234 | 3 | 60 | 184 |
| home-credit | 2 | 3 | 11 | 12 | 408 | 3 | 998 | 5 | 539 | 298 |
| lending-club | 2 | 4 | 12 | 13 | 548 | 5 | 959 | 7 | 193 | 319 |
| nyc-taxi | 2 | 4 | 16 | 14 | 678 | 5 | 3386 | 9 | 680 | 180 |
| santander | 2 | 3 | 76 | 86 | 806 | 4 | 1139 | 8 | 109 | 209 |
| us-accidents | 8 | 28 | 40 | 31 | 1016 | 7 | 2195 | 10 | 1590 | 607 |

Все значения — миллисекунды, один прогон. profile — весь источник; explore — sampleRows=1000 для связей/гипотез, профиль/quality по всему источнику, quality-сканы (квартили/выбросы/дубликаты) на больших таблицах — по первым 100 000 строк.

## 8. Память и RSS

| Датасет | heap до, МБ | heap после reader | heap после explore | heap после heavy | RSS |
|---|---|---|---|---|---|
| amazon-fine-food | 103 | 104 | 124 | 39 | null (sandbox) |
| amazon-reviews | 88 | 89 | 97 | 98 | null (sandbox) |
| chicago-crimes | 98 | 100 | 56 | 107 | null (sandbox) |
| flight-delays | 107 | 109 | 45 | 54 | null (sandbox) |
| h1b-visa | 113 | 114 | 39 | 103 | null (sandbox) |
| home-credit | 39 | 41 | 44 | 55 | null (sandbox) |
| lending-club | 87 | 88 | 100 | 88 | null (sandbox) |
| nyc-taxi | 65 | 67 | 83 | 87 | null (sandbox) |
| santander | 54 | 59 | 28 | 113 | null (sandbox) |
| us-accidents | 17 | 23 | 24 | 65 | null (sandbox) |

RSS недоступен: DSH sandbox запрещает ps (rssNote в JSON); heap — memory.usedMb из /v1/health. Вне песочницы харнесс снимает RSS.

## 9. MCP overhead

| Инструмент | wall ms | bytes | ≈tokens | ok |
|---|---|---|---|---|
| dap_explore | 2356 | 15621 | 3905 | yes |
| dap_analyze | 1178 | 5737 | 1434 | yes |
| dap_workflow_create | 5 | 67 | 16 | yes |
| dap_add_node | 4 | 42 | 10 | yes |
| dap_run | 8 | 126 | 31 | yes |
| dap_get_table | 19 | 432 | 108 | yes |
| dap_delete_workflow | 4 | 12 | 3 | yes |
| dap_estimate | 23 | 939 | 234 | yes |
| dap_report | 4580 | 17764 | 4441 | yes |
| **итого** | **8177** | **40740** | **10185** | **9/9** |

| Инструмент | MCP ms | HTTP ms | HTTP шагов | speedup |
|---|---|---|---|---|
| dap_explore | 2356 | 2195 | 1 | 0.93 |
| dap_analyze | 1178 | 1092 | 4 | 0.93 |
| dap_get_table | 19 | 31 | 1 | 1.63 |
| dap_estimate | 23 | 7 | 1 | 0.31 |
| dap_report | 4580 | 1092 | 4 | 0.24 |

speedup = HTTP/MCP; >1 — MCP быстрее. dap_report дороже HTTP-набора из-за повторного profile/describe и записи HTML; для таблицы без отчёта дешевле dap_analyze.

## 10. Точность vs reference

Reference посчитан независимо в DuckDB Python (read_csv_auto/read_parquet), сравнение — с /profile (средние) и group counts. rel — относительная ошибка; rows и group counts совпали точно.

| Датасет | rows ref/obs | max rel средних | max rel group counts |
|---|---|---|---|
| amazon-fine-food | 568454/568454 | 0.00e+00 | 0.00e+00 |
| amazon-reviews | 3600000/3600000 | 0.00e+00 | 0.00e+00 |
| chicago-crimes | 2688710/2688710 | 0.00e+00 | 0.00e+00 |
| flight-delays | 5819079/5819079 | 0.00e+00 | 0.00e+00 |
| h1b-visa | 2895203/2895203 | 4.67e-14 | 0.00e+00 |
| home-credit | 13605401/13605401 | 0.00e+00 | 0.00e+00 |
| lending-club | 27648741/27648741 | 1.39e-16 | 0.00e+00 |
| nyc-taxi | 12748986/12748986 | 2.78e-14 | 0.00e+00 |
| santander | 200000/200000 | 4.97e-15 | 0.00e+00 |
| us-accidents | 7728394/7728394 | 1.45e-14 | 0.00e+00 |

Максимальная относительная ошибка по всем 10 датасетам — 4.7e-14 (средняя wage на h1b-visa); это ошибка double-арифметики, не логики. Rows и group counts — точные.


## 11. Smoke-прогон (example-манифест)

Запуск: `tests/p6_bench_manifest.example.json`, `--data-dir build/p6-bench/smoke-data`
(iris + p2 + `reference.json`), свежий `--out`, `--start`, heap 4g, DuckDB 1GB.
Движок DAP 1.2.0, jar 44,8 МиБ, старт 504,5 мс, health 3,2 мс. Числа ниже — один
прогон; между прогонами разброс заметный (JIT, page cache, порядок шагов).

| Датасет | ingest | reader_run | table 20 | table page | profile | estimate | explore |
|---|---|---|---|---|---|---|---|
| iris (150×5) | 1593,1 | 19,4 | 7,3 | 2,9 | 11,0 | 2,5 | 31,4 |
| p2 (6×4) | 10,8 | 1,8 | 4,1 | 2,5 | 3,2 | 1,1 | 13,1 |

- Итог: 2 датасета, 34 шага, 0 ошибок; SQL/корреляция/регрессия/group counts —
  все шаги `ok` (iris: pearson по 4 колонкам, регрессия `sepal_length` на 3
  предиктора с `group_by: species`, group counts 50/50/50).
- MCP: 9 вызовов, 0 ошибок, 154,3 мс суммарно, 9 885 байт (≈2 471 токен);
  `dap_report` записал `mcp/iris-report.html` (5 046 байт).
- Точность: `rows` 150/150, group counts 50/50/50, |Δ| средних < 7e-4
  (`petal_length`), относительная < 2e-4.
- RSS: `null` — среда запрещает `ps` (`rssNote` в JSON); heap 42 → 15 МБ
  (`memoryAfter` снят после закрытия сессионных БД).
- ingest iris 1,6 с — это первый ingest прогона (холодный DuckDB + warm-up),
  а не стоимость CSV на 150 строк.

## 12. Слабые места, найденные бенчмарком, и что исправлено

1. **Explore падал по OOM на 7.7M x 46 (US Accidents) при DuckDB 1 GB.** Точный COUNT(DISTINCT) по 46 колонкам и SELECT DISTINCT * не помещались в память. Исправлено: выше 100 000 строк — APPROX_COUNT_DISTINCT, quality-сканы (квартили/выбросы/дубликаты) по первым 100 000 строк, дубликаты — по hash-сэмплу. Explore: US 8.2 с → 2.2 с, NYC 19.4 с → 3.1 с; ошибка 500 заменена на 400 explore_too_large с подсказкой.
2. **/profile делал отдельный запрос на каждую колонку (46 сканов).** Исправлено: один агрегатный запрос на все колонки + один запрос на точный distinct для колонок ниже порога (только до 1M строк). Profile: US 2.9 с → 1.0 с, NYC 2.6 с → 0.7 с.
3. **CSV ingest не умел header=false, битые строки и null-строки NA.** h1b падал на PREVAILING_WAGE/NA, amazon-reviews не имел заголовка. Исправлено: POST /v1/datasets и dap_ingest принимают header, delimiter, quote, escape, ignoreErrors, nullPadding, strictMode, nullStrings; опции входят в ключ кэша. Для h1b нужны nullStrings=[NA] + ignoreErrors=true (или предварительная типизация), для amazon-reviews — header=false.
4. **Linear regression падал на 9 из 10 датасетов из-за cap 100k строк.** Исправлено: настройка sample_rows=N — детерминированные первые N строк; ошибка подсказывает sample_rows. Харнесс ставит 100 000; финальный прогон — 194 шага, 0 ошибок (было 9).
5. **Explore считал IQR-выбросы для Severity (1..4) и давал 1.57M ложных выбросов.** Исправлено: IQR-выбросы пропускаются для числовых колонок с <=10 различными значениями; quality/hypotheses/recommendations это учитывают.
6. **dap_report остаётся дорогим (4.6 с на US Accidents против 1.1 с у dap_analyze).** Причина — повторный profile/describe и запись HTML; для таблицы без отчёта дешевле dap_analyze. Кандидат на оптимизацию: переиспользовать профиль между секциями отчёта.
7. **Широкие таблицы в JSON.** 20 строк x 46 колонок — 12 КБ; MCP dap_get_table с проекцией/CSV/offset даёт 452 байта на страницу. Рекомендация агенту: columns + format=csv/markdown + offset.
8. **RSS в песочнице недоступен** — ps запрещён, в отчёте null и heap из /v1/health. Вне песочницы харнесс снимает RSS.
9. **Остаточные ограничения.** In-memory stats (hypothesis/cluster/PCA/resample/survival) по-прежнему cap 100k/50k строк с явной ошибкой; для больших данных — SQL-агрегация или sample_rows (пока только regression). Kendall p-value и ADF — приближения, задокументированы в NODES.md. ORC/Avro не поддерживаются DuckDB 1.5.6.

## 13. Воспроизведение

1. `make build-native` (или `engine-native/mvnw -DskipTests package`).
2. `python3 -m unittest tests.test_p6_bench -v` — 52 кейса, движок не нужен.
3. Smoke: команда из §2, ожидаемо 2 датасета / 34 шага / 0 ошибок и
   `summary.json`, `summary.md`, `<dataset>.json` в `--out`.
4. Большие данные: манифест + `data/` + (опционально) `reference.json`,
   прогон из §2, числа — в таблицы §4–§10, отклонения — в §12.
