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
- Unit-тесты чистых хелперов — `tests/test_p6_bench.py` (43 кейса, движок не
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
| Машина | — |
| ОС / arch | — |
| JDK | — |
| Python | — |
| Jar | — |
| JVM | `-Xmx` — |
| DuckDB | memory limit — |

## 5. Датасеты

| Датасет | Строк | Колонок | Файл | Parquet | cached |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

## 6. Ingest

| Датасет | wall ms | МБ/с | rows | columns | cached |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

Первый ingest в прогоне включает холодный старт DuckDB/JIT и конвертацию;
повторный ingest того же файла в тот же `--out` вернёт `cached: true` и меряет
только хэш/стат. Для «холодных» чисел нужен свежий `--out`.

## 7. Query / profile / explore latency

| Датасет | reader_build | reader_run | table 20 | table page | profile | estimate | explore | sql_run |
|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — |

## 8. Память и RSS

| Датасет | RSS до | RSS после ingest | RSS после explore | heap до, МБ | heap после, МБ |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

RSS берётся `ps -o rss= -p <pid>` у своего движка; heap — `memory.usedMb` из
`/v1/health`. Если `ps` запрещён средой (DSH sandbox), в JSON пишется
`rssBytes: null` и причина в `rssNote`, а heap остаётся доступен.

## 9. MCP overhead

| Инструмент | wall ms | bytes | ≈tokens | ok |
|---|---|---|---|---|
| — | — | — | — | — |

| Инструмент | MCP ms | HTTP ms | HTTP шагов | speedup |
|---|---|---|---|---|
| — | — | — | — | — |

## 10. Точность vs reference

Если `<data-dir>/reference.json` существует, харнесс сравнивает с ним
`profile`/`table`/`explore` и пишет абсолютные/относительные расхождения:

```json
{
  "iris": {
    "rows": 150,
    "numeric_means": {"sepal_length": 5.8433, "petal_length": 3.7587},
    "group_counts": {"setosa": 50, "versicolor": 50, "virginica": 50}
  }
}
```

| Датасет | Метрика | reference | observed | abs | rel |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

Без `reference.json` секция содержит note «accuracy skipped» — это штатный режим
smoke-прогона.

## 11. Smoke-прогон (example-манифест)

Запуск: `tests/p6_bench_manifest.example.json`, `--data-dir build/p6-bench/smoke-data`
(iris + p2 + `reference.json`), свежий `--out`, `--start`, heap 4g, DuckDB 1GB.
Движок DAP 1.2.0, jar 44,8 МиБ, старт 504,5 мс, health 3,2 мс.

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

## 12. Слабые места и что чинить

1. **RSS в песочнице.** `ps` запрещён DSH sandbox → `rssBytes: null`. Вне
   песочницы работает. Чинить: отдавать RSS в `/v1/health` (движок знает
   процесс) или снимать замер на хосте; пока в отчёте честно стоит `rssNote`.
2. **Один прогон без повторов.** Тайминги шумят (JIT, page cache); медианы по
   N прогонам не считаются. Чинить: `--repeat N` и `timing()` по выборке.
3. **Холодный/тёплый ingest.** Повторный запуск в тот же `--out` даёт
   `cached: true`. Для больших данных нужен свежий `--out` или отдельный
   прогон «тёплого» кэша; поле `cached` фиксирует режим.
4. **explore семплирован.** Профиль/quality — по всему источнику, связи и
   гипотезы — по первым `sampleRows=1000`; точность связей на больших данных
   ограничена этим сэмплом.
5. **Корреляция только pearson.** `spearman`/`kendall` не меряются (kendall
   к тому же ограничен 100k строк).
6. **Регрессия — только при `value_column`.** Категориальные предикторы движок
   не поддерживает; на датасете с 2 строками на группу движок законно падает
   (см. §11: поэтому у p2 в example-манифесте нет `value_column`).
7. **MCP меряется на одном датасете** (первом) — это цена адаптера, не датасета;
   setup-вызовы `dap_get_table` входят в `callCount` и в `totalMs`.
8. **HTTP-накладные харнесса.** В `ms` входит json-сериализация/парсинг
   stdlib-харнесса (одинаково для всех шагов) и один keep-alive сокет.
9. **Нет изоляции JIT-прогрева.** Первый датасет платит за прогрев движка;
   для честного сравнения датасетов нужен warm-up-шаг до замеров.
10. **Лимиты движка по умолчанию.** `DAP_QUERY_TIMEOUT_SEC=60` может резать
    `/table`/`/profile` на очень больших таблицах; харнесс его не переопределяет
    (план не просил) — при необходимости задать в окружении.
11. **Диск.** `<out>/cache`, `<out>/duckdb-tmp` и Parquet-кэш не чистятся между
    прогонами; на 10M+ строках нужен запас места.
12. **Порт.** Харнесс не проверяет, свободен ли `--port`; занятый порт даёт
    ошибку старта движка (код 2).
13. **Точность неполная.** Сравниваются rows, средние и group counts; связи и
    quality из `/explore` не сверяются (нет в reference-контракте).

## 13. Воспроизведение

1. `make build-native` (или `engine-native/mvnw -DskipTests package`).
2. `python3 -m unittest tests.test_p6_bench -v` — 43 кейса, движок не нужен.
3. Smoke: команда из §2, ожидаемо 2 датасета / 34 шага / 0 ошибок и
   `summary.json`, `summary.md`, `<dataset>.json` в `--out`.
4. Большие данные: манифест + `data/` + (опционально) `reference.json`,
   прогон из §2, числа — в таблицы §4–§10, отклонения — в §12.
