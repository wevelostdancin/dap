# DAP

**DAP** — локальный аналитический бэкенд для агента: один JVM-процесс с HTTP API
`/v1` и stdio MCP-адаптер. Агент собирает workflow из JSON-узлов, запускает его и
получает таблицу и профиль колонок. Вычисления идут в процессе, данные не покидают
машину.

Движок: Javalin 6.7 + DuckDB 1.5.6 (in-memory) + Arrow 19.0, Java 21. Каталог —
44 узла `dap.*`, контракт — 27 операций `/v1`. Версия: **1.0.0**.

## Что это

- **Аналитика по запросу.** Адаптер поднимает движок при первом вызове; отдельного
  демона, Docker и внешней БД в ежедневном пути нет.
- **Два интерфейса.** MCP (stdio, 22 инструмента `dap_*`) для агента и HTTP `/v1`
  для скриптов и тестов.
- **Узлы как данные.** Узел описывается JSON-спекой: id, порты, `configSchema`.
  Агент читает схему настроек и собирает граф без XML и без GUI.
- **44 узла.** IO (4 читателя, 4 писателя), очистка (фильтры, дедупликация,
  замена значений, приведение типов, порядок колонок, выбросы), преобразование
  (дата/время, правила, бины, нормализация, кодирование, выборка), агрегация и форма
  (group_by, построчные агрегаты, pivot/unpivot, window, top-N, joiner/concatenate),
  статистика (describe, value_counts, correlation, histogram, quantile, outlier_detect),
  временные ряды (resample, rolling, diff, date_range).
- **DuckDB внутри.** Преобразования — ленивые SQL-отношения; данные материализуются
  только при чтении таблицы или профиля.
- **Parquet-кэш.** `POST /v1/datasets` (инструмент `dap_ingest`) один раз конвертирует
  CSV/Excel/JSON/Parquet в `$DAP_HOME/build/cache` (или `~/.dap/cache`); повторный
  анализ читает Parquet.

DAP не является GUI, многопользовательским сервисом или облачным решением.
Пользователь один, данные локальные, интерфейс — чат агента.

## Требования

- **JDK 21** (проверено на Temurin 21.0.13). Путь задаётся `DAP_JDK`.
- **Python 3.10+** — для MCP-адаптера и проверок; только стандартная библиотека.
- macOS или Linux, x86_64/aarch64. Jar содержит native-библиотеку DuckDB платформы
  сборки; для кросс-платформенной упаковки — `-Ddap.duckdb.native=all`.
- Внешняя БД и Docker не нужны.

## Быстрый старт

```bash
export DAP_JDK=/path/to/jdk-21        # по умолчанию ~/Projects/tools/jdk-21.0.13+9/Contents/Home

make build-native                     # engine-native/target/dap-native.jar (~44 MiB)
make run-native                       # 127.0.0.1:7800, блокирующий процесс
```

В другом терминале:

```bash
curl -s http://127.0.0.1:7800/v1/health
```

```json
{"ok":true,"name":"DAP","version":"1.0.0","api":"1","nodes":44,"coreNodes":44,
 "workflows":0,"sessions":0,"jobs":0,"memory":{"usedMb":…,"maxMb":…}}
```

(`memory` — текущие `usedMb`/`maxMb` JVM.)

Cold start — около 0.4–0.5 с от `java -jar` до первого `200`. Остановка —
`POST /v1/shutdown`, сигнал процессу или простой дольше `DAP_IDLE_TIMEOUT_SEC`.

Переменные: `DAP_PORT` (7800), `DAP_BIND` (`127.0.0.1`; также `localhost` и
`0.0.0.0`), `DAP_MAX_WORKFLOWS` (100 на сессию), `DAP_JOB_TIMEOUT` (600 с),
`DAP_IDLE_TIMEOUT_SEC` (900 с; `0` выключает автозавершение),
`DAP_DUCKDB_MEMORY_LIMIT`, `DAP_DUCKDB_THREADS`, `DAP_DUCKDB_TEMP_DIR`,
`DAP_CACHE_DIR` (Parquet-кэш; по умолчанию `$DAP_HOME/build/cache` при заданном
`DAP_HOME`, иначе `~/.dap/cache`; в sandbox — `$TMPDIR/dap-cache`).

## Пример вызова

```bash
B=http://127.0.0.1:7800

# 1. workflow
curl -s -X POST $B/v1/workflows -H 'Content-Type: application/json' \
  -d '{"name":"demo"}'
# {"id":"wf-1","name":"demo","session":"default","nodes":0}

# 2. источник
curl -s -X POST $B/v1/workflows/wf-1/nodes -H 'Content-Type: application/json' -d '{
  "factory":"dap.csv_reader",
  "settings":{"file":"/data/iris.csv","delimiter":",","header":true}
}'
# {"node":"1:1","name":"CSV Reader"}

# 3. фильтр
curl -s -X POST $B/v1/workflows/wf-1/nodes -H 'Content-Type: application/json' -d '{
  "factory":"dap.row_filter",
  "settings":{"logic":"and","where":[{"column":"species","op":"eq","value":"setosa"}]}
}'
# {"node":"1:2","name":"Row Filter"}

# 4. связь и запуск
curl -s -X POST $B/v1/workflows/wf-1/connect -H 'Content-Type: application/json' \
  -d '{"from":"1:1","to":"1:2"}'
curl -s -X POST $B/v1/workflows/wf-1/run -H 'Content-Type: application/json' -d '{}'
# {"ok":true,"durationMs":…,"workflow":"wf-1","nodes":[{"node":"1:1","name":"CSV Reader","state":"EXECUTED"}, …]}

# 5. таблица и профиль
curl -s "$B/v1/workflows/wf-1/nodes/1:2/table?limit=5"
curl -s "$B/v1/workflows/wf-1/nodes/1:2/profile"
```

Идентификатор узла — `<номер workflow>:<индекс>`, например `1:2` для `wf-1`.
Сессия — заголовок `X-DAP-Session` или `?session=`, по умолчанию `default`.
Асинхронный запуск — `POST .../run?async=true` или `POST .../jobs` (202 + `job-N`).

## MCP-адаптер

Адаптер — тонкий stdio-транспорт JSON-RPC 2.0; вычисления остаются в движке.

```bash
python3 adapters/mcp/dap_mcp.py
```

Если движок не отвечает на `/v1/health`, адаптер запускает jar сам и ждёт готовности
до 60 с; уже работающий сервис не перезапускает. Конфигурация MCP-клиента:

```json
{
  "mcpServers": {
    "dap": {
      "command": "python3",
      "args": ["/absolute/path/to/dap/adapters/mcp/dap_mcp.py"],
      "env": {
        "DAP_HOME": "/absolute/path/to/dap",
        "DAP_PORT": "7800",
        "DAP_SESSION": "agent"
      }
    }
  }
}
```

Подключить одной командой (stdlib-CLI, идемпотентно, с бэкапом и `--dry-run`):

```bash
python3 adapters/mcp/dap_cli.py install --client hermes   # или --client dsh
python3 adapters/mcp/dap_cli.py doctor --client hermes
```

Дистрибутив: `make dist` → `dist/` (jar, адаптер, CLI, `SHA256SUMS`); проверка
установки из него — `make install-e2e`. Подробности — [docs/MCP.md](docs/MCP.md).

Инструменты: `dap_search_nodes`, `dap_node_dialog_schema`, `dap_workflow_create`,
`dap_add_node`, `dap_connect`, `dap_run`, `dap_get_table`, `dap_profile_table`,
`dap_ingest`, `dap_save_workflow`, `dap_load_workflow`, `dap_shutdown` и остальные
`dap_*` — 22 штуки. Полный список с аргументами —
[adapters/mcp/README.md](adapters/mcp/README.md).

## Проверки

```bash
make test-native    # JUnit: 401 тест
make goldens        # 15 golden-workflow на отдельном экземпляре :7811
make smoke          # чёрный smoke по HTTP против запущенного движка (DAP_BASE)
make mcp-e2e        # create → add_node → connect → run → table → delete через MCP
make install-e2e    # установка из dist/ во временный HOME + stdio preflight
make check          # test-native + goldens + lint
```

## Структура репозитория

```
engine-native/   движок: Java 21, specs/*.json, JUnit-тесты
adapters/mcp/    stdio MCP-адаптер (Python, только stdlib)
api/             контракт /v1: openapi.yaml
docs/            ARCHITECTURE, NODES, QUALITY, ROADMAP, API, MCP, BENCH
tests/           goldens-native/, native_smoke.py, mcp_e2e.py, install_e2e.py, bench.py
deploy/          Dockerfile.native (образ не проверен без Docker-демона)
scripts/         статические проверки репозитория
Makefile         build-native, test-native, run-native, goldens, dist, check
```

## Документация

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — компоненты, жизненный цикл запроса,
  память и ресурсы DuckDB, отмена, MCP-адаптер.
- [docs/NODES.md](docs/NODES.md) — 44 узла: назначение, config, примеры JSON.
- [docs/QUALITY.md](docs/QUALITY.md) — unit-тесты, goldens, правила сравнения,
  контракт ошибок, критерий готовности узла.
- [docs/ROADMAP.md](docs/ROADMAP.md) — план продукта и пул узлов.
- [docs/API.md](docs/API.md) — человекочитаемый контракт `/v1`;
- [docs/MCP.md](docs/MCP.md) — подключение к клиенту (stdio, инструменты, lifecycle);
  [api/openapi.yaml](api/openapi.yaml) — машинный.
- [docs/BENCH.md](docs/BENCH.md) — замеры ingest, cold start, памяти.

## Ограничения

- Один пользователь, аутентификации нет: сервис слушает loopback и не должен
  выставляться в сеть без прокси.
- Состояние в памяти: workflow, задания и таблицы исчезают при остановке процесса;
  `POST .../save` пишет `workflow.json`, `POST /v1/workflows/load` читает его обратно.
  На диске остаётся только явно сохранённый `workflow.json` и Parquet-кэш ingest.
- Остановка — явная (`POST /v1/shutdown`, сигнал процессу) или автоматическая по
  простою: `DAP_IDLE_TIMEOUT_SEC` (900 с по умолчанию, `0` выключает), если нет
  активных jobs.
- Каталог — 44 узла; файловые источники читают любой путь, доступный процессу.
- Память ingest живёт в JVM heap: для 10 млн строк нужно ~4,65 ГБ heap, и
  `DAP_DUCKDB_MEMORY_LIMIT` его не ограничивает.

## Лицензия

Текст лицензии — [LICENSE](LICENSE). Движок собирается из собственного кода и
сторонних библиотек, перечисленных в [engine-native/pom.xml](engine-native/pom.xml);
гарантий нет.
