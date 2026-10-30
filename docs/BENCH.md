# BENCH — замеры dap-native

Фаза 3. Харнесс — `tests/bench.py` (Python 3, только stdlib). Пилотные числа
(10 млн строк) сняты на merged jar 30.10.2026, отчёт — §4.1.

## 1. Статус

- Харнесс готов и покрывает три секции: `cold`, `csv`, `branches`.
- Пилот на 10M строк снят (§4.1): cold 473 мс, ingest 11,7 с, `/table` 69 мс,
  heap peak 4,65 ГБ; ветви измерены на 5M (ratio 1,18×) из-за лимита 8 ГиБ RAM.
- Размер jar: 46 298 055 Б = 44,2 МиБ (лимит 50 МБ, ROADMAP §9).
- Образ (< 200 МБ, healthy < 5 с) в этой среде не измерим: Docker-демона нет
  (см. §3); Dockerfile и арифметика размера — [deploy/README-native.md](../deploy/README-native.md),
  строка `TBD` до прогона на Docker-хосте.

## 2. Метод

Стенд (фиксируется харнессом в `environment` JSON):

| Что | Значение |
|---|---|
| Машина | Apple M1, arm64, 8 ядер, 8 ГиБ RAM, macOS |
| JDK | Temurin 21.0.13+9 (`$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home`) |
| DuckDB | 1.5.6.0 (`duckdb_jdbc`, версия из `engine-native/pom.xml`) |
| Jar | `engine-native/target/dap-native.jar`, `-DskipTests package` |
| JVM | `--heap 6g` в пилоте (`-Xmx4g` — дефолт харнесса); loopback `127.0.0.1:<port>` |
| DuckDB | `DAP_DUCKDB_MEMORY_LIMIT=2GB`, `DAP_DUCKDB_THREADS=8`, `DAP_DUCKDB_TEMP_DIR=/tmp/dap-bench-tmp` |

Что и как измеряется:

| Секция | Действие | Как считается |
|---|---|---|
| `cold` | spawn `java -Xmx6g -Ddap.port=N -jar …` → первый `200 {ok:true}` от `GET /v1/health` (в пилоте `--heap 6g`; дефолт харнесса — `4g`) | монотонные часы от `Popen` до успешного probe, опрос каждые 20 мс; 3 прогона, медиана + min/max |
| `csv` | детерминированный CSV → `csv_reader → row_filter(value >= 50) → group_by(grp, mean+sum value) → sorter(grp asc)` через `/v1` | четыре числа ниже |
| `branches` | один DAG с двумя независимыми `csv_reader`-ветвями vs те же два ingest'а подряд (два прогона одно-ветвевого workflow) | `ratio = median(seq) / median(parallel)` — end-to-end ускорение; `adjustedRatio` вычитает измеренную стоимость пустого прогона (3 замера) с обеих сторон; оба variant'а прогреваются перед замерами (JIT/кэш), sync-run меряет только ingest |

CSV (детерминированный, без `random`): `id` = 0…N−1 (Int64),
`value` = `(i*7919 % 100000) / 1000.0` (Float64, [0, 100)),
`grp` = `g(i % 8)` (Utf8, 8 групп), явная схема в `csv_reader`;
порог фильтра по умолчанию 50.0 (проходит ~50 % строк). Файл генерируется в temp-каталог
(10M = 169,6 МиБ) и удаляется после прогона, если нет `--keep-data`.

Числа `csv`:

| Поле | Что это |
|---|---|
| `runMs` | wall time клиента на `POST /v1/workflows/{id}/run` (sync). `csv_reader` — единственный eager-узел, остальные ленивые: это **ingest** (parse CSV + Arrow + Appender), плюс серверный `durationMs` в `runServerDurationMs` |
| `tableMs` | `GET …/nodes/{final}/table`: `COUNT(*)` + `LIMIT` материализует весь SQL-конвейер (filter+group_by+sorter) |
| `totalMs` | `runMs + tableMs` — оценка end-to-end, сопоставимая с «read+filter+groupby» из ROADMAP §9 |
| `profileMs` | `GET …/nodes/{final}/profile` (полный проход по 8 строкам результата; заново исполняет конвейер) |
| `profileSourceMs` | `GET …/nodes/{csv_reader}/profile` — полный проход по исходным N строкам (`COUNT`/`DISTINCT`/`MIN`/`MAX`/`AVG`) |

JIT: по умолчанию один **неизмеряемый** warmup-прогон (`--warmup 1`) перед таймируемым;
его время пишется в `warmupRunsMs`. Без него первый ingest на 100k занимает ~2,5 с вместо
~0,12 с (JIT), поэтому сравнивать «холодный» и «тёплый» прогоны нельзя.

Память: фоновый поток каждые 200 мс читает `ps -o rss= -p <pid>` (`csv.rssPeakBytes`,
RSS всего JVM) и, независимо, `GET /v1/health` → `memory.usedMb/maxMb`
(`csv.heapPeakMb`, `csv.heapMaxMb`). Второе — ровно формула ROADMAP §9
(`Runtime.totalMemory()-freeMemory()`) и работает там, где `ps` запрещён песочницей.
Каждое поле имеет смысл отдельно: heap не включает DuckDB-буферы и JVM-оверхед, RSS —
включает всё, но с разрешением 200 мс.

Ветви: `DuckDbSession.loadInto` синхронизирован на job-соединении
([NATIVE.md](NATIVE.md) §2.4), поэтому `CREATE TABLE`+`Appender` двух
`csv_reader`-ов не интерливаются, а `CsvTableReader.read` (parse) выполняется **до**
lock и перекрывается. Отсюда честное ожидание: `1.0x` (полная сериализация) …
`2.0x` (полный параллелизм), фактическое значение зависит от размера файла: на малых
доминирует parse, на больших — сериализованный append. Харнесс пишет измеренное `ratio` и
это объяснение в `branches.reason`, ничего не подгоняя. Sync-run меряет только ingest:
SQL-ветви ленивые и исполняются на `/table`, поэтому «параллельность DAG» здесь — это
параллельность ingest'а.

## 3. Ограничения

- **Нет Docker-демона в этой среде.** Размер образа и `docker run` healthy < 5 с здесь
  не измеряются; строка `TBD`, команды — §4.1.
- **KNIME-oracle не умеет CSV через `/v1`.** `CSV Reader` падает с
  `Execute failed: Workflow context required.` ([PARITY.md](PARITY.md) §6.6.1), поэтому
  сравнение «≥ 3× быстрее KNIME» для этого workflow **native-only**. Поддерживаемого
  эквивалента нет: `table_source` читает только shipped-датасеты KNIME (максимум ADULT,
  ~32k строк), а `GroupBy`-агрегации через `/v1` не конфигурируются (§6.6.3). Значит,
  число KNIME для 10M-пайплайна получить нельзя; в таблице — `n/a` с причиной, а не
  выдуманная цифра. Cold start и размер jar — тоже не сравнение с KNIME.
- **RSS.** `ps`-семплирование раз в 200 мс; пики короче пропускаются; RSS включает
  JVM-базу. В песочнице исполнителя `ps` запрещён (`Operation not permitted`), поэтому
  `rssPeakBytes = null`, а харнесс пишет note и заполняет `heapPeakMb` из `/v1/health`.
  На обычной машине заполняются оба поля.
- **Шум.** JIT (см. §2), планировщик macOS, page cache, фоновые процессы. Харнесс
  предупреждает, когда `parallelMs < 100 мс`, что отношение ветвей шумит, и советует
  `--branch-reps 3`; при `--branch-reps 1` на таких прогонах шум особенно заметен. Для
  стабильности — `--branch-reps 3` и 10M. Медианы по 3 прогонам для cold start — минимум,
  а не статистическая гарантия.
- **Диск.** 10M CSV = 169,6 МиБ; плюс копия таблицы в DuckDB и temp-каталог. На машине с 49 ГиБ
  свободного места это нормально, но `--keep-data` оставляет файл в temp (путь — в
  `dataDir` JSON).
- **Ветви: память.** В одном DAG две `csv_reader`-ветви держат две копии таблицы; на 10M
  при `-Xmx4g` прогон ветвей упал с `Java heap space` (это и есть причина мерить ветви на
  5M при `-Xmx6g`). Для 10M-ветвей нужен heap ≥ 8 ГиБ, то есть машина крупнее этой.
- **DuckDB-настройки.** `DAP_DUCKDB_MEMORY_LIMIT`/`DAP_DUCKDB_THREADS`/
  `DAP_DUCKDB_TEMP_DIR` движок читает ([NATIVE.md](NATIVE.md) §3.4); харнесс определяет это
  по классам движка (`engineReadsDapDuckdbEnv: true`) и пишет фактические значения в
  `environment.duckdb.settings`.

## 4. Результаты

### 4.1 Пилот: 10M строк, merged jar (30.10.2026)

Команда, которой сняты числа (полный прогон без ветвей — они отдельно, §4.1.1):

    cd engine-native
    export JAVA_HOME=$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home
    export PATH="$JAVA_HOME/bin:$PATH"
    ./mvnw -q -o -DskipTests package
    cd ..
    export DAP_DUCKDB_MEMORY_LIMIT=2GB DAP_DUCKDB_THREADS=8 \
           DAP_DUCKDB_TEMP_DIR=/tmp/dap-bench-tmp
    python3 tests/bench.py --jar engine-native/target/dap-native.jar --rows 10000000 \
        --heap 6g --only cold,csv --json build/bench-10m.json

| Метрика | Цель (ROADMAP §9) | native, merged jar | KNIME oracle | Поле JSON |
|---|---|---|---|---|
| Cold start, медиана 3 прогонов | < 2 с | **473 мс** | n/a (KNIME — desktop) | `cold.medianMs` |
| Cold start, min / max | — | 473 / 480 мс | n/a | `cold.minMs` / `cold.maxMs` |
| 10M sync-run (ingest) | — | **11 667 мс** (~857k строк/с) | n/a (PARITY §6.6.1) | `csv.runMs` |
| 10M `/table` (filter+group_by+sorter) | — | **69 мс** | n/a (PARITY §6.6.1) | `csv.tableMs` |
| 10M end-to-end = run + `/table` | ≥ 3× быстрее KNIME | **11 736 мс** | n/a (PARITY §6.6.1) | `csv.totalMs` |
| 10M `/profile` финального узла | — | **118 мс** (8 строк) | n/a | `csv.profileMs` |
| 10M `/profile` источника (полный проход) | — | **176 мс** | n/a | `csv.profileSourceMs` |
| JVM heap peak на 10M (формула ROADMAP) | ≤ KNIME | **4 653 МиБ** при `-Xmx6g` (макс. 6 144) | n/a | `csv.heapPeakMb` |
| Peak RSS JVM на 10M | ≤ KNIME | `null` — `ps` запрещён песочницей | n/a | `csv.rssPeakBytes` |
| Размер jar | < 50 МБ | 44,2 МиБ (46 298 055 Б) | n/a | `environment.jar.mib` |
| Размер образа | < 200 МБ | `TBD` (нет Docker-демона; арифметика ~152 МБ) | n/a | `docker images dap-native` |
| `docker run` healthy | < 5 с | `TBD` (нет Docker-демона) | n/a | `docker run` + poll `/v1/health` |

Дополнительно: CSV 169,6 МиБ сгенерирован за 4,1 с; warmup-прогон (не в счёте) — 13,4 с
против 11,7 с прогретого; heap idle — 31 МиБ; `engineReadsDapDuckdbEnv: true`.
Ingest упирается в JVM heap (Arrow-таблица + построчный Appender), а не в DuckDB: SQL-часть
конвейера на 10M занимает 69 мс.

#### 4.1.1 Ветви (5M, `-Xmx6g`, 3 повтора)

На 10M при `-Xmx4g` прогон двух `csv_reader`-ветвей падает с `Java heap space`
(две копии таблицы), поэтому ratio измерен на 5M. Харнесс прогревает оба варианта,
измеряет стоимость пустого workflow (per-run overhead) и вычитает её из обеих сторон:

    python3 tests/bench.py --jar engine-native/target/dap-native.jar --rows 5000000 \
        --heap 6g --only csv,branches --branch-reps 3 --json build/bench-branches.json

| Метрика | Значение |
|---|---|
| parallel (2 ветви в одном DAG) | 8 345 мс |
| sequential (два прогона подряд) | 10 574 мс |
| **ratio** (end-to-end speedup) | **1,27×** (цель ≥ 1,8× — не достигнута) |
| per-run overhead (пустой workflow, медиана 3) | 0,6 мс |
| **adjustedRatio** (без overhead) | **1,27×** — overhead не объясняет разрыв |
| csv 5M: ingest / `/table` / `/profile` | 4 298 / 31 / 59 мс |
| heap peak на 5M | 2 628 МиБ |

Причина зафиксирована в `branches.reason`: `DuckDbSession.loadInto` синхронизирован на
job-соединении, поэтому parse перекрывается, а `CREATE TABLE`+`Appender` — нет. Это
архитектурное ограничение пилота, а не ошибка харнесса; чтобы получить ≥1,8×, нужен
параллельный ingest (несколько соединений/appender'ов) — кандидат в волну IO.
Раннее измерение без прогрева/нормализации давало 1,18× (`--branch-reps 1`), полный
прогон с warmup и 3 повторами — 1,27×; оба числа end-to-end, не «чистая» параллельность DAG.

Почему `n/a` в колонке KNIME: oracle исполняет только in-memory workflow через `/v1`;
файловые ридеры требуют workflow-context и падают, а shipped-датасетов на 10M строк нет. Это
зафиксированное ограничение ([PARITY.md](PARITY.md) §6.6.1), а не отсутствие замера.

Строки образа заполняет задача образа (Docker-демона здесь нет):

    docker images dap-native
    time docker run --rm -d --name dap-bench -p 7925:7800 dap-native
    curl -sf http://127.0.0.1:7925/v1/health

### 4.2 Валидация харнесса (не пилотные числа)

Замеры этой ветки на том же стенде, jar 44,2 МиБ, `-Xmx4g`;
10M здесь не запускался.

    python3 tests/bench.py --jar engine-native/target/dap-native.jar --port 7925 --rows 100000
    python3 tests/bench.py --jar engine-native/target/dap-native.jar --port 7925 --rows 1000000 --branch-reps 3

| Метрика | 100k | 1M (`--branch-reps 3`) |
|---|---|---|
| Cold start: медиана (min–max) | 466 мс (436–583) | 501 мс (482–576) |
| Первый `/v1/health` после spawn | 436 мс | 726 мс |
| Sync-run (ingest) | 123 мс | 1363 мс |
| end-to-end (run + `/table`) | 132 мс | 1382 мс |
| `/table` финального узла | 9,2 мс | 19,6 мс |
| `/profile` финального узла | 15,5 мс | 27,5 мс |
| `/profile` источника | 16,8 мс | 30,4 мс |
| CSV-файл | 1,5 МиБ | 16,0 МиБ |
| Ветви: parallel / sequential / ratio | 150 / 167 мс / 1,11× | 1731 / 1915 мс / 1,11× |
| Peak RSS | `null` (в песочнице запрещён `ps`) | `null` |

Наблюдения: первый ingest на 100k — 2,5 с против 0,12 с тёплого (JIT: warmup-прогон в
`warmupRunsMs`); на 1M это 4,4 с против 1,4 с. Отношение ветвей на 1M стабильно ~1,1×:
parse перекрывается, append сериализован (§2), поэтому потолок «2×» не достигается и числа
не подгоняются. На 100k одиночный ingest ~83 мс, отношение шумит — харнесс предупреждает
(`--branch-reps`).

### 4.3 Решение по Polars (принято по измеренным данным)

Порог из ROADMAP §6: Polars — **только** если бенчмарк узла даёт ≥ 2× против DuckDB; иначе
остаёмся на DuckDB. Решение: **остаёмся на DuckDB**.

Основания (из §4.1, а не из ожиданий):

1. **SQL-часть уже не является стоимостью.** filter + group_by + sorter на 10M строк
   материализуются за **69 мс**, полный `/profile` источника — 176 мс. Узлам нечего
   ускорять в 2×: замена DuckDB на Polars на этом профиле не даёт измеримого выигрыша.
2. **Стоимость — в ingest и памяти, и она наша.** 10M ingest = 11,7 с и 4,65 ГБ heap
   (`CsvParser` → `ArrowTable` → построчный `Appender`), то есть узкое место — не
   SQL-движок, а путь данных. Polars не заменяет этот путь: между узлами нет Arrow-шины
   ([NATIVE.md](NATIVE.md) §4), поэтому Polars потребовал бы отдельной границы данных,
   а не «замены движка».
3. **Per-node ≥ 2× не измерен ни на одном узле.** Харнесс меряет workflow; отдельный
   per-node microbench, который мог бы дать порог, не проводился — при этом п.1 показывает,
   что ускорять там нечего: 69 мс на 10M — это уже ниже практического порога
   (round-trip HTTP на порядок больше).

Условия пересмотра (зафиксированы, чтобы решение не было «навсегда»): появится узел, у
которого доля DuckDB-исполнения в end-to-end ≥ 50 %, и прототип на Polars покажет ≥ 2× на
его профиле; либо стоимость ingest станет доминировать настолько, что понадобится
векторизованный scan (тогда рассматривается Arrow-native путь, а не замена SQL-движка).

## 5. Как воспроизвести

Сборка (offline, JDK 21; в свежем worktree `engine-native/.tools` сидируется из
основного checkout — каталог gitignored):

    cd engine-native
    export JAVA_HOME=$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home
    export PATH="$JAVA_HOME/bin:$PATH"
    ./mvnw -q -o test                 # 199 тестов, 0 падений (проверка сборки)
    ./mvnw -q -o -DskipTests package  # engine-native/target/dap-native.jar
    cd ..

Пилот (10M строк, JSON-отчёт; 169,6 МиБ CSV в temp). Числа §4.1 сняты с
`DAP_DUCKDB_MEMORY_LIMIT=2GB DAP_DUCKDB_THREADS=8 DAP_DUCKDB_TEMP_DIR=/tmp/dap-bench-tmp`
и `--heap 6g`; ветви — отдельно на 5M (§4.1.1):

    python3 tests/bench.py --jar engine-native/target/dap-native.jar --rows 10000000 \
        --heap 6g --only cold,csv --json build/bench-10m.json
    python3 tests/bench.py --jar engine-native/target/dap-native.jar --rows 5000000 \
        --heap 6g --only csv,branches --branch-reps 3 --json build/bench-branches.json

Смоук (100k, порт 7925, ничего не оставляет после себя):

    python3 tests/bench.py --jar engine-native/target/dap-native.jar \
        --port 7925 --rows 100000 --json build/bench-small.json

Против уже запущенного сервиса (свой jar не спавнится, cold start недоступен):

    DAP_PORT=7925 java -jar engine-native/target/dap-native.jar &
    python3 tests/bench.py --base http://127.0.0.1:7925 --only csv,branches --csv build/bench-10m.csv

Отдельные секции и повторные ветви:

    python3 tests/bench.py --jar engine-native/target/dap-native.jar --only cold
    python3 tests/bench.py --jar engine-native/target/dap-native.jar --only csv --rows 10000000
    python3 tests/bench.py --jar engine-native/target/dap-native.jar --only branches \
        --csv build/bench-10m.csv --branch-reps 3

Полезные флаги: `--heap` (по умолчанию `4g`), `--java` (иначе `$DAP_JDK`,
затем `$JAVA_HOME`), `--threshold`, `--warmup 0` (без прогрева), `--rss-interval`,
`--duckdb-version`, `--json`. Каждый spawned JVM гасится в `finally`; при
`--keep-data` путь к каталогу данных печатается в JSON (`dataDir`) и его надо удалить вручную.
JSON-отчёт отсортирован по ключам (`meta.schema = 1`): между прогонами меняются только
`meta.generatedAt`, `environment` и измеренные поля, поэтому диффы читаемы.

Цели Makefile (добавлены в Фазе 3):

    make bench        # 100k, быстрая проверка харнесса
    make bench-full   # 10M, отчёт build/bench.json (--keep-data; каталог данных удалить вручную)
