# ROADMAP — DAP

**Статус:** фазы 0–3 пилота (KNIME-oracle) закрыты 30.10.2026. **P0 «Отвязка» закрыт
06.11.2026:** KNIME удалён, goldens-native зелёные (13/13), workflow сохраняется и
загружается. **P1 «Lifecycle + IO» закрыт 06.11.2026:** idle-watchdog, 5 IO-узлов,
Parquet-ingest, 16 узлов, 247 тестов. **P2 «Ядро аналитики» закрыт 06.11.2026:**
17 новых узлов, 33 узла, 332 теста, 14 goldens. **P3 «Статистика и время» закрыт
06.11.2026:** 11 новых узлов, 44 узла, 401 тест, 15 goldens. **P4 «Hardening» закрыт
15.11.2026:** таймауты чтения, эвикция сессий и jobs, контракт ошибок, soak.
**P5 «MCP-сервис» закрыт 15.11.2026:** 52 узла, 30 инструментов, resources/prompts,
рецепты, coverage-сьют 68/69, установка одной командой.
**Цель:** лёгкий, быстрый и точный аналитический бэкенд для агента: локальный, по требованию,
без KNIME.
**Связанные документы:** [ARCHITECTURE.md](ARCHITECTURE.md), [NODES.md](NODES.md),
[API.md](API.md), [QUALITY.md](QUALITY.md), [BENCH.md](BENCH.md).

## 1. Продукт

`dap` — один JVM-процесс плюс MCP-адаптер. Агент вызывает инструменты `dap_*`; адаптер
поднимает движок при первом вызове, движок сам завершается по простою или по `dap_shutdown`.
Внутри — DuckDB in-process, Arrow и пул узлов по JSON-спецификациям; обмен и хранение —
Parquet.

Продукт **не** является: GUI, многопользовательским сервисом, заменой KNIME на 832 узла,
облачным решением. Пользователь — один, данные — локальные, интерфейс — чат агента.

## 2. Решения

| Решение | Обоснование |
|---|---|
| DuckDB, без Polars | SQL-часть 10M-конвейера — 69 мс; узкое место — ingest и память, Polars его не заменяет (BENCH.md §4.3). Пересмотр — только по замеру ≥2× на конкретном узле. |
| Никакого KNIME | KNIME-обёртка, vendored-рантайм и каталог 832 узлов удалены; ожидаемые результаты перенесены в native-goldens. Oracle больше не нужен. |
| Parquet — канонический формат | Колоночный, сжатый, читается DuckDB без копий. CSV/Excel/JSON — только на входе и выходе. |
| On-demand lifecycle | Адаптер стартует движок, idle-timeout гасит его. Никакого демона, launchd или Docker в ежедневном пути. |
| Свой контракт `/v1` | Контракт остаётся, но теперь он наш: изменения — через CHANGELOG, без «frozen sha» KNIME-эпохи. |
| Спецификации узлов — JSON | Агент читает `configSchema` и настраивает узел без `settingsXml`. |
| Один узел = spec + executor + goldens + docs | Без этого узел не считается готовым. |

## 3. Что удаляется (P0)

Выполнено 06.11.2026 в таком порядке:

1. `tests/goldens-native/` — статические фикстуры: вход → ожидаемый выход; oracle при
   прогоне не нужен.
2. Удалены KNIME-обёртка, vendored-рантайм, каталог 832 узлов, legacy-тесты, скрипты и
   образ; из Makefile убраны legacy-цели.
3. Legacy-документы заменены на `ARCHITECTURE.md`, `NODES.md`, `QUALITY.md`.
4. `adapters/mcp` — только нативный движок, без legacy-ветки.

## 4. Целевая архитектура

- `engine-native/` — Javalin + DuckDB + Arrow, один jar; `/v1` 1:1.
- `adapters/mcp/` — stdio MCP, 20+ инструментов `dap_*`, автозапуск и остановка движка.
- `specs/` — JSON-спецификации узлов (id, configSchema, порты, описание).
- `goldens/` — детерминированные фикстуры для каждого узла.
- Никаких зависимостей от KNIME в коде, тестах и сборке — это проверяет `make lint`.

## 5. Пул узлов

Цель — десятки узлов по направлениям. Реализовано 52 (помечены `*`); остальные —
по задачам.

| Направление | Узлы | Волна |
|---|---|---|
| IO | `csv_reader*`, `csv_writer*`, `excel_reader*`, `excel_writer*`, `json_reader*`, `json_writer*`, `parquet_reader*`, `parquet_writer*`, `file_list`, `sql_query` | 1 |
| Очистка | `row_filter*`, `column_filter*`, `column_renamer*`, `column_resorter*`, `type_cast*`, `missing_value*`, `duplicate_filter*`, `value_replace*`, `outlier_filter*` | 1 |
| Преобразование | `sorter*`, `math_formula*`, `string_manipulation*`, `date_time*`, `rule_engine*`, `binner*`, `normalizer*`, `encoder*`, `sample_rows*`, `split` | 1–2 |
| Агрегация и соединение | `group_by*`, `row_aggregator*`, `pivot*`, `unpivot*`, `joiner*`, `concatenate*`, `window*`, `top_n_per_group*` | 2 |
| Статистика | `describe*`, `value_counts*`, `correlation*`, `covariance`, `histogram*`, `quantile*`, `outlier_detect*` | 3 |
| Временные ряды | `resample*`, `rolling*`, `diff*`, `date_range*` | 3 |
| Текст | `text_split`, `regex_extract`, `text_concat` | 3 |

Итого ~50 узлов; реализовано 52 — к концу P2 было 33, P3 добавил 11, P5 — ещё 8. Новые узлы
добавляются по задачам, но всегда по правилу «spec + executor + goldens + docs».

## 6. Форматы и хранение

- **Чтение:** CSV/TSV (delimiter, header, типы, NULL, gzip/zstd), XLSX, JSON/JSONL, Parquet,
  файл DuckDB (attach).
- **Запись:** CSV, JSON/JSONL, Parquet, XLSX.
- **Канон:** Parquet. `dap_ingest` / `POST /v1/datasets` один раз конвертирует вход в
  `~/.dap/cache/<hash>.parquet`; узлы принимают `dataset` или `file`. Повторный анализ
  не парсит CSV заново.
- **Правила:** NULL — только null bitmap; `""` — значение; типы — Arrow (Int64, Float64,
  Utf8, Boolean, Date32, Time64, Timestamp, Decimal128).

## 7. Lifecycle

- Адаптер при первом `tools/call`: `GET /v1/health`; если движок не отвечает — запускает jar,
  ждёт готовности, работает.
- Движок: `DAP_IDLE_TIMEOUT_SEC` (по умолчанию 900) сбрасывается каждым запросом; по
  истечении — graceful shutdown. `POST /v1/shutdown` и инструмент `dap_shutdown` — явная
  остановка.
- Адаптер переживает остановку: при `connection refused` поднимает движок заново.
- Ни launchd, ни Docker, ни фонового демона в ежедневном пути.

## 8. Качество и воспроизводимость

- **Goldens:** сценарий на волну — фикстура «вход → ожидаемый выход», прогон без сети и
  без KNIME.
- **Контракт ошибок:** 400 — невалидный config, 404 — неизвестный id, 500 — ошибка
  исполнения, 408 — timeout sync-run.
- **Схемы:** `configSchema` валидируется до исполнения; `/v1/nodes/{id}/dialog` отдаёт
  поля, типы, умолчания.
- **`make check`:** сборка + unit + goldens + lint; JDK 21 через `DAP_JDK`, зависимости
  пиннятся `mvnw`, в рантайме сети нет.
- **Документы:** `ARCHITECTURE.md` (как устроено), `NODES.md` (каталог и конфиги),
  `QUALITY.md` (правила сравнения и приёмки), `API.md` (контракт).

## 9. Фазы

| Фаза | Что | Критерий «готово» | Время |
|---|---|---|---|
| **P0. Отвязка** ✅ | goldens-native, удаление KNIME, свой `workflow.json` (save/load), docs | закрыто 06.11.2026: `make check` зелёный, 13/13 goldens, save→load round-trip | 3 дня |
| **P1. Lifecycle + IO** ✅ | idle-timeout, `dap_shutdown`, 5 IO-узлов (parquet/json readers, csv/parquet/json writers), `dap_ingest` | закрыто 06.11.2026: 16 узлов, 247 тестов, `make check` зелёный, ingest→parquet_reader→table проверен; DSH-плагин делает пользователь | 4 дня |
| **P2. Ядро аналитики** ✅ | 17 узлов: очистка (type_cast, duplicate_filter, value_replace, column_resorter, outlier_filter), преобразование (date_time, rule_engine, binner, normalizer, encoder, sample_rows), форма (row_aggregator, pivot, unpivot, window, top_n_per_group, concatenate) | закрыто 06.11.2026: 33 узла, 332 теста, 14 goldens, `make check` зелёный | 5 дней |
| **P3. Статистика и время** ✅ | 11 узлов: describe, value_counts, correlation, histogram, quantile, outlier_detect, resample, rolling, diff, date_range, excel_writer | закрыто 06.11.2026: 44 узла, 401 тест, 15 goldens, `make check` зелёный | 5 дней |
| **P4. Hardening** ✅ | таймауты чтения, эвикция сессий/jobs/таблиц, контракт `{error, code, hint}`, soak | закрыто 15.11.2026: 534 теста, 25 goldens, `make check` и `make soak` зелёные | 3 дня |
| **P5. MCP-сервис** ✅ | 8 узлов, 3 расширения, 7 SQL-рецептов, `dap-mcp install/doctor`, resources/prompts, coverage harness | закрыто 15.11.2026: 52 узла, 30 инструментов, покрытие 68/69, `make install-e2e` | 1 день |

**Веха:** после P1 (~1,5 недели) — ежедневное использование на CSV/Excel; после P2 —
полноценная аналитика; после P4 — стабильный продукт.

## 10. Чего не делаем

- Polars (только по замеру ≥2×), auth, multi-tenancy, persistence-БД, HA, web-UI.
- 832 узла и «паритет с KNIME» как критерий приёмки.
- Docker и launchd как обязательный путь запуска.
- Узлы без goldens и без спецификации.

## 11. Риски и меры

| Риск | Мера |
|---|---|
| Пул узлов расползается | Правило «spec + executor + goldens + docs»; узлы добавляются по задачам |
| Удаление KNIME раньше переноса goldens | Жёсткий порядок: сначала goldens зелёные, потом `rm` |
| Parquet-кэш протухает | Ключ — content hash; `dap_ingest` идемпотентен |
| Большие файлы упираются в heap | Лимиты ingest, потоковая запись в Parquet, `DAP_HEAP` и temp-dir по умолчанию |
| DSH не видит MCP | Проверить коннектор в P1, до написания узлов |
| Потеря данных при shutdown | Graceful stop: дождаться активных jobs или пометить их cancelled |

## 12. Правила работы

- Каждый узел: JSON-спека, executor, ≥3 unit-теста, строка в `NODES.md`; golden —
  сценарий на волну.
- Правка `/v1` — запись в CHANGELOG и обновление `API.md`.
- Сначала корректность и воспроизводимость, потом скорость, потом красота.
- Коммиты — conventional, английский.
