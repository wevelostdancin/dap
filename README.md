# DAP

**DAP** — автономный аналитический движок для агентов. Один JVM-процесс поднимает HTTP API над
vendored-рантаймом KNIME 5.12: каталог из 832 узлов, 733 доступные схемы настроек,
141 core-узел, сборка и исполнение workflow, чтение таблиц и профилей. Без GUI, без
установленного KNIME и без внешней БД.

Версия: **1.0.0**. API: **/v1**. Лицензия: **GPLv3** (см. раздел «Лицензия»).

## Что такое DAP

DAP — это «движок за API», а не приложение для аналитика. Агент (MCP-клиент, скрипт, сервис)
получает машиночитаемое описание узлов и их настроек, собирает workflow из JSON-вызовов,
запускает его и забирает результат в виде строк таблицы и профиля колонок. Вся аналитика
исполняется локально, в одном процессе, на данных, которые не покидают машину.

Типовой цикл:

1. `GET /v1/nodes?q=csv` — найти узел.
2. `GET /v1/nodes/{id}/dialog` — узнать поля, типы, умолчания и допустимые значения.
3. `POST /v1/workflows` → `POST .../nodes` → `POST .../connect` — собрать граф.
4. `POST .../run` или `POST .../jobs` — выполнить (синхронно или асинхронно).
5. `GET .../nodes/{node}/table` и `.../profile` — забрать данные и статистику.
6. `POST .../save` — сохранить workflow как каталог KNIME.

## Чем отличается

| Свойство | DAP | KNIME Analytics Platform |
|---|---|---|
| Интерфейс | HTTP **/v1** + MCP (stdio) | desktop GUI |
| Профиль запуска | headless, один JVM, без дисплея | интерактивная среда |
| Установка | runtime уже внутри дистрибутива | установщик + workspace |
| Контракт для агента | каталог, схемы, JSON workflow, jobs | действия человека в UI |
| Состояние | workflow и jobs в памяти процесса | проект на диске |
| Лицензия | GPLv3 | GPLv3 + EPL-компоненты |

DAP **не является** независимой реализацией аналитического движка. Это производная KNIME
5.12: движок линкуется с `org.knime.core` и использует `WorkflowManager`, `NodeFactory`,
`NodeModel` и таблицы KNIME. Подробный и честный разбор — в
[docs/INDEPENDENCE.md](docs/INDEPENDENCE.md).

## Быстрый старт

### Требования

- JDK 21 (проверено на Temurin 21.0.13).
- Python 3.10+ — для MCP-адаптера и проверок.
- Linux или macOS, x86_64/aarch64.
- Установленный KNIME **не нужен**. Нужен только каталог `runtime/knime`.

`runtime/` не хранится в git (см. [.gitignore](.gitignore)): в релизном артефакте и OCI-образе
он уже внутри; в исходном дереве его получают из релиза или собирают скриптом
`scripts/vendor-runtime.sh` из установленного KNIME (только для разработки самого runtime).

### Сборка и запуск

```bash
make build-engine      # собрать com.dap.engine_1.0.0.jar против runtime/knime
make build-catalog     # пересобрать catalog/catalog.json и catalog/schemas/*.json
make run               # поднять сервис на 127.0.0.1:7800 (блокирующий процесс)
make test              # golden + contract на отдельном экземпляре :7810
```

`make test` поднимает собственный экземпляр на `127.0.0.1:7810` и не трогает сервис на
7800; подробности — [tests/README.md](tests/README.md).

В другом терминале:

```bash
curl -s http://127.0.0.1:7800/v1/health
curl -s 'http://127.0.0.1:7800/v1/nodes?q=csv&limit=5'
```

Если JDK лежит не в `$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home`, задайте путь:

```bash
make run DAP_JDK=/path/to/jdk-21
# или
DAP_JDK=/path/to/jdk-21 make build-engine
```

Все переменные окружения и сценарии эксплуатации — в [docs/OPERATIONS.md](docs/OPERATIONS.md).

### Нативный движок (engine-native)

В репозитории также живёт независимый движок `engine-native/` (Javalin 6 + DuckDB +
Arrow) с тем же контрактом `/v1`. Он не использует `runtime/knime` и покрывает 11 узлов
(10 пилотных + `column_renamer`); архитектура, parity и замеры — в [docs/NATIVE.md](docs/NATIVE.md),
шаблон нового узла — в [docs/MIGRATION.md](docs/MIGRATION.md).

```bash
make build-native   # engine-native/target/dap-native.jar
make test-native    # JUnit suite (203 теста)
make run-native     # 127.0.0.1:7800, блокирующий процесс
```

Если порт 7800 занят KNIME-сервисом, запустите с другим портом и прогоните чёрный ящик:

```bash
DAP_PORT=7861 make run-native &
python3 tests/native_smoke.py --base http://127.0.0.1:7861
```

### MCP

Адаптер — тонкий stdio-транспорт JSON-RPC 2.0; вычисления остаются в JVM. Запускайте сервис
заранее (адаптер умеет стартовать его сам, но пути автостарта зашиты в адаптере и могут не
совпадать с вашим деревом).

```bash
python3 adapters/mcp/dap_mcp.py
```

Конфигурация MCP-клиента:

```json
{
  "mcpServers": {
    "dap": {
      "command": "python3",
      "args": ["/absolute/path/to/dap/adapters/mcp/dap_mcp.py"],
      "env": { "KNIME_SERVICE_PORT": "7800" }
    }
  }
}
```

Инструменты — 19 штук с префиксом `dap_*` (поиск узлов, схемы настроек, сборка workflow,
запуск, таблицы и профили); полный список с аргументами —
[adapters/mcp/README.md](adapters/mcp/README.md).

## API-обзор

База: `http://127.0.0.1:7800/v1`. Тело и ответы — `application/json; charset=utf-8`.
Сессия — заголовок `X-DAP-Session` или параметр `?session=` (по умолчанию `default`).

| Метод и путь | Назначение |
|---|---|
| `GET /v1` | мета: имя, версия, список разделов |
| `GET /v1/health` | здоровье, счётчики, память |
| `GET /v1/nodes?q=&category=&limit=` | поиск узлов |
| `GET /v1/nodes/{id}` | полное описание узла и `settingsXml` |
| `GET /v1/nodes/{id}/dialog` | схема настроек (precomputed или live) |
| `GET/POST /v1/workflows` | список / создание workflow |
| `DELETE /v1/workflows/{id}` | удалить workflow |
| `GET/POST /v1/workflows/{id}/nodes` | список / добавление узла |
| `PATCH/DELETE /v1/workflows/{id}/nodes/{node}` | настройка / удаление узла |
| `POST /v1/workflows/{id}/connect`, `/disconnect` | соединения |
| `POST /v1/workflows/{id}/run` | запуск (sync; `async=true` → 202) |
| `POST /v1/workflows/{id}/jobs` | запуск (всегда 202) |
| `GET /v1/jobs`, `GET /v1/jobs/{id}`, `DELETE /v1/jobs/{id}` | задания и отмена |
| `GET .../nodes/{node}/table`, `.../profile` | данные и профиль |
| `POST /v1/workflows/{id}/save` | сохранить на диск |

Коды: `200` успех, `202` задание принято, `400` неверный запрос/настройки,
`404` неизвестный объект или маршрут, `408` таймаут sync-запуска, `409` отмена
неактивного job, `500` ошибка исполнения или внутренняя ошибка.

Полный человекочитаемый контракт — [docs/API.md](docs/API.md); машинный —
[api/openapi.yaml](api/openapi.yaml).

## Tier: core и experimental

Tier — это **обещание поддержки**, а не ограничение исполнения. Оба tier доступны через API.

- **core** — 141 узел: схема и поведение зафиксированы, изменения ломают контракт только
  осознанно. Golden-сценарии лежат в `tests/golden` и запускаются `make test`; они
  покрывают ключевые core-сценарии, но не все 141 узла.
- **experimental** — 691 узел: доступны, но схема может быть неполной, а поведение —
  измениться между релизами.

Список core задаётся в `catalog/curated.json` и применяется при старте; это источник
истины. Фактическое число отдаёт `GET /v1/health` → `coreNodes`. Как добавить узел —
[docs/NODES.md](docs/NODES.md).

## Ограничения

- **Один JVM, один worker.** Все workflow исполняются последовательно; одновременно активны
  не более 8 jobs (жёсткий лимит в коде). HTTP-диспетчер по умолчанию тоже обрабатывает
  запросы последовательно: синхронный `run` блокирует другие HTTP-вызовы до завершения.
- **Состояние в памяти.** Workflow, jobs и результаты живут до перезапуска процесса.
  `save` — единственный способ сохранить workflow.
- **Нет аутентификации.** По умолчанию сервис слушает `127.0.0.1`. Не выставляйте его в
  сеть без внешнего прокси/аутентификации.
- **99 узлов из 832 не отдают схему** (нет ни аннотаций, ни Swing-диалога). Для них доступен
  `settingsXml` из `GET /v1/nodes/{id}`, но не готовый список полей.
- **21 узел не сериализует настройки по умолчанию** (`settingsUnavailable`): настройки
  такого узла нельзя применить через API.
- **Зависимые choices неполны.** Статическая схема узла не знает входных таблиц; варианты,
  зависящие от колонок/других полей, появляются только у узла в workflow и могут честно
  возвращать `choicesError`/`choicesNote`.
- **Нет обновления workflow.** Нельзя переименовать workflow или получить его метаданные
  отдельным запросом; есть список, создание, удаление и операции над узлами.
- **Частичный эффект при ошибке.** `POST .../nodes` сначала добавляет узел, затем применяет
  настройки; при 400 узел уже может остаться в workflow.
- **Таймаут считается с момента постановки в очередь**, а не с начала исполнения.
- **Часть list-настроек требует проверки.** Пустые list-массивы (`array-size=0`) теперь
  поддерживаются, но для отдельных вложенных списков без шаблона в XML всё ещё возможен
  400. Актуальный список ограничений — SKIP-проверки в [tests/README.md](tests/README.md).
- **Состояние job при прерывании неоднозначно.** timeout/cancel может завершиться как
  `timeout`/`cancelled`, так и `failed` с `InterruptedException` — зависит от окна
  прерывания.
- **Лимит workflow на сессию** (по умолчанию 100) при превышении возвращает 500, а не 409.
- **Размер runtime** — около 662 МБ, 610 OSGi-бандлов.

## Лицензия

DAP распространяется под **GNU GPL v3**. В дистрибутив входит производный от KNIME 5.12
рантайм (GPLv3, с дополнительными разрешениями KNIME по разделу 7) и EPL-компоненты Eclipse.
Полный текст — [LICENSE](LICENSE), сведения об источниках и изменениях — [NOTICE](NOTICE).
DAP-native (`engine-native/`, артефакт `dap-native.jar`) собирается отдельно и не содержит
кода KNIME; его зависимости — MIT/Apache-2.0/EPL-LGPL, границы и лицензии —
[docs/INDEPENDENCE.md](docs/INDEPENDENCE.md), [docs/NATIVE.md](docs/NATIVE.md),
[docs/ROADMAP-NATIVE.md](docs/ROADMAP-NATIVE.md).
Гарантий нет; см. `LICENSE`.

## Документация

- [ARCHITECTURE.md](ARCHITECTURE.md) — слои, процессы, jobs, сессии, каталог.
- [docs/API.md](docs/API.md) — контракт HTTP API.
- [api/openapi.yaml](api/openapi.yaml) — OpenAPI 3.1.
- [docs/OPERATIONS.md](docs/OPERATIONS.md) — запуск, env, OCI, диагностика.
- [docs/NODES.md](docs/NODES.md) — каталог, tiers, добавление узла.
- [docs/INDEPENDENCE.md](docs/INDEPENDENCE.md) — зависимость от KNIME и план замены.
- [docs/PARITY.md](docs/PARITY.md) — правила parity с KNIME и известные расхождения.
- [docs/ROADMAP-NATIVE.md](docs/ROADMAP-NATIVE.md) — план нативного движка dap-native.
- [docs/CI.md](docs/CI.md) — CI, runtime-ассет и self-hosted runner.