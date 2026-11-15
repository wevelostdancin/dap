# HTTP API DAP v1

Человекочитаемый контракт нативного движка. Машинное описание —
[../api/openapi.yaml](../api/openapi.yaml), устройство движка —
[ARCHITECTURE.md](ARCHITECTURE.md), узлы — [NODES.md](NODES.md).

- База: `http://127.0.0.1:7800/v1`
- Запросы с телом: `Content-Type: application/json`; ответы —
  `application/json; charset=utf-8`, заголовок `X-DAP-Api-Version: 1`
- Аутентификации нет; сервис слушает loopback
- Каталог — 52 узла с id `dap.*`; контракт — 27 операций

## 1. Основы

### Сессия

Сессия изолирует workflow и задания. Берётся из заголовка `X-DAP-Session`, затем из
`?session=`, иначе `default`. Допустимый id — `[A-Za-z0-9._-]{1,64}`; невалидный
даёт 400 `{"error":"invalid session id"}`. Валидная сессия возвращается в ответе
заголовком `X-DAP-Session`. Чужие объекты неотличимы от несуществующих: 404.

### Идентификаторы

| Объект | Формат | Область |
|---|---|---|
| Узел каталога | `dap.<type>`, например `dap.csv_reader` | глобально |
| Workflow | `wf-N` | процесс; виден только своей сессии |
| Задание | `job-N` | процесс; видно только своей сессии |
| Узел workflow | `<номер workflow>:<индекс>`, например `1:2` для `wf-1` | workflow |

Порты данных нумеруются с 0. У всех узлов один табличный выход (порт 0); у
`dap.joiner` два входа (0 — Left, 1 — Right), у `dap.concatenate` — два входа
(0 — Top, 1 — Bottom), у остальных один. Писатели (`dap.csv_writer`,
`dap.parquet_writer`, `dap.json_writer`, `dap.excel_writer`) возвращают входную таблицу
без изменений (pass-through).

### Ошибки

Обычная ошибка — объект с `error` (человекочитаемое сообщение), `code`
(машиночитаемый код) и необязательным `hint` (что сделать, чтобы исправить):

```json
{"error": "unknown setting 'foo' in 'settings'; available: [file, delimiter, header, columns, emptyStringIsNull]",
 "code": "unknown_setting",
 "hint": "remove 'foo' or check GET /v1/nodes/dap.csv_reader/dialog for the available settings"}
```

`code` — аддитивное расширение контракта: старые клиенты читают `error` как
раньше. Если у ошибки нет специфичного кода, он выводится из статуса:
`bad_request` (400), `not_found` (404), `conflict` (409), `server_error` (500).
`hint` и дополнительные поля (например `limit` у `workflow_limit`) опускаются,
когда их нет.

Исключения: таймаут и падение синхронного run возвращают объект job, отмена
завершённого job — `CancelResult` (см. §5); в них тоже есть `code`
(`node_failed` у упавшего run, `not_cancellable` у отмены завершённого job).

| Код | Когда | `code` |
|---|---|---|
| 200 | успех | — |
| 202 | асинхронный запуск принят | — |
| 400 | невалидная сессия, битый контракт запроса, неизвестный `factory`/`settings`-ключ, недопустимая связь, порт вне диапазона, отсутствующий `dir` | `bad_request`, `unknown_setting`, `invalid_query_param` |
| 404 | неизвестный или чужой `wf-*`/`job-*`, неизвестный узел каталога в `GET /v1/nodes/{id}`, неизвестный узел workflow в `/table`/`/profile`/`PATCH`/`DELETE`/`dialog`, неизвестное соединение, неизвестный маршрут | `not_found`, `unknown_node` |
| 408 | истёк `timeoutSec` синхронного run (тело — job со `state: "timeout"`); истёк `DAP_QUERY_TIMEOUT_SEC` у `/table`/`/profile` | `query_timeout` |
| 409 | отмена уже завершённого job; лимит workflow на сессию; у узла ещё нет таблицы (до run); узел упал | `not_cancellable`, `workflow_limit`, `not_materialized`, `node_failed` |
| 500 | ошибка исполнения, невалидное значение config, нечитаемый файл, битый JSON | `server_error`, `node_failed` |

Метод, не поддержанный для маршрута, даёт 404 `no route: <METHOD> <path>`, а не 405.

Коды каталога датасетов: `dataset_not_found` (404); `dataset_exists` (409);
`invalid_dataset_name`, `invalid_format`, `dataset_source_not_found`,
`dataset_source_required`, `invalid_path`, `path_not_allowed` (400);
`datasets_corrupt`, `datasets_write_failed`, `dataset_ingest_failed`,
`dataset_profile_failed` (500).

## 2. Мета и здоровье

### `GET /v1`

```json
{"name":"DAP","version":"1.1.0","api":"v1","endpoints":"/v1/nodes, /v1/workflows, /v1/jobs, /v1/health"}
```

### `GET /v1/health`

```json
{"ok":true,"name":"DAP","version":"1.1.0","api":"1",
 "generation":"0f8c2f7a-…","extensions":{"installed":["sqlite_scanner"]},
 "nodes":44,"coreNodes":44,
 "workflows":0,"sessions":0,"jobs":0,"memory":{"usedMb":…,"maxMb":…}}
```

`generation` — UUID запуска процесса: он меняется при рестарте движка, поэтому
клиент (MCP-адаптер) отличает «движок перезапустился, workflow потеряны» от
обычного 404. `extensions.installed` — имена расширений DuckDB, установленных в
`DAP_DUCKDB_EXTENSION_DIR` (ставятся явно:
`java -jar dap-native.jar --install-extensions sqlite postgres httpfs`).
`nodes` и `coreNodes` — живые счётчики каталога (сейчас 44 и 44); `workflows` —
все workflow процесса; `sessions` — открытые сессионные БД; `jobs` — активные
задания текущей сессии; `memory` — heap JVM.

### Сессии и эвикция

Сессионная БД создаётся при первом запросе данных (run, job, `/table`, `/profile`)
и живёт до эвикции. Эвикция освобождает всё сразу: активные задания сессии
отменяются, её workflow удаляются, DuckDB закрывается. Правила:

- `DAP_SESSION_TTL_SEC` (по умолчанию 1800 с; `0` выключает TTL) — сессия без
  обращений дольше TTL закрывается;
- `DAP_MAX_SESSIONS` (по умолчанию 64) — при превышении закрывается самая давно
  не использованная сессия (LRU); активные не трогаются, но лимит жёсткий;
- проверка идёт в тике watchdog (каждые 1–30 с) и при каждом обращении к сессии.

### `GET /v1/sessions`

Список открытых сессионных БД процесса, давно не использованные сверху:

```json
{"count":2,"sessions":[
  {"id":"default","lastAccess":1763000000000,"workflows":1},
  {"id":"agent-7","lastAccess":1763000042123,"workflows":0}
]}
```

`lastAccess` — epoch-миллисекунды последнего запроса данных этой сессии,
`workflows` — число её workflow.

### `DELETE /v1/sessions/{id}`

`{"ok":true,"id":"agent-7"}` — сессия закрыта (задания отменены, workflow удалены,
БД закрыта). Неизвестная сессия — 404. Повторное удаление — тоже 404.

## 3. Каталог узлов

### `GET /v1/nodes?q=&category=&limit=`

Поиск без учёта регистра по имени, id и категории; токены `q` объединяются по AND,
`category` — префикс; `limit` по умолчанию 50, сортировка по имени.

```json
{"count":44,"nodes":[
  {"id":"dap.csv_reader","name":"CSV Reader","category":"/IO/Read","type":"Source",
   "deprecated":false,"tier":"core",
   "inPorts":[],"outPorts":[{"index":0,"name":"Data","type":"BufferedDataTable"}]}
]}
```

### `GET /v1/nodes/{id}`

Полное описание: поля списка плюс `contributor`, `configurable: true`,
`settings` (список ключей `configSchema`), `settingsXml: null`,
`settingsUnavailable` (подсказка про `/dialog`), `description`, `available`.
Неизвестный id — 404.

### `GET /v1/nodes/{id}/dialog`

Поля настроек из `configSchema`: `field`, `configKey`, `title`, `description`,
`type`, `optional`, `default`, `placeholder`, `choices`, `items`.
`type` — `string`, `integer`, `number`, `boolean`, `enum`, `array`, `object`,
`unknown`.

```json
{"id":"dap.csv_reader","tier":"core","precomputed":true,"available":true,
 "schemaKind":"annotations",
 "schema":{"class":"dap.csv_reader",
   "fields":[{"field":"file","configKey":"file","title":"Input file","type":"string",
              "optional":false,"placeholder":"/data/input.csv"}],
   "labelCoverage":"5/5","warnings":[]}}
```

Неизвестный id — 400. Все 52 узла отдают `available: true`.

### Диалог узла внутри workflow

`GET /v1/workflows/{workflowId}/nodes/{node}/dialog` возвращает ту же схему плюс
`node`; неизвестный узел — 404. Для нативных узлов варианты не зависят от входных
таблиц, поэтому ответ совпадает с каталожным.

## 4. Workflow

### `GET /v1/workflows`

```json
{"workflows":[{"id":"wf-1","name":"demo","session":"default","nodes":2}]}
```

### `POST /v1/workflows`

Тело `{"name": "demo"}` необязательно. Ответ — объект workflow. Лимит на сессию
(`DAP_MAX_WORKFLOWS`, по умолчанию 100) при превышении даёт 409
`workflow_limit`: `error` — `workflow limit for session`, в `limit` — сам
лимит, `hint` предлагает удалить workflow или взять другую сессию.

### `POST /v1/workflows/load`

Создаёт workflow из `workflow.json`, записанного `POST .../save`:

```json
{"dir": "/tmp/dap-demo", "name": "demo-copy"}
```

Читается `<dir>/workflow.json`; требуется `"format": "dap-native-workflow/1"`.
Имя берётся из запроса, иначе из файла. Узлы создаются заново (id вида `<номер>:<индекс>`), ключи настроек проверяются
по `configSchema`, связи восстанавливаются.
Неизвестный или недоступный `factory`, битый файл, отсутствующий `dir` или
неподдерживаемый формат — 400; созданный при ошибке workflow удаляется.

### `DELETE /v1/workflows/{workflowId}`

Удаляет workflow: отменяет его активные задания, прерывает выполняющиеся чтения
`/table`/`/profile` этого workflow, удаляет таблицы его reader-узлов и освобождает
lock запуска. Ответ `{"ok": true}`; неизвестный или чужой id — 404.

### Узлы workflow

`GET /v1/workflows/{workflowId}/nodes` — состояния узлов:

```json
{"nodes":[{"node":"1:1","name":"CSV Reader","state":"EXECUTED"}]}
```

Состояния: `IDLE`, `RUNNING`, `EXECUTED`, `ERROR` (у `ERROR` есть `message`).

`POST /v1/workflows/{workflowId}/nodes` — добавить узел:

```json
{"factory": "dap.csv_reader", "settings": {"file": "/data/iris.csv"}}
```

Ответ `{"node":"1:1","name":"CSV Reader"}`. Неизвестный `factory` или неизвестный
ключ `settings` — 400. Узел добавляется до применения настроек, поэтому при 400
из-за настроек он может остаться в workflow.

`PATCH /v1/workflows/{workflowId}/nodes/{node}` — дельта настроек:

```json
{"settings": {"delimiter": ";"}}
```

Верхнеуровневые ключи сливаются с текущими (вложенные объекты заменяются целиком);
неизвестный ключ — 400, отсутствующий `settings` — 400, неизвестный узел — 404.

`DELETE /v1/workflows/{workflowId}/nodes/{node}` — удалить узел; связанный узел
удалить нельзя (400), неизвестный — 404.

### Связи

`POST /v1/workflows/{workflowId}/connect`:

```json
{"from": "1:1", "to": "1:2", "fromPort": 0, "toPort": 0}
```

Порты необязательны (0). 400 — неизвестный узел workflow, самосвязь, уже занятый
входной порт, дубль связи, цикл, порт вне диапазона, отрицательный порт.
`POST .../disconnect` с тем же телом разрывает связь; неизвестная связь — 404.

## 5. Исполнение

### `POST /v1/workflows/{workflowId}/run`

Тело `{"timeoutSec": 30}` необязательно; `async` можно передать в теле или
запросом `?async=true`.

- Синхронно: ждёт завершения, `200` с `RunResult`; `408` при таймауте;
  `500` при ошибке (тело — job со `state: "failed"` или `"cancelled"` и
  `code: "node_failed"`).
- `async=true`: `202` с объектом job.
- Неизвестный workflow — 404. Таймаут считается от постановки в очередь.

```json
{"ok":true,"durationMs":…,"workflow":"wf-1",
 "nodes":[{"node":"1:1","name":"CSV Reader","state":"EXECUTED"},
          {"node":"1:2","name":"Sorter","state":"EXECUTED"}]}
```

Запуски одного workflow сериализованы, разные workflow идут параллельно. Первый
упавший узел отменяет соседей по уровню.

### Задания

`POST /v1/workflows/{workflowId}/jobs` — всегда `202` с job.

`GET /v1/jobs` — задания сессии, новые сверху, без `result`:
`{"count":1,"jobs":[...]}`. История ограничена: хранятся последние
`DAP_MAX_RETAINED_JOBS` заданий сессии (по умолчанию 200), более старые
терминальные удаляются и дают 404; активные не удаляются никогда.

`GET /v1/jobs/{jobId}` — job с `result` для `succeeded`; неизвестный или чужой —
404.

`DELETE /v1/jobs/{jobId}` — отмена активного job:

- `200` `{"ok":true,"id":"job-1","job":{...}}`;
- `409` `{"ok":false,...,"error":"...","code":"not_cancellable"}`, если job уже завершён;
- `404` для неизвестного или чужого.

Поля job: `id`, `workflow`, `state`, `createdAt`, `timeoutSec`,
`startedAt`/`finishedAt`/`durationMs` (когда известны), `error`, `code`
(у упавшего run), `result`, `statusUrl`. Состояния: `queued`, `running`,
`succeeded`, `failed`, `cancelled`, `timeout`.

## 6. Данные

### `GET /v1/workflows/{workflowId}/nodes/{node}/table?port=0&limit=1`

Материализует сохранённый план узла: `COUNT(*)` и первые `limit` строк (по
умолчанию 50). Материализация ограничена `DAP_QUERY_TIMEOUT_SEC` (по умолчанию
60 с; `0` выключает): по истечении соединение закрывается, ответ — 408
`query_timeout` с `hint`, следующий запрос работает как обычно. Пример ниже
сокращён до одной строки.

```json
{"rows":150,
 "columns":[{"index":0,"name":"sepal_length","type":"Number (Float)"}],
 "data":[["5.1"]],
 "preview":1}
```

`rows` — всего строк, `data` — строки значениями-строками (`null` для NULL),
`preview` — сколько строк вернулось. `limit < 0` даёт пустой `data`; `port`
вне диапазона или нецелый query-параметр — 400; неизвестный узел workflow —
404 `unknown_node`; узел ещё не исполнялся — 409 `not_materialized` (в теле
есть `ports` и `hint` с командой run); узел упал — 409 `node_failed` с
сообщением узла; неизвестный workflow — 404.

### `GET /v1/workflows/{workflowId}/nodes/{node}/profile`

Полный проход по таблице:

```json
{"rows":150,
 "columns":[{"name":"sepal_length","type":"Number (Float)",
             "missing":0,"distinct":35,"min":4.3,"max":7.9,"mean":5.843333333333334}]}
```

`missing` и `distinct` точные; `distinct` считается точно до 100 000 строк,
выше — приблизительно с досчётом у границы. `min`/`max`/`mean` — только для
числовых колонок; у колонки из одних NULL они опускаются. Статусы те же, что у
`/table`: 404 `unknown_node`, 409 `not_materialized`/`node_failed`.

### `POST /v1/datasets`

Один раз конвертирует CSV/Excel/JSON/Parquet в Parquet-кэш, чтобы повторный анализ
не парсил исходник.

```json
{"file": "/data/iris.csv", "format": "csv"}
```

`format` необязателен: выводится из расширения (`.csv`, `.xlsx`, `.json`,
`.parquet`). Явно допустимы `csv`, `excel` (или `xlsx`), `json`, `parquet`.
Ответ:

```json
{"path": "<cache>/<sha256>.parquet", "rows": 150,
 "columns": [{"name": "sepal_length", "type": "Float64"}], "cached": false}
```

Типы — Arrow-имена (`Int64`, `Float64`, `Utf8`, `Boolean`, `Date32`, `Time64`,
`Timestamp`, `Decimal128`); тип, которого нет в контракте, отдаётся как его
называет DuckDB. Кэш: `DAP_CACHE_DIR` (используется как есть); без него —
`$DAP_HOME/build/cache` (когда `DAP_HOME` задан, например адаптером), иначе
`~/.dap/cache`, а если этот каталог нельзя создать (sandbox) — `$TMPDIR/dap-cache`.
Ключ — SHA-256 от абсолютного пути, размера, mtime и формата, поэтому изменённый
файл инвалидирует запись, а неизменённый возвращается с `cached: true`. 400 — нет
`file`, пустой или нестроковый `format`, неизвестное расширение/формат, файл не
найден; 500 — ошибка записи.
Полученный `path` можно передать в `dap.parquet_reader` как `file`.

### Каталог датасетов

Постоянный каталог (JSON) поверх Parquet-кэша: имя, описание, владелец, теги,
источник, версия, content hash и кэш профиля. Файл каталога — `DAP_DATASETS_FILE`,
иначе `$DAP_HOME/build/datasets.json`, иначе `~/.dap/build/datasets.json`. Запись
атомарная (tmp + rename), чтение и запись под read/write-lock; битый файл — 500
`datasets_corrupt` с путём и подсказкой, а не молчаливая перезапись.

Имя датасета — `[A-Za-z0-9._-]{1,64}`; оно же path-параметр маршрутов.

#### `GET /v1/catalog/datasets`

Список без `history` и `profile`, отсортирован по имени:

```json
{"count": 1, "datasets": [{"name": "iris", "description": "flowers", "owner": "alice",
  "tags": ["demo"], "source": "/data/iris.csv", "format": "csv",
  "path": "<cache>/<sha256>.parquet", "rows": 150, "columns": 5, "version": 1,
  "contentHash": "<sha256>", "createdAt": "...", "updatedAt": "..."}]}
```

#### `POST /v1/catalog/datasets`

```json
{"name": "iris", "file": "/data/iris.csv", "format": "csv",
 "description": "flowers", "tags": ["demo"], "owner": "alice"}
```

`file` — CSV/XLSX/JSON/Parquet; `dataset` — уже ингестированный Parquet-путь
(формат тогда всегда `parquet`; передавать `format` с другим значением — 400).
Нужно ровно одно из `file`/`dataset`. `format` необязателен: выводится из
расширения. Ответ — запись целиком (как `GET .../{name}`). 400
`invalid_dataset_name`, `invalid_format`, `dataset_source_not_found`,
`path_not_allowed`, `dataset_source_required`, `invalid_path`; 409
`dataset_exists`.

#### `GET /v1/catalog/datasets/{name}`

Запись + `history` (последние 10 вытесненных версий) + `profile`, если он уже
закэширован. 404 `dataset_not_found`.

#### `PATCH /v1/catalog/datasets/{name}`

`{"description": "...", "tags": ["..."], "owner": "..."}` — только метаданные,
опущенные поля сохраняют значение; данные, версия и кэш профиля не меняются.

#### `DELETE /v1/catalog/datasets/{name}`

`{"ok": true, "name": "iris"}`. Удаляется только запись каталога; Parquet-файл в
кэше остаётся.

#### `POST /v1/catalog/datasets/{name}/refresh`

Переингестит записанный источник, `version` += 1, предыдущий снимок уходит в
`history` (не более 10). Кэш профиля переиспользуется, только если `contentHash`
не изменился.

#### `GET /v1/catalog/datasets/{name}/profile?refresh=false`

Профиль считается один раз и кэшируется по `contentHash`; `cached` показывает,
взят ли он из кэша, `refresh=true` форсирует пересчёт:

```json
{"name": "iris", "version": 1, "contentHash": "<sha256>", "rows": 150, "cached": false,
 "computedAt": "...", "columns": [{"name": "sepal_length", "type": "Float64",
 "missing": 0, "distinct": 35, "min": 4.3, "max": 7.9, "mean": 5.843}]}
```

`min`/`max`/`mean` — только для числовых колонок, у колонки из одних NULL
опускаются. `columns` в записи каталога — число колонок, схема — в профиле.
### `POST /v1/explore`

Один вызов на первичный анализ датасета: профиль колонок, качество, связи,
различия групп, временной диапазон, гипотезы и рекомендации.

```json
{"file": "/data/iris.csv", "format": "csv", "maxColumns": 50, "sampleRows": 1000}
```

Ровно один источник: `file` (конвертируется в Parquet-кэш по правилам
`POST /v1/datasets`) или `dataset` (путь уже ингестированного Parquet из ответа
`POST /v1/datasets`). `format` — только для `file`, по умолчанию из расширения;
вместе с `dataset` даёт 400. `sql` — один read-only `SELECT`/`WITH`,
уточняющий источник; ингестированный файл доступен как вью `src`. Оба источника
или ни одного, нестроковые поля, неизвестный формат, не найденный `file`,
нечисловой или выходящий за границы `maxColumns`/`sampleRows` — 400;
несуществующий `dataset` — 404 `dataset_not_found`; SQL не является одним
read-only SELECT — 400 `invalid_sql`.

```json
{"source": "/data/iris.csv", "rows": 150,
 "columns": [{"name": "petal_length", "type": "Float64", "missing": 0, "distinct": 43,
              "min": 1.0, "max": 6.9, "mean": 3.7587, "stddev": 1.7644,
              "zeros": 0, "negatives": 0, "outliers": 0}],
 "quality": {"score": 97, "issues": [{"severity": "low", "kind": "duplicate_rows",
              "count": 3, "detail": "3 of 150 rows are exact duplicates"}]},
 "relationships": [{"left": "petal_length", "right": "petal_width",
                    "method": "pearson", "value": 0.9629, "p_value": 0.0,
                    "n": 150, "strength": "very strong"}],
 "group_differences": [{"value": "petal_length", "group": "species", "test": "anova",
                        "statistic": 1180.16, "p_value": 0.0, "effect_size": 0.941}],
 "hypotheses": [{"id": "h1", "question": "Do petal_length and petal_width move together?",
                 "test": "pearson", "columns": ["petal_length", "petal_width"],
                 "rationale": "Very strong correlation (pearson=0.963, p<0.001, n=150).",
                 "priority": "high"}],
 "recommendations": ["Model petal_width with petal_length (pearson=0.963, p<0.001)."]}
```

`columns` — до `maxColumns` первых колонок (по умолчанию 50); `missing` и
`distinct` точные; `min`/`max`/`mean`/`stddev` (выборочное СКО), `zeros`,
`negatives` и `outliers` (вне границ 1.5×IQR) — только для числовых колонок.
`quality.score` — 100 минус штрафы за найденные проблемы (high 12, medium 5,
low 1), в диапазоне 0..100; `kind` — одно из `missing`, `empty`, `constant`,
`duplicate_rows`, `high_cardinality`, `outliers`, `mixed_type`.

`relationships` — до 20 пар по убыванию |value|, затем по именам: Pearson для
числовых пар (p-value по t с n−2 степенями свободы), Cramér's V и p хи-квадрат
для категориальных (Utf8/Boolean, от 2 до 20 категорий). `group_differences` —
до 10 пар «числовая × категориальная» по возрастанию p-value: ANOVA, если в
каждой группе не меньше 5 значений, иначе Kruskal-Wallis; `effect_size` — η²
или ε². `p_value` может отдаваться как `0.0`, если истинное значение меньше
точности double. `time` — первый столбец даты/времени: диапазон и оценка
гранулярности (`day`/`week`/`month`/`year`); без такого столбца поле отсутствует.
`hypotheses` — от 3 до 5 шаблонных вопросов по самым сильным находкам, порядок:
priority (`high`/`medium`/`low`), затем `id`; на пустом источнике — пустой
список. `recommendations` — до 8 конкретных шагов. Профиль и quality считаются
по всему источнику, связи, различия и гипотезы — по первым `sampleRows` строкам
(по умолчанию 1000, детерминированно, без случайной выборки).

## 7. Сохранение, диагностика, остановка

### `POST /v1/workflows/{workflowId}/save`

Тело `{"dir": "/tmp/dap-demo"}`. Каталог создаётся, в него пишется
`workflow.json`:

```json
{
  "format": "dap-native-workflow/1",
  "id": "wf-1",
  "name": "demo",
  "nodes": [
    {"id": "1:1", "factory": "dap.csv_reader", "name": "CSV Reader",
     "settings": {"file": "/data/iris.csv"}},
    {"id": "1:2", "factory": "dap.sorter", "name": "Sorter",
     "settings": {"keys": [{"column": "sepal_length", "direction": "desc"}]}}
  ],
  "connections": [
    {"from": "1:1", "fromPort": 0, "to": "1:2", "toPort": 0}
  ]
}
```

Ответ `{"ok":true,"dir":"/tmp/dap-demo"}`. Нет `dir` — 400, неизвестный
workflow — 404, ошибка записи — 500.

### `GET /v1/workflows/{workflowId}/debug`

Диагностика (не стабильный контракт): `workflow`, `name`, `nodes` (id, name,
factory, state, config), `connections` строками `"1:1:0 -> 1:2:0"` и `duckdb`:

```json
{"duckdb":{"memoryLimit":"1.8 GiB","threads":3,"tempDirectory":"/var/tmp/dap"}}
```

До создания БД сессии отдаются сконфигурированные значения, после — эффективные
(после нормализации DuckDB). Неизвестный workflow — 404.

### `POST /v1/shutdown`

`{"ok":true}`, затем процесс закрывается примерно через 100 мс. Аутентификации нет;
незавершённые workflow теряются.

Кроме явной остановки, движок завершается сам после простоя дольше
`DAP_IDLE_TIMEOUT_SEC` (по умолчанию 900 с; `0` выключает автозавершение), если
нет активных jobs. Любой запрос, включая `GET /v1/health`, продлевает простой.

## 8. Пример целиком

```bash
B=http://127.0.0.1:7800

curl -s $B/v1/health

curl -s -X POST $B/v1/workflows -H 'Content-Type: application/json' -d '{"name":"demo"}'
# {"id":"wf-1","name":"demo","session":"default","nodes":0}

curl -s -X POST $B/v1/workflows/wf-1/nodes -H 'Content-Type: application/json' -d '{
  "factory":"dap.csv_reader",
  "settings":{"file":"/data/iris.csv","header":true,
    "columns":[{"name":"sepal_length","type":"Float64"},
               {"name":"sepal_width","type":"Float64"},
               {"name":"petal_length","type":"Float64"},
               {"name":"petal_width","type":"Float64"},
               {"name":"species","type":"Utf8"}]}
}'
# {"node":"1:1","name":"CSV Reader"}

curl -s -X POST $B/v1/workflows/wf-1/nodes -H 'Content-Type: application/json' -d '{
  "factory":"dap.sorter",
  "settings":{"keys":[{"column":"sepal_length","direction":"desc"}],"collation":"natural"}
}'
# {"node":"1:2","name":"Sorter"}

curl -s -X POST $B/v1/workflows/wf-1/connect -H 'Content-Type: application/json' \
  -d '{"from":"1:1","to":"1:2"}'
# {"ok":true}

curl -s -X POST $B/v1/workflows/wf-1/run -H 'Content-Type: application/json' -d '{}'
curl -s "$B/v1/workflows/wf-1/nodes/1:2/table?limit=5"
curl -s "$B/v1/workflows/wf-1/nodes/1:2/profile"

curl -s -X POST $B/v1/datasets -H 'Content-Type: application/json' \
  -d '{"file":"/data/iris.csv"}'
# {"path":"<cache>/<sha256>.parquet","rows":150,"columns":[...],"cached":false}

curl -s -X POST $B/v1/workflows/wf-1/save -H 'Content-Type: application/json' \
  -d '{"dir":"/tmp/dap-demo"}'
curl -s -X POST $B/v1/workflows/load -H 'Content-Type: application/json' \
  -d '{"dir":"/tmp/dap-demo","name":"demo-copy"}'

curl -s -X POST $B/v1/shutdown
```

## 9. Ограничения

- Один пользователь, без auth; сервис должен слушать loopback.
- Состояние в памяти: workflow, задания и таблицы живут до остановки процесса;
  на диск сохраняются `workflow.json` через `/save`, Parquet-кэш
  `POST /v1/datasets` и каталог датасетов (`DAP_DATASETS_FILE`).
- Workflow нельзя переименовать или получить отдельным запросом: есть список,
  создание, загрузка, удаление и операции над узлами.
- Файловые источники читают любой путь, доступный процессу.
- Часть ошибок значения config проявляется только при run: sync-run отвечает 500,
  job получает `failed`, узел — `ERROR` с сообщением.