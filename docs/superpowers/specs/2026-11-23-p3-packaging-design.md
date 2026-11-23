# P3 «Упаковка и принятие» — дизайн

**Дата:** 23.11.2026
**Статус:** утверждён к реализации
**Ветка:** `p3-packaging`
**Связанные документы:** [ROADMAP](../ROADMAP.md), [MCP](../../MCP.md), [QUALITY](../../QUALITY.md)

## 1. Задача

DAP уже решает аналитическую задачу, но установка требует JDK 21, Python 3.10+,
сборки Maven и ручной прописи MCP-сервера. Для публичного open-source проекта это
главный барьер принятия: новый пользователь не должен собирать движок и искать
`JAVA_HOME`.

Нужно сделать установку «одной командой», убрать системные зависимости, дать
готовые сценарии для агента и закрепить точность/стоимость ответа в CI.

## 2. Цели

1. Самодостаточный архив под macOS и Linux (x86_64, arm64) без JDK и без Python
   на машине пользователя.
2. Установка одной командой: `install.sh` и DSH-плагин.
3. 8 готовых рецептов и MCP-промптов: качество, когорты, A/B, временные ряды,
   сегментация, регрессия, воронка, отчёт.
4. Обязательные CI-гейты: `validate_dap.py` и `p6_bench.py` с порогами.
5. Публичный open-source baseline: лицензия, CONTRIBUTING, SECURITY, CHANGELOG,
   release workflow, проверка на CJK-символы в документации.

## 3. Не цели

- Переписывать движок на Rust или портировать MCP-адаптер на Java.
- GraalVM native-image как обязательный артефакт: только таймбокс-спайк, решение
  по критериям ниже.
- Docker, launchd, многопользовательский режим, web-UI.
- Публикация в Homebrew и подпись Apple Developer ID (опционально позже).

## 4. Текущее состояние

- `make build-native` → `engine-native/target/dap-native.jar` (~45 МБ), JDK 21
  через `DAP_JDK`.
- `make dist` → `dist/`: jar, `adapters/mcp`, `scripts/dap-mcp`, `VERSION`,
  `SHA256SUMS`. JRE и Python внутри нет.
- Установка: скопировать `dist/` в `~/.dap`, выставить `DAP_HOME`, иметь JDK и
  Python, выполнить `dap_cli.py install --client dsh`.
- Рецепты: механизм есть, поставки нет.
- CI: unit, package, lint, goldens, coverage, MCP, smoke; `validate_dap.py` и
  `p6_bench.py` не запускаются.

## 5. Архитектура поставки

### 5.1 Артефакт

```
dap-<version>-<os>-<arch>/
  bin/dap                 # единая точка входа: engine | mcp | doctor | version
  bin/dap-engine          # лаунчер jlink-runtime
  bin/dap-mcp             # лаунчер MCP-адаптера на встроенном Python
  lib/dap-native.jar
  runtime/                # jlink JRE (java.base, java.sql, java.naming, ...)
  python/                 # python-build-standalone 3.12 (stripped)
  adapters/mcp/*.py
  recipes/*.json
  examples/data/*.csv
  VERSION
  SHA256SUMS
```

Архив: `dap-<version>-<os>-<arch>.tar.gz`; рядом `.sha256`. Это «один архив,
ноль системных зависимостей», а не буквально один файл: адаптер остаётся Python,
движок — JVM. Буквально один файл возможен только через native-image и порт
адаптера; это вне P3.

### 5.2 jlink-runtime

- Модули: вывод `jdeps --print-module-deps` плюс `jdk.crypto.ec`, `jdk.zipfs`,
  `jdk.charsets`, `jdk.localedata`.
- Флаги: `--strip-debug --no-header-files --no-man-pages --compress=2`.
- Проверка: `runtime/bin/java -jar lib/dap-native.jar` отвечает на `/v1/health`,
  проходит один golden и `dap_analyze` на fixture.
- CDS-архив — опционально, если старт улучшается ≥ 15 %.

### 5.3 Python-runtime

- `python-build-standalone` 3.12.16+20261110, stripped, pinned SHA256.
- Скачивается в `build/tools/python` один раз; `make python-runtime`.
- Если сеть недоступна, `make package DAP_BUNDLE_PYTHON=0` собирает архив с
  системным Python и явным предупреждением.

### 5.4 `bin/dap`

```
dap engine [args...]     # java -jar lib/dap-native.jar
dap mcp [args...]        # python adapters/mcp/dap_cli.py
dap doctor [--client dsh]
dap install [--client dsh]
dap version
dap home
```

Скрипт POSIX sh, без bash-измов; `DAP_HOME` по умолчанию — корень распаковки.
`DAP_JAVA_OPTS` и `DAP_HEAP` пробрасываются.

### 5.5 `install.sh`

1. Определяет OS/arch (`uname -s`, `uname -m`).
2. Скачивает архив с GitHub Releases (или берёт `--archive PATH`).
3. Проверяет SHA256 из `.sha256`.
4. Распаковывает в `~/.dap` (атомарно: `~/.dap.new` → rename).
5. Добавляет `~/.dap/bin` в `PATH` через маркерный блок в `~/.zshrc`/`~/.bashrc`
   (только с `--path`; по умолчанию печатает строку).
6. По `--client dsh|hermes` вызывает `dap install --client ...`; без флага — только
   `dap doctor`.
7. Никогда не перезаписывает существующий `~/.dap` без `--force`; сохраняет
   `~/.dap/recipes` и `~/.dap/build` при обновлении.

### 5.6 DSH-плагин

Пакет `dap-dsh` — bundle без runtime-кода:

- `package.json`: `dsh.bundle.patch`, `engines.dsh`, `license`, `files`.
- `cordis.patch.yml`: insert строки `dap` и patch `mcp-manager.servers.dap`.
- `bin/launch.mjs`: резолвит `~/.dap/bin/dap`, проверяет версию, печатает
  понятную ошибку с командой установки.
- Установка: `dsh plugin --profile desktop add dap-dsh` или Settings → Plugins.
- Плагин не скачивает бинарник сам: бинарник ставит `install.sh`. Это разделяет
  код и данные, не требует postinstall и работает с pnpm.

Если DSH не сохраняет `PATH` для дочернего процесса, patch использует
`!!js process.env.HOME + '/.dap/bin/dap'`; это проверяется в `tests/dsh_plugin_test.py`
статически и в ручном smoke.

### 5.7 Рецепты и промпты

8 рецептов в `recipes/`, каждый — `dap-native-workflow/1` + блок `recipe`:

| Рецепт | Узлы | Параметры |
|---|---|---|
| `data_quality` | parquet_reader → data_quality → markdown_report | dataset, report |
| `cohort_analysis` | parquet_reader → sql_query (когорты/retention) → markdown_report | dataset, report |
| `ab_test` | parquet_reader → ab_test → markdown_report | dataset, report |
| `time_series` | parquet_reader → resample → rolling → time_series(forecast) → markdown_report | dataset, report |
| `segmentation` | parquet_reader → cluster → group_by → markdown_report | dataset, report |
| `regression` | parquet_reader → linear_regression → markdown_report | dataset, report |
| `funnel` | parquet_reader → sql_query (шаги/конверсия) → markdown_report | dataset, report |
| `exec_report` | parquet_reader → group_by → chart → markdown_report | dataset, report, chart |

Демо-данные — `examples/data/*.csv` (детерминированные, ≤ 200 строк).
Промпты: `cohort_analysis`, `ab_test`, `time_series`, `segmentation`,
`regression`, `funnel`, `exec_report`; существующие три сохраняются.
Каждый промпт: прочитать `dap://recipes/<name>`, вызвать `dap_recipe_run` с
`dataset` и `params`, при несовпадении колонок — собрать эквивалент через
`dap_add_node`/SQL.

### 5.8 CI-гейты

- `accuracy`: refvenv из `tests/reference/requirements.txt`, сборка jar,
  `validate_dap.py --json build/accuracy.json --min-cases 27`; любой FAIL — красный.
- `bench`: `p6_bench.py --gate tests/p6_bench_gate.json`; жёсткие пороги:
  `totals.failed == 0`, `mcp.failedCalls == 0`, `mcp.callCount <= 12`,
  `mcp.totalTokens <= 4000`, `rows`/group counts exact, `numeric_means` |Δ| ≤ 1e-3.
  Латентность — мягкий бюджет (nightly), не PR-гейт.
- `package`: `make package` + `tests/package_e2e.py` на macOS и Linux.
- `docs`: проверка отсутствия CJK-символов в `.md`, `.yml`, `.json` (кроме
  `tests/fixtures`), `VERSION` == OpenAPI == адаптер.
- Branch protection: `accuracy`, `bench`, `package`, `lint` — required checks.

### 5.9 Open-source baseline

`README.md` (установка одной командой, скриншот/пример), `docs/INSTALL.md`,
`docs/PACKAGING.md`, `docs/RELEASING.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`,
`SECURITY.md`, `CHANGELOG.md`, issue/PR templates, `.github/dependabot.yml`,
release workflow по тегу `v*`.

## 6. Контракты и совместимость

- `/v1` не меняется; версия 1.3.0.
- `dap-mcp install` остаётся совместимым; новые пути — только в `dap` CLI.
- `DAP_HOME` — корень установки; `recipes/` и `examples/` читаются оттуда.
- `SHA256SUMS` покрывает все файлы архива, кроме самого файла.
- `install.sh` идемпотентен; повторный запуск не плодит блоки PATH.

## 7. Безопасность

- Скачивание только по HTTPS, pinned SHA256, отказ при несовпадении.
- `install.sh` не использует `sudo`, не пишет вне `~/.dap` и `~/.zshrc`/`~/.bashrc`.
- `tar` распаковывается с `--no-same-owner`; проверка, что архив не содержит
  абсолютных путей и `..`.
- MCP-сервер слушает только loopback; `DAP_BIND` сохраняет текущую защиту.
- Рецепты не содержат секретов и не выполняют сетевых вызовов.
- Плагин не запускает postinstall; бинарник ставится явной командой.

## 8. Тестирование

- `tests/test_package.py` — чистые функции (OS/arch, layout, checksums).
- `tests/package_e2e.py` — собрать архив, распаковать в temp HOME, `dap doctor`,
  `/v1/health`, `dap_analyze` на fixture, `dap_recipe_run` одного рецепта.
- `tests/test_mcp_resources.py` — 10 промптов, 8 рецептов, рендер.
- `tests/mcp_recipe_e2e.py` — прогнать все 8 рецептов на demo-данных.
- `tests/test_p6_bench.py` — парсер gate и порогов.
- `tests/test_validate_gate.py` — `--json`/`--min-cases`.
- `tests/dsh_plugin_test.py` — манифест, patch YAML, отсутствие CJK, launcher.
- `scripts/check_repo.py` — CJK, версия, наличие docs.

## 9. Критерии приёмки

1. На чистой машине без JDK/Python: `curl .../install.sh | sh` → `dap doctor` PASS.
2. `dap engine` + `dap mcp` работают из архива; `dap_analyze` на iris возвращает
   таблицу и профиль.
3. Все 8 рецептов проходят `dap_recipe_run` на demo-данных.
4. `make check`, `make install-e2e`, `make package-e2e` зелёные.
5. CI: accuracy 27/27, bench gate 0 failed, package на macOS/Linux.
6. В документации нет CJK; `VERSION` 1.3.0 согласован.
7. `dsh plugin` пакет проходит `npx dsh-standards --scope entry --entry-claim static`
   (или документированный `not-run`, если проверка недоступна).

## 10. Риски

| Риск | Мера |
|---|---|
| jlink не хватает модуля | smoke-прогон health + golden + analyze; добавить модуль |
| Python-runtime несовместим с macOS 12 | минимум macOS 13; проверить в CI |
| DSH patch не резолвит путь | `!!js` + launcher; ручной smoke, иначе документировать |
| p6_bench шумит по латентности | жёсткие только accuracy/tokens/failed |
| Native-image не собирается | спайк с таймбоксом, jlink остаётся основным |
| Рецепты ломаются на других колонках | demo-данные + промпт «адаптируй колонки» |
