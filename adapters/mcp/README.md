# DAP MCP-адаптер

stdio-сервер [Model Context Protocol](https://modelcontextprotocol.io) над HTTP API
движка DAP (`/v1`). Тонкий транспорт: адаптер ничего не считает сам — ищет узлы в
каталоге, собирает workflow, запускает задания и читает таблицы через движок.

- Python 3.10+, только стандартная библиотека (без pip-зависимостей).
- JSON-RPC 2.0, по одному объекту на строку: запросы в stdin, ответы в stdout.
- Инструменты — только `dap_*` (19 штук). Ошибки движка приходят как JSON-RPC `error`.

## Быстрый старт

```bash
# движок уже слушает 127.0.0.1:7800 — просто запускаем адаптер
python3 /Users/vitalypesnev/Projects/dap/adapters/mcp/dap_mcp.py

# если движка нет, адаптер поднимет его сам при первом вызове инструмента
DAP_SESSION=claude python3 adapters/mcp/dap_mcp.py
```

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `DAP_HOME` | `~/Projects/dap` | корень репозитория DAP: здесь ищутся `scripts/run.sh` и пишется `service.log` |
| `DAP_PORT` | `7800` | порт движка |
| `DAP_SESSION` | `default` | сессия агента, уходит заголовком `X-DAP-Session` в каждом запросе |
| `DAP_BIND` | `127.0.0.1` | адрес движка; `0.0.0.0` и `::` для запросов заменяются на `127.0.0.1` |
| `DAP_NATIVE` | — | `1` — поднимать нативный `dap-native.jar` вместо legacy KNIME |
| `DAP_ENGINE` | — | `native` — то же, что `DAP_NATIVE=1`; иное значение — legacy |
| `DAP_HEAP` | `2g` (native) | `-Xmx` для нативного jar; в legacy его читает сам `run.sh` (по умолчанию `3g`) |

Базовый URL: `http://$DAP_BIND:$DAP_PORT`. Таймаут HTTP-запроса — 600 с.

## Автозапуск движка

При первом `tools/call` адаптер проверяет `GET /v1/health`:

1. движок отвечает `200` — работаем с ним;
2. не отвечает — запускает движок (stdout и stderr — в `$DAP_HOME/service.log`) и
   ждёт health до 60 с:
   - по умолчанию — `bash "$DAP_HOME/scripts/run.sh"` (legacy KNIME);
   - при `DAP_NATIVE=1` или `DAP_ENGINE=native` — `java -Xmx$DAP_HEAP -jar
     engine-native/target/dap-native.jar` (см. ниже).

Уже запущенный сервис адаптер не перезапускает и не останавливает: при выходе
движок продолжает работать. Если launcher (или нативный jar) не найден или сервис
не поднялся за 60 с, инструмент вернёт JSON-RPC error `-32001` с путём к логу.

## Нативный движок (dap-native)

Фаза 3: адаптер умеет поднимать нативный движок вместо legacy KNIME. Протокол,
имена и схемы инструментов не меняются, legacy остаётся режимом по умолчанию.

```bash
# один раз: собрать jar (нужен JDK 21)
cd engine-native && ./mvnw -o -DskipTests package && cd ..

# адаптер сам запустит jar при первом tools/call
DAP_NATIVE=1 DAP_PORT=7923 DAP_SESSION=claude python3 adapters/mcp/dap_mcp.py
```

`DAP_ENGINE=native` — эквивалент. Java ищется в `$JAVA_HOME/bin/java`, иначе в `PATH`;
jar — всегда `$DAP_HOME/engine-native/target/dap-native.jar`. Если jar нет, первый
вызов вернёт `-32001` с командой сборки.

Чем native отличается для агента:

- каталог — 11 узлов с id `dap.*` (10 пилотных + `dap.column_renamer`), а не 832 KNIME;
- настройки — плоский JSON по `configKey` из `dap_node_dialog_schema`, без XML;
- нет `runtime/knime`, `workspace/` и KNIME-рантайма; jar самодостаточен;
- `DAP_BIND` для native — `127.0.0.1`, `localhost` или `0.0.0.0` (`::` движок не принимает);
- `/v1` тот же (замороженный контракт), поэтому остальные инструменты и сессии
  работают как раньше; legacy-алиасы без `/v1` (`GET /health`) в native не зарегистрированы;
- sync `dap_run` в native не блокирует HTTP-диспетчер (виртуальный поток), но
  `dap_run_async` + `dap_job_status` по-прежнему предпочтительны для долгих workflow;
- файловые ридеры читают путь, доступный процессу jar (loopback, без auth).

### Сквозная проверка (e2e)

`tests/mcp_e2e.py` — stdlib-клиент MCP: поднимает адаптер с `DAP_NATIVE=1` на свободном
порту, ждёт health, проходит `dap_workflow_create` → `dap_add_node` (csv_reader, sorter)
→ `dap_connect` → `dap_run` → `dap_get_table` → `dap_delete_workflow`, проверяет
отсортированные значения и гасит сервис через `POST /v1/shutdown`.

```bash
cd engine-native && ./mvnw -o -DskipTests package && cd ..
python3 tests/mcp_e2e.py                  # порт 7923, общий лимит 120 с
python3 tests/mcp_e2e.py --port 7924      # другой порт
```

Legacy KNIME не запускается, Docker не нужен. Код возврата 0 — успех, 1 — провал с
сообщением; stderr адаптера печатается при ошибке.

## Подключение к агенту

Любой MCP-клиент со stdio-транспортом. `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "dap": {
      "command": "python3",
      "args": ["/Users/vitalypesnev/Projects/dap/adapters/mcp/dap_mcp.py"],
      "env": {
        "DAP_HOME": "/Users/vitalypesnev/Projects/dap",
        "DAP_PORT": "7800",
        "DAP_SESSION": "claude"
      }
    }
  }
}
```

Тот же формат (`command` / `args` / `env`) используют Cline, Cursor, Continue и
другие клиенты. Если `python3` в PATH старше 3.10 — укажите абсолютный путь к
интерпретатору в `command`.

Проверка вручную (одна строка — один запрос):

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | python3 adapters/mcp/dap_mcp.py
```

## Инструменты

| Инструмент | Что делает | Аргументы |
|---|---|---|
| `dap_search_nodes` | поиск узлов по имени/категории | `query`, `category`, `limit` (20) |
| `dap_node_details` | полное описание узла: порты, tier, XML-схема настроек | `id` |
| `dap_node_dialog_schema` | поля настроек: подписи, типы, умолчания, configKey, допустимые значения; с `workflow`+`node` — варианты по живым входам | `id`, `workflow`?, `node`? |
| `dap_workflow_create` | создать пустой workflow | `name`? |
| `dap_list_workflows` | workflow текущей сессии с числом узлов | — |
| `dap_delete_workflow` | удалить workflow | `workflow` |
| `dap_add_node` | добавить узел из каталога, вернуть id (`0:1`) | `workflow`, `factory`, `settings`? |
| `dap_configure_node` | изменить настройки узла поверх текущих | `workflow`, `node`, `settings` |
| `dap_connect` | соединить выход с входом (порты данных, с 0) | `workflow`, `from`, `fromPort` (0), `to`, `toPort` (0) |
| `dap_disconnect` | разорвать соединение | те же |
| `dap_remove_node` | удалить узел | `workflow`, `node` |
| `dap_run` | выполнить workflow синхронно, дождаться конца | `workflow`, `timeoutSec`? |
| `dap_run_async` | запустить асинхронно (HTTP 202), вернуть id задания | `workflow`, `timeoutSec`? |
| `dap_job_status` | состояние задания, для завершённого — `result` | `job` |
| `dap_job_cancel` | отменить активное задание | `job` |
| `dap_list_jobs` | задания сессии, новые сверху | — |
| `dap_get_table` | колонки, число строк, первые N строк | `workflow`, `node`, `port` (0), `limit` (20) |
| `dap_profile_table` | профиль: пропуски, уникальные, min/max/mean | `workflow`, `node` |
| `dap_save_workflow` | сохранить workflow на диск | `workflow`, `dir` |

`settings` — плоский объект по `configKey` из `dap_node_dialog_schema`, например
`{"dataset": "IRIS"}`.

## Сессии

Сессия задаётся один раз на процесс переменной `DAP_SESSION` и уходит заголовком
`X-DAP-Session` во всех запросах. Движок изолирует по сессиям workflow и задания:
чужой `wf-*` или `job-*` не виден и не удаляется. Имя — `[A-Za-z0-9._-]{1,64}`,
по умолчанию `default`.

Два агента на одном движке — просто два процесса адаптера с разными
`DAP_SESSION`; общий каталог узлов у них один.

## Задания (jobs)

- `dap_run` — синхронный: движок держит соединение до конца исполнения,
  ответ содержит `ok`, `durationMs` и состояние каждого узла.
- `dap_run_async` — возвращает `202`, `id` (`job-N`), начальное состояние и
  `statusUrl`; исполнение продолжается в движке.
- Состояния: `queued`, `running`, `succeeded`, `failed`, `cancelled`, `timeout`.
  Для `succeeded` `dap_job_status` возвращает `result` с узлами, для `failed` — `error`.
- `timeoutSec` ограничивает исполнение (по умолчанию настройка движка, 600 с);
  по истечении задание становится `timeout`, исполнение отменяется.
- `dap_job_cancel` отменяет только активное задание; для уже завершённого
  вернётся JSON-RPC error (движок отвечает `409`).
- В движке одновременно исполняется одно задание (worker-поток), остальные ждут в
  `queued`; активных заданий не больше 8.

## Ошибки

Ошибка инструмента — это JSON-RPC `error`, а не `content` с `isError`:

```json
{"jsonrpc":"2.0","id":77,
 "error":{"code":-32000,"message":"unknown node no.such.NodeFactory",
          "data":{"status":404,"body":{"error":"unknown node no.such.NodeFactory"}}}}
```

| Код | Когда |
|---|---|
| `-32700` | невалидный JSON в строке |
| `-32600` | строка не является JSON-RPC объектом |
| `-32601` | неизвестный метод |
| `-32602` | неизвестный инструмент или нет обязательного аргумента |
| `-32603` | внутренняя ошибка адаптера |
| `-32000` | движок вернул ошибку (`data.status`, `data.body`) |
| `-32001` | движок недоступен или не поднялся за 60 с (`data.log`) |

## Пример: IRIS от узла до таблицы

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"dap_workflow_create","arguments":{"name":"iris"}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"dap_add_node","arguments":{
  "workflow":"wf-1",
  "factory":"org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory",
  "settings":{"dataset":"IRIS"}}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"dap_run","arguments":{"workflow":"wf-1"}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"dap_get_table","arguments":{
  "workflow":"wf-1","node":"0:1","limit":5}}}
```

Фактические ответы (сокращены):

```json
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{\"id\": \"wf-1\", \"name\": \"iris\", \"session\": \"claude\"}"}]}}
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"node\": \"0:1\", \"name\": \"Example Data Reader\"}"}]}}
{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"{\"ok\": true, \"durationMs\": 136, \"workflow\": \"wf-1\", \"nodes\": [{\"node\": \"0:1\", \"name\": \"Example Data Reader\", \"state\": \"EXECUTED\"}]}"}]}}
{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"{\"rows\": 150, \"columns\": [{\"index\": 0, \"name\": \"sepal_length\", \"type\": \"Number (double)\"}, ...], \"data\": [[5.1, 3.5, 1.4, 0.2, \"Iris-setosa\"], ...], \"preview\": 5}"}]}}
```

Асинхронный вариант:

```json
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"dap_run_async","arguments":{"workflow":"wf-1"}}}
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"dap_job_status","arguments":{"job":"job-1"}}}
```

```json
{"jsonrpc":"2.0","id":6,"result":{"content":[{"type":"text","text":"{\"id\": \"job-1\", \"workflow\": \"wf-1\", \"state\": \"queued\", \"timeoutSec\": 600, \"statusUrl\": \"/v1/jobs/job-1\"}"}]}}
{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"{\"id\": \"job-1\", \"state\": \"succeeded\", \"result\": {\"ok\": true, \"nodes\": [...]}}"}]}}
```

Типичная последовательность для агента: `dap_search_nodes` → `dap_node_details` /
`dap_node_dialog_schema` → `dap_workflow_create` → `dap_add_node` → `dap_connect` →
`dap_configure_node` → `dap_run` (или `dap_run_async` + `dap_job_status`) →
`dap_get_table` / `dap_profile_table` → при необходимости `dap_save_workflow` /
`dap_delete_workflow`.

## Ограничения

- Один процесс адаптера — один движок (`$DAP_BIND:$DAP_PORT`) и одна сессия
  (`DAP_SESSION`); для второй сессии запускается второй процесс.
- HTTP-таймаут 600 с; долгие workflow надёжнее гонять через `dap_run_async`.
- Только stdlib: адаптер ставится копированием файла, без venv и pip.
- Адаптер не управляет жизненным циклом сервиса: не перезапускает и не
  останавливает уже работающий движок.
