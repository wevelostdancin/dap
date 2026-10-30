# HTTP API DAP v1

Человекочитаемый контракт. Машинное описание — [../api/openapi.yaml](../api/openapi.yaml).
Архитектура и модель job — [../ARCHITECTURE.md](../ARCHITECTURE.md).

- База: `http://127.0.0.1:7800/v1`
- Формат: JSON UTF-8, `Content-Type: application/json; charset=utf-8`
- Версия API: заголовок ответа `X-DAP-Api-Version: 1`
- Сессия: заголовок `X-DAP-Session` или `?session=`, по умолчанию `default`
- Аутентификации нет; сервис слушает loopback
- Пути без префикса `/v1` (включая `/health`) — legacy-алиасы; канонический контракт — `/v1/*`

## 1. Соглашения

### Сессии

Сессия изолирует workflow и jobs. Допустимый id: `[A-Za-z0-9._-]{1,64}`. Невалидный id —
400 `{"error":"invalid session id"}`. Валидная сессия возвращается в `X-DAP-Session`.
Чужие объекты неотличимы от несуществующих: 404.

### Идентификаторы

| Объект | Формат | Область |
|---|---|---|
| Workflow | `wf-N` | процесс; виден только своей сессии |
| Job | `job-N` | процесс; виден только своей сессии |
| Узел workflow | `<container>:<index>`, например `12:1` | workflow |
| Узел каталога | Java class factory, например `org.knime...CSVTableReaderNodeFactory` | глобально |

### Порты

Порты данных нумеруются с 0. Если у узла есть скрытый порт Flow Variable на индексе 0,
сервис сам сдвигает индекс: агент всегда передаёт индекс **данных**.

### Ошибки

Большинство ошибок:

```json
{"error": "human-readable message"}
```

Исключения: таймаут и падение синхронного `run` возвращают объект Job (см. раздел 8), а отмена
job — объект с `ok` (см. раздел 8). Тело ошибки может содержать диагностические поля
(например, `ports` у table).

| Код | Когда |
|---|---|
| 200 | успех |
| 202 | job принят асинхронно |
| 400 | неверный JSON-контракт, неизвестный узел/настройка, невалидная сессия, узел/соединение нельзя удалить, у узла нет таблицы |
| 404 | неизвестный workflow/job/маршрут/соединение |
| 408 | истёк `timeoutSec` синхронного `run` |
| 409 | отмена уже завершённого job |
| 500 | ошибка исполнения, внутренняя ошибка, лимиты, битый JSON |

Метод, не поддержанный для маршрута, обычно даёт 404 `no route…`, а не 405.

## 2. Мета и здоровье

### `GET /v1`

Имя, версия и разделы. Сессия здесь не валидируется и не возвращается.

```json
{"name":"DAP","version":"1.0.0","api":"v1",
 "endpoints":"/v1/nodes, /v1/workflows, /v1/jobs, /v1/health"}
```

### `GET /v1/health`

```json
{
  "ok": true,
  "name": "DAP",
  "version": "1.0.0",
  "api": "1",
  "nodes": 832,
  "coreNodes": 141,
  "workflows": 5,
  "sessions": 1,
  "jobs": 0,
  "memory": {"usedMb": 102, "maxMb": 3072}
}
```

`workflows`/`sessions` — по всему процессу, `jobs` — по текущей сессии, `nodes`/`coreNodes` —
по каталогу. `nodes: 0` означает, что каталог не загрузился.

## 3. Каталог узлов

### `GET /v1/nodes?q=&category=&limit=`

Поиск по подстроке в `name + id + category` (регистронезависимо, несколько слов — AND).
`category` — префикс категории (например, `/analytics`). `limit` по умолчанию 50.
Порядок — по имени.

```json
{
  "count": 2,
  "nodes": [
    {
      "id": "org.knime.base.node.io.filehandling.csv.reader.CSVTableReaderNodeFactory",
      "name": "CSV Reader",
      "category": "/IO/Read",
      "type": "Source",
      "deprecated": false,
      "tier": "core",
      "inPorts": [],
      "outPorts": [{"index": 0, "name": "Data", "type": "BufferedDataTable"}]
    }
  ]
}
```

200 всегда; неизвестные `q`/`category` дают пустой список. Нецелое `limit` — 500.

### `GET /v1/nodes/{id}`

Полное описание узла. `{id}` — URL-encoded class name.

```json
{
  "id": "org.knime...CSVTableReaderNodeFactory",
  "name": "CSV Reader",
  "category": "/IO/Read",
  "contributor": "org.knime.base",
  "type": "Source",
  "configurable": false,
  "deprecated": false,
  "tier": "core",
  "inPorts": [],
  "outPorts": [{"index": 0, "name": "Data", "type": "BufferedDataTable"}],
  "settings": ["filename", "delimiter", "..."],
  "settingsXml": "<config key=\"settings\">...</config>"
}
```

Если настройки по умолчанию не сериализуются, вместо `settingsXml` будет
`settingsUnavailable` с причиной. 404 — неизвестный id.

`settingsXml` — XML-схема настроек; именно по её ключам принимается JSON в
`POST/PATCH .../nodes`.

### `GET /v1/nodes/{id}/dialog`

Схема настроек без привязки к workflow. Сначала отдаётся предрасчитанный файл
`catalog/schemas/{id}.json` (`precomputed: true`), затем live-построение с кэшем в памяти.

Успех (аннотированная схема):

```json
{
  "id": "org.knime...ARFFReaderNodeFactory",
  "available": true,
  "schemaKind": "annotations",
  "schema": {
    "class": "org.knime...ARFFReaderNodeParameters",
    "fields": [
      {
        "field": "m_fileUrl",
        "configKey": "FileURL",
        "title": "Input file location",
        "description": "The location of the ARFF file to read.",
        "advanced": false,
        "type": "string",
        "default": "",
        "placeholder": "Row",
        "choices": [{"value": "a", "label": "A"}],
        "choicesSource": {"provider": "...", "kind": "columns",
                          "note": "варианты зависят от входной таблицы"}
      }
    ],
    "labelCoverage": "2/2",
    "warnings": []
  },
  "tier": "core",
  "precomputed": true
}
```

Swing-схема: `schemaKind: "swing"`, `schema.kind: "swing"`, поля в `schema.fields`,
а для ручных диалогов — дерево `schema.ui` (без `configKey`).

Схема недоступна:

```json
{
  "id": "org.knime...BoxPlotNodeFactory",
  "available": false,
  "reason": "узел не отдал ни аннотированную, ни Swing-схему; используйте settingsXml из /nodes/{id}",
  "swing": {"kind": "swing", "fields": [], "warnings": ["createNodeDialogPane() вернул null"], "empty": true},
  "tier": "experimental",
  "precomputed": true
}
```

Типы полей: `string`, `integer`, `number`, `boolean`, `enum`, `array`, `object`,
`unknown`. Дополнительно возможны `optional`, `nullable`, `defaultNote`, `items`,
`addButtonText`, `error`, `choicesNote`, `choicesError`, `choicesResolved`.

Неизвестный id — 400, не 404.

## 4. Workflow

### `GET /v1/workflows`

Только workflow текущей сессии, без пагинации.

```json
{"workflows":[{"id":"wf-3","name":"probe","session":"default","nodes":0}]}
```

### `POST /v1/workflows`

Тело необязательно: `{"name":"my-workflow"}`. Без имени — `workflow-N`.

```json
{"id":"wf-5","name":"my-workflow","session":"default"}
```

200. Превышение `DAP_MAX_WORKFLOWS` (100) — 500 `workflow limit for session`.
Пустое тело допустимо. Битый JSON — 500.

### `DELETE /v1/workflows/{id}`

Удаляет workflow и его узлы; активный job по нему завершится ошибкой. 200 `{"ok":true}`.
Чужой/неизвестный — 404.

## 5. Узлы workflow

### `GET /v1/workflows/{id}/nodes`

```json
{"nodes":[{"node":"12:1","name":"Data Generator","state":"EXECUTED","message":"..."}]}
```

`message` присутствует, если у узла есть сообщение. `state` — состояние KNIME
(`IDLE`, `CONFIGURED`, `EXECUTED`, `ERROR` и т. п.).

### `POST /v1/workflows/{id}/nodes`

```json
{"factory":"org.knime.base.node.util.sampledata.SampleDataNodeFactory",
 "settings":{"seed":42}}
```

`factory` обязателен; `settings` необязателен. Ответ:

```json
{"node":"12:1","name":"Data Generator"}
```

200. Ошибки: 400 `'factory' is required`, 400 `unknown node: …`, 400 `settings rejected: …`,
400 `unknown setting 'x' in 'settings'; available: [...]`.

**Важно:** узел добавляется до применения настроек. При 400 из-за настроек узел уже
существует в workflow, хотя id в ответе не пришёл. Агент должен либо откатить граф, либо
считать операцию частичной.

### `PATCH /v1/workflows/{id}/nodes/{node}`

Меняет настройки поверх текущих:

```json
{"settings":{"seed":7}}
```

200 `{"ok":true,"node":"12:1"}`. Работает только для узлов, созданных через этот сервис
(иначе 400 `node was not created through this service`). 400 — настройки отвергнуты.

### `DELETE /v1/workflows/{id}/nodes/{node}`

200 `{"ok":true,"node":"12:1"}`. 400 — узел не найден или его нельзя удалить
(идут соединения или исполнение).

## 6. Соединения

### `POST /v1/workflows/{id}/connect`

```json
{"from":"12:1","fromPort":0,"to":"12:2","toPort":0}
```

Порты — индексы **данных**; скрытый Flow Variable учитывается автоматически.
200 `{"ok":true}`. 400 — неизвестный узел или несовместимые порты.

### `POST /v1/workflows/{id}/disconnect`

То же тело. 200 `{"ok":true}`; 404 `connection not found`; 400 `connection cannot be removed`.

## 7. Запуск

### `POST /v1/workflows/{id}/run`

Синхронный запуск. Тело необязательно:

```json
{"async": false, "timeoutSec": 600}
```

`?async=true` эквивалентно `{"async":true}`. Таймаут по умолчанию — `DAP_JOB_TIMEOUT`
(600 с). Значение `<= 0` заменяется на 600. Таймаут считается **с момента постановки в
очередь**.

Синхронный успех — 200:

```json
{
  "ok": true,
  "durationMs": 19,
  "workflow": "wf-10",
  "nodes": [
    {"node": "12:1", "name": "Data Generator", "state": "EXECUTED"},
    {"node": "12:2", "name": "Column Filter", "state": "EXECUTED", "message": "..."}
  ]
}
```

Синхронный таймаут — 408, тело — Job со `state: "timeout"`.
Синхронное падение — 500, тело — Job со `state: "failed"` (или `cancelled`) и `error`.

Известная неоднозначность: из-за обёртки `InterruptedException` watchdog-таймаут может
завершиться как `failed`, и тогда sync-запуск вернёт 500, а не 408. Тесты допускают оба
исхода.

Асинхронный режим — 202:

```json
{
  "id": "job-5",
  "workflow": "wf-9",
  "state": "queued",
  "createdAt": 1793281772614,
  "timeoutSec": 600,
  "statusUrl": "/v1/jobs/job-5"
}
```

Синхронный `run` занимает HTTP-диспетчер: пока он ждёт, другие HTTP-запросы (включая
health) не обслуживаются. Для параллельной работы используйте раздел 8 и асинхронный запуск.

### `POST /v1/workflows/{id}/jobs`

Всегда асинхронно — 202 и объект Job, как выше. Тело может содержать `timeoutSec`.

## 8. Jobs

### `GET /v1/jobs`

Только jobs текущей сессии, новые первыми; результат не включается.

```json
{
  "count": 1,
  "jobs": [
    {"id":"job-5","workflow":"wf-9","state":"succeeded",
     "createdAt":1793281772614,"startedAt":1793281772614,
     "finishedAt":1793281772665,"durationMs":51,"timeoutSec":600}
  ]
}
```

### `GET /v1/jobs/{id}`

То же плюс `result` (результат исполнения) для завершённого job. 404 — чужой/неизвестный.

```json
{
  "id":"job-5","workflow":"wf-9","state":"succeeded",
  "createdAt":1793281772614,"startedAt":1793281772614,
  "finishedAt":1793281772665,"durationMs":51,"timeoutSec":600,
  "result":{"ok":true,"durationMs":19,"workflow":"wf-9","nodes":[...]}
}
```

Состояния: `queued`, `running`, `succeeded`, `failed`, `cancelled`, `timeout`.
При прерывании терминальное состояние может быть `failed` (с `error: InterruptedException`)
вместо `cancelled`/`timeout` — это известное поведение, зафиксированное в тестах.

### `DELETE /v1/jobs/{id}`

Отмена активного job:

- 200 `{"ok":true,"id":"job-5","job":{...}}` — отмена запрошена;
- 409 `{"ok":false,"id":"job-5","job":{...}}` — job уже завершён;
- 404 — неизвестный/чужой job.

Отмена best effort: узел, игнорирующий прерывание, может доработать. Состояние может
остаться `running` до фактического выхода worker и затем стать `cancelled` или `failed`.

## 9. Данные

### `GET /v1/workflows/{id}/nodes/{node}/table?port=0&limit=50`

`port` — предпочтительный выходной порт; `limit` — максимум строк (по умолчанию 50).
Если на порту не таблица, сервис ищет первый табличный порт (молча; индекс в ответе не
сообщается). `rows` — общее число строк, `data` — превью.

```json
{
  "rows": 5400,
  "columns": [
    {"index": 0, "name": "Universe_0_0", "type": "Number (double)"},
    {"index": 1, "name": "Cluster Membership", "type": "String"}
  ],
  "data": [["0.5", "Cluster_0"], ["0.7", null]],
  "preview": 2
}
```

Пропуски — `null`; значения — строки. 400: `node has no table output` (с массивом
`ports`), `node has N out ports`. 400 — неизвестный узел; 404 — неизвестный workflow.

### `GET /v1/workflows/{id}/nodes/{node}/profile`

Профиль первой таблицы узла (полный проход по таблице):

```json
{
  "rows": 5400,
  "columns": [
    {"name":"Universe_0_0","type":"Number (double)",
     "missing":0,"distinct":5400,"min":0.0,"max":0.99,"mean":0.5},
    {"name":"Cluster Membership","type":"String","missing":0,"distinct":5}
  ]
}
```

`distinct` точен до 100 000 различных значений на колонку; выше порога допускается
приближённое значение (sketch, ошибка до 5 %), причём значения вблизи порога досчитываются
точно. `min/max/mean` — только для числовых колонок KNIME. 400 — у узла нет таблицы или
узел не найден. Долгие `table`/`profile` прерываются отменой работающего job этого
workflow или закрытием сессии; прерванный запрос отвечает 500 (ошибка исполнения).

## 10. Сохранение и диагностика

### `POST /v1/workflows/{id}/save`

```json
{"dir":"/absolute/path/to/workflow"}
```

Сохраняет workflow как каталог KNIME. 200 `{"ok":true,"dir":"..."}`. 400 — нет `dir`.
Путь интерпретируется на стороне сервера; каталог создаётся средствами KNIME (существующий может быть перезаписан).

### `GET /v1/workflows/{id}/debug`

Диагностика (не является стабильным контрактом): `nodes` с числом портов и классами
портов, `connections`. 200.

### `POST /v1/shutdown`

Штатная остановка: 200 `{"ok":true}`, затем процесс завершается. Не имеет аутентификации;
не публикуйте наружу. Jobs отменяются, несохранённые workflow теряются.

## 11. Сквозной пример

```bash
BASE=http://127.0.0.1:7800/v1
S='X-DAP-Session: agent-1'

WF=$(curl -s -X POST -H "$S" -H 'Content-Type: application/json' \
  -d '{"name":"demo"}' "$BASE/workflows" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

NODE=$(curl -s -X POST -H "$S" -H 'Content-Type: application/json' \
  -d '{"factory":"org.knime.base.node.util.sampledata.SampleDataNodeFactory","settings":{"seed":42}}' \
  "$BASE/workflows/$WF/nodes" | python3 -c 'import sys,json;print(json.load(sys.stdin)["node"])')

JOB=$(curl -s -X POST -H "$S" -H 'Content-Type: application/json' -d '{}' \
  "$BASE/workflows/$WF/jobs" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

until curl -s -H "$S" "$BASE/jobs/$JOB" | grep -q '"state":"succeeded"'; do sleep 1; done
curl -s -H "$S" "$BASE/workflows/$WF/nodes/$NODE/table?limit=5"
curl -s -H "$S" "$BASE/workflows/$WF/nodes/$NODE/profile"
curl -s -X POST -H "$S" -H 'Content-Type: application/json' \
  -d '{"dir":"/tmp/demo"}' "$BASE/workflows/$WF/save"
```

## 12. Известные расхождения

- Ошибка разбора JSON → 500, а не 400.
- Превышение `DAP_MAX_WORKFLOWS` и лимита 8 активных jobs → 500, а не 409/429.
- Нет 405: неподдержанный метод маршрута → 404.
- Нет `GET /v1/workflows/{id}`: метаданные workflow отдельным запросом не отдаются.
- `POST .../nodes` с неверными настройками оставляет узел в workflow.
- `table` при fallback на другой порт не сообщает фактический индекс.
- `GET /v1/nodes/{id}/dialog` для неизвестного id → 400, а не 404.
- Пустые list-массивы (`array-size=0`) поддерживаются; отдельные вложенные списки без
  шаблона в XML всё ещё могут дать 400 `has no template to extend`.
- Терминальное состояние прерванного job: `cancelled` (DELETE) или `timeout` (watchdog).
