# BENCH — замеры dap-native

Фаза 3. Харнесс — `tests/bench.py` (Python 3, только stdlib). Пилотные числа
(10 млн строк) снимает интегратор на merged jar; в §4.1 они помечены `TBD` и для
каждой ячейки указано, какой командой и какое поле JSON её заполняет.

## 1. Статус

- Харнесс готов и провалидирован на 100 000 и 1 000 000 строк (§4.2) на ветке
  `phase3/bench`; 10M здесь сознательно не запускался.
- Размер jar на этой ветке: 46 290 332 Б = 44,1 МиБ (лимит 50 МБ, ROADMAP §9).
- Образ (< 200 МБ, healthy < 5 с) в этой среде не измерим: Docker-демона нет
  (см. §3); строка `TBD`, владелец — задача образа.

## 2. Метод

Стенд (фиксируется харнессом в `environment` JSON):

| Что | Значение |
|---|---|
| Машина | Apple M1, arm64, 8 ядер, 8 ГиБ RAM, macOS |
| JDK | Temurin 21.0.13+9 (`$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home`) |
| DuckDB | 1.5.6.0 (`duckdb_jdbc`, версия из `engine-native/pom.xml`) |
| Jar | `engine-native/target/dap-native.jar`, `-DskipTests package` |
| JVM | `-Xmx4g`, по умолчанию; loopback `127.0.0.1:<port>` |

Что и как измеряется:

| Секция | Действие | Как считается |
|---|---|---|
| `cold` | spawn `java -Xmx4g -Ddap.port=N -jar …` → первый `200 {ok:true}` от `GET /v1/health` | монотонные часы от `Popen` до успешного probe, опрос каждые 20 мс; 3 прогона, медиана + min/max |
| `csv` | детерминированный CSV → `csv_reader → row_filter(value >= 50) → group_by(grp, mean+sum value) → sorter(grp asc)` через `/v1` | четыре числа ниже |
| `branches` | один DAG с двумя независимыми `csv_reader`-ветвями vs те же два ingest'а подряд (два прогона одно-ветвевого workflow) | `ratio = median(seq) / median(parallel)`; sync-run меряет только ingest |

CSV (детерминированный, без `random`): `id` = 0…N−1 (Int64),
`value` = `(i*7919 % 100000) / 1000.0` (Float64, [0, 100)),
`grp` = `g(i % 8)` (Utf8, 8 групп), явная схема в `csv_reader`;
порог фильтра по умолчанию 50.0 (проходит ~50 % строк). Файл генерируется в temp-каталог
(10M ≈ 160 МиБ) и удаляется после прогона, если нет `--keep-data`.

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

Peak RSS: фоновый поток каждые 200 мс читает `ps -o rss= -p <pid>` (на macOS нет
`/proc`) и держит максимум; поле `csv.rssPeakBytes`. Это RSS всего JVM-процесса,
включая базовый JVM-оверхед; разрешение 200 мс пропускает короткие пики.

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
  JVM-базу. В песочнице исполнителя этой ветки `ps` был запрещён, поэтому в §4.2
  `rssPeakBytes = null` и харнесс печатает warning; на обычной машине поле заполняется.
- **Шум.** JIT (см. §2), планировщик macOS, page cache, фоновые процессы. При `--branch-reps 1`
  и прогонах < 100 мс отношение ветвей шумит (харнесс предупреждает); для стабильности —
  `--branch-reps 3` и 10M. Медианы по 3 прогонам для cold start — минимум, а не
  статистическая гарантия.
- **Диск.** 10M CSV ≈ 160 МиБ; плюс копия таблицы в DuckDB и temp-каталог. На машине с 49 ГиБ
  свободного места это нормально, но `--keep-data` оставляет файл в temp (путь — в
  `dataDir` JSON).
- **Ветви: память.** В одном DAG две `csv_reader`-ветви держат две копии таблицы; на 10M
  это ~2× памяти сессии, что при 8 ГиБ и `-Xmx4g` надо учитывать.
- **DuckDB-настройки.** `DAP_DUCKDB_MEMORY_LIMIT`/`DAP_DUCKDB_THREADS`/
  `DAP_DUCKDB_TEMP_DIR` движок на этой ветке **не читает** (харнесс проверяет это по
  `NativeService.class` и пишет `engineReadsDapDuckdbEnv: false`; значения env, если
  заданы, всё равно попадают в `environment.duckdb.settings`). Если их добавят, харнесс начнёт
  фиксировать их автоматически.

## 4. Результаты

### 4.1 Пилот: 10M строк, merged jar (`TBD`)

Команда, заполняющая **все** `TBD` ниже:

    cd engine-native
    export JAVA_HOME=$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home
    export PATH="$JAVA_HOME/bin:$PATH"
    ./mvnw -q -o -DskipTests package
    cd ..
    python3 tests/bench.py --jar engine-native/target/dap-native.jar \
        --json build/bench.json --keep-data

| Метрика | Цель (ROADMAP §9) | native, merged jar | KNIME oracle | Поле JSON |
|---|---|---|---|---|
| Cold start, медиана 3 прогонов | < 2 с | `TBD` | n/a (KNIME — desktop) | `cold.medianMs` |
| Cold start, min / max | — | `TBD` / `TBD` | n/a | `cold.minMs` / `cold.maxMs` |
| 10M sync-run (ingest) | — | `TBD` | n/a (PARITY §6.6.1) | `csv.runMs` |
| 10M `/table` (материализация конвейера) | — | `TBD` | n/a (PARITY §6.6.1) | `csv.tableMs` |
| 10M end-to-end = run + `/table` | ≥ 3× быстрее KNIME | `TBD` | n/a (PARITY §6.6.1) | `csv.totalMs` |
| 10M `/profile` финального узла | — | `TBD` | n/a | `csv.profileMs` |
| 10M `/profile` источника (полный проход) | — | `TBD` | n/a | `csv.profileSourceMs` |
| Peak RSS JVM на 10M | ≤ KNIME | `TBD` | n/a | `csv.rssPeakBytes` |
| Размер jar | < 50 МБ | 44,1 МиБ (46 290 332 Б) | n/a | `environment.jar.mib` |
| Размер образа | < 200 МБ | `TBD` (нет Docker-демона) | n/a | `docker images dap-native` |
| `docker run` healthy | < 5 с | `TBD` (нет Docker-демона) | n/a | `docker run` + poll `/v1/health` |
| Две независимые ветви: ratio | ≥ 1.8× | `TBD` (на 1M: 1,11×, см. §4.2) | n/a | `branches.ratio` |

Почему `n/a` в колонке KNIME: oracle исполняет только in-memory workflow через `/v1`;
файловые ридеры требуют workflow-context и падают, а shipped-датасетов на 10M строк нет. Это
зафиксированное ограничение ([PARITY.md](PARITY.md) §6.6.1), а не отсутствие замера.

Строки образа заполняет задача образа (Docker-демона здесь нет):

    docker images dap-native
    time docker run --rm -d --name dap-bench -p 7925:7800 dap-native
    curl -sf http://127.0.0.1:7925/v1/health

### 4.2 Валидация харнесса (не пилотные числа)

Замеры этой ветки на том же стенде, jar 44,1 МиБ, `-Xmx4g`;
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

### 4.3 Что это значит для решения по Polars

Порог из ROADMAP §6: Polars — **только** если бенчмарк узла даёт ≥ 2× против DuckDB; иначе
остаёмся на DuckDB. Текущий харнесс меряет workflow целиком (ingest + SQL-конвейер), а не
отдельный узел, поэтому решения по порогу он не даёт:

- в замерах §4.2 нет ни одного узла с измеренным ≥ 2× — наоборот, ingest упирается в
  сериализованный `loadInto` и JIT, а SQL-часть ленивая;
- между узлами нет Arrow-шины ([NATIVE.md](NATIVE.md) §4): Polars потребовал бы отдельной
  границы данных, а не «замены движка»;
- чтобы закрыть вопрос, нужен отдельный per-node замер (например, `group_by`/`sorter` на
  10M строк) — его в этой ветке нет.

Вывод: **до появления per-node числа ≥ 2× остаёмся на DuckDB**; решение по Polars принимает
интегратор по цифрам, а не харнесс.

## 5. Как воспроизвести

Сборка (offline, JDK 21; в свежем worktree `engine-native/.tools` сидируется из
основного checkout — каталог gitignored):

    cd engine-native
    export JAVA_HOME=$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home
    export PATH="$JAVA_HOME/bin:$PATH"
    ./mvnw -q -o test                 # 181 тест, 0 падений (проверка сборки)
    ./mvnw -q -o -DskipTests package  # engine-native/target/dap-native.jar
    cd ..

Пилот (10M, все секции, JSON-отчёт; ~160 МиБ CSV в temp):

    python3 tests/bench.py --jar engine-native/target/dap-native.jar \
        --json build/bench.json --keep-data

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

Предлагаемые цели Makefile (в этой ветке **не добавлены** — Makefile не редактировался):

    bench:
    	python3 tests/bench.py --jar engine-native/target/dap-native.jar --rows 100000

    bench-full:
    	python3 tests/bench.py --jar engine-native/target/dap-native.jar --json build/bench.json
