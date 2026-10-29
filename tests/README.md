# DAP golden / contract tests

Самодостаточный набор тестов поверх HTTP API движка DAP (`/v1`). Только Python 3
stdlib, без внешних зависимостей. Тесты не меняют `engine/`, `runtime/`,
`catalog/`, `scripts/`, `adapters/`, `deploy/`, `docs/` и не трогают
резидентный сервис на `127.0.0.1:7800`.

## Быстрый старт

    cd /Users/vitalypesnev/Projects/dap
    python3 tests/run_all.py          # поднять 7810, прогнать, погасить
    python3 tests/run_all.py --keep   # оставить сервис на 7810 для отладки

После `--keep` сервис остаётся на `http://127.0.0.1:7810`, данные —
в `/tmp/dap-golden-tests`, лог — `/tmp/dap-golden-tests/service.log`.
Погасить вручную:

    curl -X POST http://127.0.0.1:7810/v1/shutdown

Контрактный набор отдельно, против уже запущенного экземпляра:

    python3 tests/contract/test_api.py                        # 127.0.0.1:7810
    python3 tests/contract/test_api.py http://127.0.0.1:7800  # любой другой

## Что делает `run_all.py`

1. Проверяет, что порт 7810 свободен. Если там уже что-то отвечает — выходит с
   кодом 2 и не перехватывает чужой сервис.
2. Запускает `scripts/run.sh` с окружением
   `DAP_PORT=7810 DAP_DATA=/tmp/dap-golden-tests DAP_BIND=127.0.0.1`
   (каталог данных пересоздаётся).
3. Ждёт `GET /v1/health` (до 120 с), проверяет `nodes=832`.
4. Прогоняет контрактный набор и golden-сценарии, печатая `PASS/FAIL/SKIP`
   по каждой проверке.
5. В конце (без `--keep`) делает `POST /v1/shutdown` и добивает процесс, если он
   не завершился за 45 с.

Порт 7800 не используется и не опрашивается: runner работает только
с собственным экземпляром на 7810.

Код возврата: 0 — ни одной FAIL-проверки (SKIP допускаются), 1 — есть FAIL,
2 — окружение не позволяет запуск.

## Структура

    tests/
      run_all.py           # раннер: сервис + прогон + отчёт
      dap_client.py        # HTTP-клиент /v1 + каркас проверок (stdlib)
      golden/
        iris.py            # IRIS: 150 строк, mean sepal_length 5.8433
        groupby.py         # GroupBy: группировка + эквивалентная агрегация (средние)
        empty_array.py     # пустой list (array-size=0) на Column Filter
        join.py            # Concatenate 300 строк + Joiner3 7500 строк
        loop.py            # Chunk Loop Start + Loop End
        missing.py         # AIR_QUALITY + Missing Value (+ WORLDBANK)
        settings_error.py  # 400/404 и текст ошибок настроек
        jobs.py            # async job -> succeeded, timeout, cancel
      contract/
        test_api.py        # контракт /v1: health, nodes, dialog, jobs, ошибки
      README.md

Новый сценарий — файл `tests/golden/<имя>.py` с функцией `run(api, sc)`;
раннер подхватывает его автоматически.

## Что реально проверяется

| сценарий | что выполняется | проверки |
|----------|-----------------|----------|
| `iris` | `ReadDatasetNodeFactory` `dataset=IRIS` | 150 строк; колонки `sepal_length…species`; первая строка `5.1,3.5,1.4,0.2,setosa`; `missing=0`, `distinct=35`, `min=4.3`, `max=7.9`; `mean(sepal_length)=5.843333` |
| `groupby` | `GroupBy` с `grouByColumns.InclList=["species"]`; отдельно `Row Aggregator` для средних | GroupBy даёт 3 группы setosa/versicolor/virginica; Row Aggregator даёт 3 строки: 5.006 / 5.936 / 6.588 |
| `empty_array` | `Column Filter` с `exclude=["sepal_length"]` (в XML `array-size=0`) | 150 строк, 4 колонки без `sepal_length`; проверяется создание элемента пустого массива |
| `join` | два IRIS reader + `Concatenate`; `Joiner3` inner join по `species` | Concatenate: 300 строк, стабильный digest; Joiner3: 7500 строк, 10 колонок, стабильный digest |
| `loop` | `Example Data Reader → Chunk Loop Start (50) → Loop End` | loop-соединение принято; Loop End отдаёт 150 строк с `Iteration` 0,1,2 по 50 строк; mean 5.8433 |
| `missing` | AIR_QUALITY + `Missing Value` | 9357×15, 0 пропусков; после узла `missing(RH)=0`; WORLDBANK: 2993 пропуска до и после (pass-through) |
| `settings_error` | `PATCH`/`POST` с некорректными настройками | 400 `unknown setting … available: [dataset]`; 400 «is a value, not a structure»; валидный PATCH; 400 без `factory`; 400 неизвестный узел; 404 node/workflow/job; 400 невалидная сессия |
| `jobs` | async run, `timeoutSec=1`, `DELETE /v1/jobs/{id}` | 202 + job-id; `succeeded` + `result.ok=true`; timeout → `timeout` за ~1 с; cancel работающего → `cancelled`; DELETE завершённого → 409 |
| `contract` | все ключевые маршруты `/v1` | health (832), `X-DAP-Api-Version: 1`, каталог и tier, `?q=`/`?category=`/`limit`, node/dialog, workflow lifecycle, изоляция сессий, jobs, 400/404 |

## SKIP: что осознанно не проверяется

Один SKIP — `missing.configure_column_settings`. У `Missing Value` настройка
`columnSettings` — вложенный `config`, а не плоский массив `entry`; движок
пока не синтезирует вложенные config-шаблоны. Без `columnSettings` узел
работает как pass-through, это и зафиксировано на WORLDBANK_COUNTRIES
(2993 пропуска до и после).

Пустые плоские массивы (`array-size=0`) уже поддерживаются — это проверяет
`empty_array` на Column Filter. GroupBy и Joiner3 настраиваются и исполняются.

## Задокументированные особенности API

* Каталог: **832 записи, но 827 уникальных factory id** — пять фабрик
  зарегистрированы дважды под разными category-path:
  `FileMetaInfoNodeFactory`, `FilterColumnNodeFactory`, `NewJoinerNodeFactory`,
  `SplitNodeFactory`, `KnnDistNodeFactory`. `/v1/health.nodes` = 832.
* `/v1/health.coreNodes` берётся из `catalog/curated.json` на старте сервиса
  (сейчас **141**); контракт проверяет согласованность с tier в каталоге.
* `X-DAP-Api-Version: 1` ставится на `/v1/*`, но не на корневой `/v1`.
* Неизвестный узел: `GET /v1/nodes/{id}` → 404, `GET /v1/nodes/{id}/dialog` → 400.
* `POST /v1/health` → 404 (маршрут только GET).
* Timeout/cancel: watchdog `timeoutSec` даёт терминальное состояние `timeout`,
  `DELETE /v1/jobs/{id}` для работающего job — `cancelled`; для завершённого — 409.
* `Joiner2` (legacy) требует `leftTableJoinPredicate/rightTableJoinPredicate`
  и падает с `Please define at least one joining column pair.`; рабочий join —
  `Joiner3` (с `mode=WILDCARD`).

## Parity harness (Phase 0)

Корпус tests/corpus/ и tests/parity.py фиксируют semantic parity с KNIME:

    python3 tests/parity.py --validate
    python3 tests/parity.py --engine oracle --write-baseline
    python3 tests/parity.py --engine oracle --check-baseline

tests/baseline.json хранит наблюдения oracle в native-словаре (имена и типы
приведены к контракту native), правила сравнения — docs/PARITY.md.
--engine native появится вместе с engine-native/.

## Фактический прогон

`python3 tests/run_all.py`, exit code 0 (9 сценариев, 41 PASS, 0 FAIL, 1 SKIP):

    service: pid=62259 version=1.0.0 nodes=832 core=141

    [PASS] contract  (13 pass, 0 fail, 0 skip)
    [PASS] iris      (3 pass, 0 fail, 0 skip)
    [PASS] groupby   (3 pass, 0 fail, 0 skip)
    [PASS] join      (3 pass, 0 fail, 0 skip)
    [PASS] loop      (3 pass, 0 fail, 0 skip)
    [PASS] missing   (3 pass, 0 fail, 1 skip)
    [PASS] settings_error (7 pass, 0 fail, 0 skip)
    [PASS] jobs      (4 pass, 0 fail, 0 skip)
    [PASS] empty_array (2 pass, 0 fail, 0 skip)

    SUMMARY  PASS 41  FAIL 0  SKIP 1  scenarios 9

## Замечания по окружению

* Нужен JDK и собранный runtime (`DAP_JDK`, `runtime/knime`, `catalog/`).
* На 7810 не должно быть другого экземпляра; runner не трогает чужой процесс.
* Одновременно запускать два `run_all.py` нельзя (порт и data-dir общие).
* При пересборке каталога меняются `nodes`/`coreNodes`; обновите ожидания
  в `run_all.py` и `contract/test_api.py`.
