# deploy: образ dap-native

Образ нативного движка: один JVM-процесс, без данных на диске, без KNIME-рантайма.
Пункт DoD Фазы 3 — «`docker run` healthy < 5 с, образ < 200 МБ».

| Файл | Назначение |
| --- | --- |
| `Dockerfile.native` | multi-stage: сборка jar с linux-нативной DuckDB → slim-рантайм с `jlink`-JRE |
| `../engine-native/.dockerignore` | контекст = `engine-native/`: без `target/` и `.tools/` |
| `README-native.md` | этот файл: сборка, запуск, переменные, проверка и её текущий статус |

KNIME-рантайм не нужен: образ самодостаточен.

## Требования

- Docker 23+ с BuildKit/buildx (в Dockerfile используются heredoc и cache mount).
- Сеть на время `docker build`: `engine-native/mvnw` скачивает Maven 3.9.11 и
  зависимости Maven Central. Локальная офлайн-сборка (`-o`, `.tools/m2`) — только
  для проверки jar на хосте, в образ она не попадает.
- glibc: `debian:bookworm-slim` + нативная библиотека DuckDB (musl/Alpine не подходят).

## Сборка

```sh
# контекст — engine-native/, Dockerfile — в deploy/
docker build -f deploy/Dockerfile.native -t dap-native:local engine-native

# multi-arch: каждая платформа собирает jar со своей библиотекой DuckDB
docker buildx build --platform linux/amd64,linux/arm64 \
  -f deploy/Dockerfile.native -t dap-native:1.1.0 engine-native

# явный выбор библиотеки, если TARGETARCH недоступен (старый CLI / plain builder)
docker build --build-arg DAP_DUCKDB_NATIVE=linux_arm64 \
  -f deploy/Dockerfile.native -t dap-native:local engine-native
```

Что происходит:

1. **build stage** (`eclipse-temurin:21-jdk-jammy`): `mvnw -DskipTests -Ddap.duckdb.native=<...> clean package`.
   `TARGETARCH` → `amd64 | linux_amd64`, `arm64 | linux_arm64`; shade оставляет ровно
   одну нативную библиотеку DuckDB. Сборка падает, если в jar оказалось не 1
   библиотека или jar ≥ 50 МБ. Кэш Maven — в BuildKit cache mount, в образ не копируется.
2. **jlink**: JRE из 9 явных модулей (`java.base, java.instrument, java.logging,
   java.management, java.naming, java.security.jgss, java.sql, jdk.jfr,
   jdk.unsupported`; фактически 12 — `java.xml`, `java.security.sasl` и
   `java.transaction.xa` приходят транзитивно через `java.sql`),
   `--strip-debug --no-header-files --no-man-pages --compress=zip-6`. Набор получен
   из самого jar (`jdeps --multi-release 21 --ignore-missing-deps
   --print-module-deps`) и прогнан запуском jar на этом рантайме: smoke 9/9 и
   корпус parity 13/13 (см. «Проверка»). `java.desktop` исключён: его ссылки —
   необязательные Jetty leak-preventer'ы, `Jackson Java7SupportImpl` и ImageIO,
   вне пути запроса Javalin (~14 МиБ экономии).
3. **healthcheck**: крошечный `HealthCheck` (`java.base`, сокет + `GET /v1/health`)
   компилируется build-JDK в `/out/health`; curl в образе не нужен.
4. **runtime stage** (`debian:bookworm-slim`): non-root пользователь `dap`
   (uid/gid 10001, build-arg `DAP_UID`/`DAP_GID`), `/opt/dap/dap-native.jar`,
   `/opt/java/openjdk`, `/tmp/dap` — он же `DAP_DUCKDB_TEMP_DIR` (переменная уже
   реализована и выставлена в образе).

## Платформа и DuckDB native

Имена файлов DuckDB: `linux_amd64` (x86_64) и `linux_arm64` — именно они
передаются в `-Ddap.duckdb.native`. Это не то же, что профили хоста в `pom.xml`:
там профиль для Linux aarch64 называется `duckdb-native-host-linux-aarch64`
(JVM на Linux сообщает `os.arch=aarch64`, а имя файла DuckDB — `linux_arm64`).
При сборке внутри Linux-контейнера профиль хоста сработал бы и сам, но образ
выбирает библиотеку **явно** через `TARGETARCH`, чтобы multi-arch сборка была
предсказуемой и не зависела от того, какая платформа у builder'а.

## Запуск

```sh
docker run -d --name dap-native -p 7800:7800 dap-native:local
curl -fsS http://127.0.0.1:7800/v1/health
docker inspect -f '{{.State.Health.Status}}' dap-native
docker images dap-native:local
docker stop dap-native
```

Read-only (рекомендуется: сервису не нужны данные на диске, кроме `/tmp`):

```sh
docker run --rm --read-only --tmpfs /tmp:size=256m --cap-drop ALL \
  -p 7800:7800 dap-native:local
```

Безопасность: auth в пилоте нет, движок по умолчанию слушает `127.0.0.1`.
Образ выставляет `DAP_BIND=0.0.0.0` (иначе порт из контейнера недоступен);
публикуйте 7800 осознанно (loopback на хосте или reverse proxy с TLS).

## Переменные окружения

| Переменная | По умолчанию в образе | Статус | Смысл |
| --- | --- | --- | --- |
| `DAP_PORT` | `7800` | есть | HTTP-порт; его же проверяет HEALTHCHECK |
| `DAP_BIND` | `0.0.0.0` | есть | адрес прослушивания (по умолчанию в коде — `127.0.0.1`) |
| `DAP_HEAP` | `1g` | есть (entrypoint) | только `-Xmx`; `JAVA_OPTS` его перекрывает |
| `JAVA_OPTS` | — | есть (entrypoint) | произвольные флаги JVM, например `-Xmx4g -XX:+UseZGC` |
| `DAP_MAX_WORKFLOWS` | `100` (код) | есть | лимит workflow на сессию |
| `DAP_JOB_TIMEOUT` | `600` (код) | есть | таймаут job по умолчанию, секунды |
| `DAP_DUCKDB_MEMORY_LIMIT` | — (по умолчанию DuckDB) | реализовано | `memory_limit` DuckDB; лимит **на сессию** |
| `DAP_DUCKDB_THREADS` | — (по умолчанию DuckDB) | реализовано | `SET threads` |
| `DAP_DUCKDB_TEMP_DIR` | `/tmp/dap` | реализовано | `temp_directory` для out-of-core |

Лимит памяти — на сессионную БД: при нескольких сессиях потолок ≈ сессии × лимит,
поэтому в контейнере задавайте его явно (`-e DAP_DUCKDB_MEMORY_LIMIT=2GB`) и
учитывайте, что CSV-ингест живёт в JVM heap (`-Xmx`/`DAP_HEAP`), а не в этом лимите.
`DAP_DUCKDB_TEMP_DIR=/tmp/dap` уже выставлен в образе; под `--read-only` смонтируйте
записываемый `/tmp` (`--tmpfs /tmp`). Детали и SQL — docs/ARCHITECTURE.md.

HEALTHCHECK: `--interval=2s --timeout=2s --start-period=5s --retries=3`,
команда — `/opt/java/openjdk/bin/java -cp /opt/dap/health HealthCheck` (`java.base`-сокет, без curl).
Первый прогон — через интервал после старта, поэтому статус `healthy` ожидается
примерно через 2–4 с. Внутри проба проверяет `GET /v1/health` → 200.

## Размер образа

Слагаемые (все числа — с этой машины, macOS arm64, JDK 21.0.13+9, кроме базового слоя):

| Слой | Размер | Как получено |
| --- | --- | --- |
| `debian:bookworm-slim` (linux/amd64) | 77 946 880 Б ≈ 74,3 МиБ | слой образа из registry (gzip 28 247 437 Б), измерено `len(gzip.open(...).read())` |
| jlink-JRE (12 модулей: 9 явных + 3 транзитивных, strip+zip-6) | 39 096 КиБ = 40 034 304 Б ≈ 38,2 МиБ | `du -sk` по выводу `jlink`; для linux/amd64 — оценка ±несколько МБ |
| `dap-native.jar` (linux_amd64) | 34 043 292 Б ≈ 32,5 МиБ | `ls -l`; одна native-библиотека `libduckdb_java.so_linux_amd64` (60 794 208 Б внутри) |
| `HealthCheck.class` | 2 695 Б | `ls -l` |
| `useradd`/каталоги | < 10 КБ | — |

Итого ≈ **152 МБ несжатого rootfs** (145 МиБ), предел — 200 МБ, запас ≈ 48 МБ.
При `docker images` на containerd-store может показываться сжатая сумма (~95–100 МБ);
обе метрики ниже предела. Оценка не заменяет `docker images` на Docker-хосте
(см. ниже): linux-размер jlink-JRE измерению здесь недоступен.

## Проверка: что проверено, а что нет

Проверено здесь (macOS arm64, **без Docker-демона**: `docker info`
→ `failed to connect to the docker API ... no such file or directory`):

| Команда | Результат | Статус |
| --- | --- | --- |
| `./mvnw -q -o -DskipTests -Ddap.duckdb.native=linux_amd64 clean package` | jar 34 043 292 Б, в `unzip -l` ровно одна запись `libduckdb_java.so_linux_amd64` | ✅ 30.10.2026 |
| то же с `-Ddap.duckdb.native=linux_arm64` | jar 31 970 289 Б, ровно одна `libduckdb_java.so_linux_arm64` | ✅ 30.10.2026 |
| `jdeps --multi-release 21 --ignore-missing-deps --print-module-deps target/dap-native.jar` | `java.base,java.desktop,java.instrument,java.management,java.naming,java.security.jgss,java.sql,jdk.jfr,jdk.unsupported` | ✅ |
| `jlink --add-modules ... --strip-debug --no-header-files --no-man-pages --compress=zip-6` | 39 096 КиБ | ✅ |
| запуск jar на этом jlink-рантайме + `python3 tests/native_smoke.py --base http://127.0.0.1:7882` | `native smoke (9 pass, 0 fail, 0 skip)` | ✅ |
| `python3 tests/run_goldens.py` на том же jlink-рантайме | 13/13 workflow ok, `matching cells 1563/1563 (100.0 %)` — все 11 узлов, включая `excel_reader`, `joiner`, `math_formula` | ✅ |
| `java -cp /tmp/hc HealthCheck` против живого сервиса / против закрытого порта | код 0 / код 1 + `Connection refused` | ✅ |
| entrypoint-развёртывание heap: `HEAP=${DAP_HEAP:-1g}; ${JAVA_OPTS:--Xmx$HEAP}` | нет env → `-Xmx1g`; `DAP_HEAP=2g` → `-Xmx2g`; `JAVA_OPTS=-Xmx4g -XX:+UseZGC` → как задано; оба → `JAVA_OPTS` | ✅ (sh/POSIX) |
| cold start на jlink-рантайме (fork → health 200, опрос каждые 5 мс) | 556, 575, 574, 560, 587 мс (5 прогонов); первый прогон сразу после `jlink` — 2820 мс (macOS валидация бинарей) | ✅ |

Точные команды локальной проверки (из `engine-native/`; `.tools/m2` — gitignored,
засеян из основного чекаута):

```sh
export JAVA_HOME="$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
export DAP_TOOLS="$PWD/.tools" DAP_M2="$PWD/.tools/m2"
./mvnw -q -o -DskipTests -Ddap.duckdb.native=linux_amd64 clean package
unzip -l target/dap-native.jar | grep libduckdb_java
# для запуска на macOS — host-профиль (osx_universal), он же проверяет модули jlink:
./mvnw -q -o -DskipTests clean package
jdeps --multi-release 21 --ignore-missing-deps --print-module-deps target/dap-native.jar
jlink --add-modules java.base,java.instrument,java.logging,java.management,java.naming,\
java.security.jgss,java.sql,jdk.jfr,jdk.unsupported \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /tmp/jre
DAP_PORT=7871 /tmp/jre/bin/java -jar target/dap-native.jar &
python3 ../tests/native_smoke.py --base http://127.0.0.1:7871
```

**Не проверено (нужен Docker-хост/демон):**

1. `docker build -f deploy/Dockerfile.native ...` — синтаксис и слои целиком;
   сеть до Maven Central внутри build stage; `useradd`/jlink в linux-образе.
2. Размер образа `docker images dap-native:local` < 200 МБ.
3. `docker run`: статус `healthy` < 5 с (ожидание 2–4 с), работа под non-root,
   `--read-only --tmpfs /tmp`.
4. Multi-arch сборка/манифест (`linux/amd64` + `linux/arm64`), запуск arm64-образа
   на arm64-хосте и amd64-образа на amd64-хосте.
5. Отсутствие `java.desktop` на всех путях: проверено smoke 9/9 и полным корпусом
   parity (13 workflow, 1563/1563 ячеек), но не ручными сценариями вне корпуса.
   При проблеме вернуть `java.desktop` в `--add-modules` (+~14 МиБ).

Команды для проверки на Docker-хосте:

```sh
docker build -f deploy/Dockerfile.native -t dap-native:local engine-native
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' dap-native:local   # < 200MB
docker run -d --name dap-native-check -p 7801:7800 dap-native:local
start=$(date +%s%N)
until [ "$(docker inspect -f '{{.State.Health.Status}}' dap-native-check)" = healthy ]; do
  sleep 0.1
done
echo "healthy in $(( ($(date +%s%N) - start) / 1000000 )) ms"
curl -fsS http://127.0.0.1:7801/v1/health
docker run --rm --read-only --tmpfs /tmp:size=256m --cap-drop ALL \
  -p 7802:7800 dap-native:local &   # поднять и повторить curl
docker rm -f dap-native-check
```

## Цели Makefile

Цели для образа, MCP e2e и бенчмарка уже добавлены в `Makefile`:

```make
DAP_NATIVE_IMAGE ?= dap-native:local

image-native:
	docker build -f deploy/Dockerfile.native -t $(DAP_NATIVE_IMAGE) engine-native

run-native-image: image-native
	docker run --rm -p 7800:7800 $(DAP_NATIVE_IMAGE)

mcp-e2e: build-native
	python3 tests/mcp_e2e.py

bench: build-native
	python3 tests/bench.py --jar engine-native/target/dap-native.jar --rows 100000
```

`make bench-full` — тот же харнесс на 10M с отчётом `build/bench.json`.
`make test-native` не меняется: образ — упаковка уже протестированного jar.
Отдельной цели `verify-native-image` нет: health и размер проверяются командами из
предыдущего раздела после `make image-native`.
