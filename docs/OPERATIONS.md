# Эксплуатация DAP

Практическое руководство: как запускать, пересобирать, проверять и диагностировать сервис.
Контракт API — [API.md](API.md), архитектура — [../ARCHITECTURE.md](../ARCHITECTURE.md).

## 1. Что запускается

Один JVM-процесс:

```text
java -Xmx$DAP_HEAP -jar runtime/knime/plugins/org.eclipse.equinox.launcher_*.jar \
  -configuration runtime/knime/configuration \
  -data $DAP_DATA \
  -application com.dap.engine.service \
  -port=$DAP_PORT -bind=$DAP_BIND \
  -catalog=catalog/catalog.json \
  -schemas=catalog/schemas \
  -curated=catalog/curated.json \
  -jobTimeoutSec=$DAP_JOB_TIMEOUT \
  -maxWorkflowsPerSession=$DAP_MAX_WORKFLOWS \
  -consoleLog
```

Это ровно то, что делает `scripts/run.sh`. Процесс блокирующий: логи идут в stdout/stderr.

## 2. Переменные окружения

Переменные читает `scripts/run.sh` (и частично `scripts/build-*.sh`); сам движок получает
их как аргументы командной строки.

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `DAP_PORT` | `7800` | HTTP-порт |
| `DAP_BIND` | `127.0.0.1` | адрес прослушивания; `0.0.0.0` — только за доверенным прокси |
| `DAP_DATA` | `<repo>/workspace` | Eclipse workspace: `.metadata`, логи KNIME |
| `DAP_HEAP` | `3g` | `-Xmx` для JVM |
| `DAP_JOB_TIMEOUT` | `600` | таймаут job по умолчанию, секунды |
| `DAP_MAX_WORKFLOWS` | `100` | лимит workflow на сессию |
| `DAP_JDK` | `$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home` | JDK 21 для сборки и запуска |
| `DAP_ROOT` | `$HOME/Projects/dap` | корень для `gen-bundles-info.py` (не для run.sh) |
| `DAP_RUNTIME` | `$DAP_ROOT/runtime/knime` | runtime для `gen-bundles-info.py` |
| `KNIME_SERVICE_PORT` | `7800` | порт, который использует MCP-адаптер |

Пример:

```bash
DAP_PORT=7810 DAP_HEAP=6g DAP_BIND=127.0.0.1 make run
```

JDK 21 обязателен: bundle `com.dap.engine` объявляет `JavaSE-21`, а KNIME 5.12 собрана под 21.

## 3. Запуск и остановка

### Обычный запуск

```bash
make run
# или
./scripts/run.sh
```

В фоне:

```bash
nohup ./scripts/run.sh > /var/log/dap.log 2>&1 &
echo $! > /run/dap.pid
```

### Проверка готовности

```bash
until curl -sf http://127.0.0.1:7800/v1/health >/dev/null; do sleep 1; done
curl -s http://127.0.0.1:7800/v1/health
```

Ответ содержит `nodes`, `coreNodes`, `workflows`, `sessions`, `jobs` и `memory`. Если
`nodes` равен 0 — каталог не загрузился.

### Остановка

```bash
kill "$(cat /run/dap.pid)"     # SIGTERM: сервер останавливается, jobs отменяются
curl -X POST http://127.0.0.1:7800/v1/shutdown   # штатное завершение через API
```

Оба способа теряют workflow и jobs, не сохранённые через `POST .../save`. `SIGKILL`
оставляет `runtime/knime/configuration` в промежуточном состоянии; при проблемах удалите
`runtime/knime/configuration/org.eclipse.osgi` (см. разделы 3 и 4).

## 4. Холодный старт

Первый старт на новом runtime дольше обычного:

1. Equinox разрешает 610 бандлов — десятки секунд.
2. При первом `WorkflowManager` распаковываются `.cp`-ресурсы в
   `runtime/knime/configuration/org.eclipse.osgi`.
3. `catalog.json` читается из кэша; если его нет — строится (дорого).

Наблюдаемая проблема: если создать workflow сразу после `/v1/health` на **пустом**
configuration-кэше, статическая инициализация `WorkflowManager` может упасть с
`NoSuchFileException: .../lib/schemas.jar`, а затем процесс до перезапуска отвечает
`500 NoClassDefFoundError: Could not initialize class ... WorkflowManager`. Это гонка
распаковки, а не ошибка каталога.

Правило эксплуатации:

```bash
# после health — прогрев
until curl -sf http://127.0.0.1:7800/v1/health >/dev/null; do sleep 1; done
WID=$(curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"name":"warmup"}' http://127.0.0.1:7800/v1/workflows | sed 's/.*"id":"\([^"]*\)".*/\1/')
curl -s -X DELETE "http://127.0.0.1:7800/v1/workflows/$WID" >/dev/null
```

Если `NoClassDefFoundError` уже произошёл — только перезапуск JVM. Для OCI-образа
рекомендуется прогревать runtime при сборке (запустить сервис, создать/удалить workflow,
остановить) и включать `configuration/org.eclipse.osgi` в образ.

## 5. Пересборка каталога и схем

```bash
make build-catalog
```

Скрипт `scripts/build-catalog.sh`:

1. запускает приложение `com.dap.engine.catalog` → `catalog/catalog.json`;
2. запускает `com.dap.engine.audit` → `catalog/schemas/*.json` и
   `catalog/schema-report.json`;
3. печатает `nodes=… core=… available=…`.

Ориентиры текущего дерева: **832** узла, **141** core, **733** доступные схемы,
**827** уникальных JSON-файлов (5 повторных регистраций фабрик), **99** узлов без схемы,
**21** с `settingsUnavailable`. Прогон аудита занимает около 22 секунд.

`core` в отчёте — снимок на момент аудита. Источник истины — `catalog/curated.json`,
который применяется при старте и определяет tier в каталоге. Фактическое число отдаёт
`GET /v1/health` → `coreNodes`;
список формирует `scripts/select-curated.py`.

Важно:

- каталог и схемы — артефакты сборки; не редактируйте `schemas/*.json` руками;
- после пересборки **перезапустите сервис**: предрасчитанные схемы читаются с диска,
  а `dialogCache` живёт в памяти;
- `catalog/` и `catalog/schemas/` не игнорируются git — это контрактные артефакты;
- для сборки нужен только JDK, установленный KNIME не нужен (runtime уже vendored).

Точечная пересборка каталога без схем:

```bash
JDK="${DAP_JDK:-$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home}"
"$JDK/bin/java" -Xmx3g -jar runtime/knime/plugins/org.eclipse.equinox.launcher_*.jar \
  -configuration runtime/knime/configuration -data "$(mktemp -d)" \
  -application com.dap.engine.catalog -out=catalog/catalog.json -consoleLog
```

## 6. Пересборка движка

```bash
make build-engine
```

`scripts/build-engine.sh` компилирует `engine/src/main/java` против всех jar из
`runtime/knime/plugins`, собирает `build/com.dap.engine_1.0.0.jar` и кладёт его в
`runtime/knime/plugins/`, затем перегенерирует `bundles.info`/`config.ini`.

- Не собирайте движок при работающем сервисе: замена jar под работающей JVM не даст эффекта
  до перезапуска.
- Скрипт использует `DAP_JDK`; путь по умолчанию — `$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home`.
- `gen-bundles-info.py` пишет **абсолютные** пути. При переносе каталога (OCI, другой
  пользователь) запустите его с `DAP_ROOT`, указывающим на новый корень, иначе runtime
  будет ссылаться на старые пути.
- Обновление самого runtime — `scripts/vendor-runtime.sh` (нужен установленный KNIME,
  только для сопровождающих); после него обязательны `make build-catalog` и `make test`.

### Тесты

```bash
make test                 # python3 tests/run_all.py
python3 tests/run_all.py --keep   # оставить тестовый сервис на :7810
```

`tests/run_all.py` поднимает отдельный экземпляр на `127.0.0.1:7810` с данными в
`/tmp/dap-golden-tests` и не трогает резидентный сервис на 7800. Порт 7810 должен быть
свободен. Набор включает контракт `/v1` и golden-сценарии (`tests/golden/*.py`); SKIP
печатаются и не считаются падением. Подробности — [tests/README.md](../tests/README.md).

## 7. OCI и переносимость

`deploy/` содержит `Dockerfile`, `docker-compose.yaml` и собственный README; упаковка —
`scripts/package-oci.sh`.

```bash
make image                          # multi-arch OCI tar -> build/dap-1.0.0.tar
./scripts/package-oci.sh -s         # single-arch, docker --load
./scripts/package-oci.sh -h         # тег, платформы, путь архива
```

Что делает образ (`deploy/Dockerfile`):

- базовый `eclipse-temurin:21-jre-jammy`, пользователь `dap` (uid/gid 10001), `/opt/dap`;
- копирует `runtime/knime`, `build/com.dap.engine_1.0.0.jar`, `catalog/`, `adapters/mcp`,
  `scripts/run.sh`, `scripts/gen-bundles-info.py`;
- **перегенерирует** `bundles.info`/`config.ini` с `DAP_ROOT=/opt/dap` — иначе в образ
  попадут абсолютные пути машины сборки;
- удаляет хостовые `configuration/org.eclipse.osgi` и `configuration/org.eclipse.core.runtime`;
- запускается от `dap`, том `/opt/dap/workspace`, `EXPOSE 7800`.

Запуск:

```bash
docker run -d --name dap -p 7800:7800 -v dap-workspace:/opt/dap/workspace dap:1.0.0
# или
docker compose -f deploy/docker-compose.yaml up -d --build
```

Переменные образа: `DAP_BIND=0.0.0.0`, `DAP_HEAP=2g`, `DAP_DATA=/opt/dap/workspace`,
`DAP_JDK=/opt/java/openjdk`; healthcheck — `curl -fsS http://127.0.0.1:7800/health`
(`/health` и `/v1/health` — алиасы), `start_period: 60s`.

Практические замечания:

1. Multi-arch требует Docker 23+ с buildx; на не-Desktop Docker может понадобиться
   `docker buildx create --driver docker-container`.
2. Vendored runtime собран из macOS-установки KNIME и содержит только cocoa SWT. На Linux
   `org.knime.workbench.core` не стартует (`SWTError`), сервис пишет
   `[service] workbench.core not started` и продолжает работу; каталог и `/v1/*` не
   затронуты. Для UI-совместимости нужен Linux-набор SWT-бандлов.
3. `coreNodes` в образе фиксируется на момент сборки из `catalog/curated.json`, а не из
   `schema-report.json`.
4. Порт `7800`; наружу — только через аутентифицирующий прокси (у сервиса нет auth).
5. Сохранённые workflow и `workspace/.metadata` — на volume.
6. Ориентиры: OCI-архив ~1,3 ГБ (amd64+arm64), single-arch image ~1,7 ГБ.

Полная процедура и проверенные результаты — [deploy/README.md](../deploy/README.md).

## 8. Логи и диагностика

| Источник | Что там |
|---|---|
| stdout/stderr процесса | `[dap] listening…`, `[service]…`, `[catalog]…`, `[probe]…`, стектрейсы |
| `$DAP_DATA/.metadata/knime/knime.log` | лог KNIME, предупреждения узлов |
| `/v1/health` | счётчики, память |
| `catalog/schema-report.json` | покрытие схем, причины отказов, `details` по узлам |
| `GET /v1/workflows/{id}/debug` | порты, классы портов, соединения |

Типовые проверки:

```bash
curl -s http://127.0.0.1:7800/v1/health
curl -s http://127.0.0.1:7800/v1 | head
curl -s 'http://127.0.0.1:7800/v1/nodes?q=csv&limit=3'
python3 - <<'PY'
import json
r=json.load(open('catalog/schema-report.json'))
print({k:r[k] for k in ('nodes','core','available','swing','annotations','fields','labelled')})
print(r['reasons'])
PY
```

Проверка конкретного узла:

```bash
NODE=org.knime.base.node.io.filehandling.csv.reader.CSVTableReaderNodeFactory
curl -s "http://127.0.0.1:7800/v1/nodes/$NODE" | head -c 2000
curl -s "http://127.0.0.1:7800/v1/nodes/$NODE/dialog" | head -c 2000
```

## 9. Типовые проблемы

| Симптом | Причина и действие |
|---|---|
| `Connection refused` | сервис не запущен или другой `DAP_PORT`/`DAP_BIND` |
| `nodes: 0` в health | `catalog.json` не найден/битый; пересоберите каталог |
| 500 `Could not initialize class WorkflowManager` | гонка холодного старта; перезапустите JVM и прогрейте |
| 500 `too many active jobs` | жёсткий лимит 8; дождитесь завершения или отмените jobs |
| 500 `workflow limit for session` | превышен `DAP_MAX_WORKFLOWS`; удалите workflow или поднимите лимит |
| 408 на sync `run` | `timeoutSec`/`DAP_JOB_TIMEOUT`; используйте async и опрос jobs |
| 400 `unknown setting` | ключ не из `settingsXml`; смотрите список `available` в ответе |
| 400 `node was not created through this service` | узел появился не через API (например, из загруженного workflow) |
| 400 `invalid session id` | `X-DAP-Session` не соответствует `[A-Za-z0-9._-]{1,64}` |
| 409 при `DELETE /jobs/{id}` | job уже завершён; отмена невозможна |
| Долгий первый запрос | инициализация OSGi/KNIME; прогрейте сервис |
| `OutOfMemoryError` | увеличьте `DAP_HEAP`; профиль читает таблицу целиком |
| MCP-адаптер не стартует сервис | автостарт использует устаревшие пути/application id; запускайте `make run` вручную |

## 10. Ограничения эксплуатации

- Нет аутентификации, авторизации, TLS и rate limiting. Только loopback или доверенный прокси.
- Один worker: тяжёлый workflow блокирует остальные.
- Синхронный `run` блокирует HTTP-диспетчер: healthcheck может «зависнуть» на время запуска.
- Состояние в памяти; резервное копирование — только `save` в каталог KNIME.
- `profile` читает всю таблицу и считает `distinct` до 100 000 значений на колонку; на
  больших данных это дорого.
- Runtime ≈662 МБ; холодный старт — десятки секунд.
- GPLv3: распространение образа накладывает обязанности по исходникам (см. [NOTICE](../NOTICE)).
