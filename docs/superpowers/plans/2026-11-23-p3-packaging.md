# План P3 «Упаковка и принятие»

**Дата:** 23.11.2026
**Спека:** [2026-11-23-p3-packaging-design.md](../specs/2026-11-23-p3-packaging-design.md)

## Правила

- Ветка интеграции: `p3-packaging`.
- Каждый исполнитель работает в своём worktree; общий worktree — только чтение.
- Коммиты conventional, английский. Документация — русский/английский, без CJK.
- Перед сдачей: `python3 scripts/check_repo.py` и профильные тесты.

## Потоки

### A. Упаковка (owner: packaging)

Файлы: `scripts/package.py`, `scripts/jlink-runtime.sh`, `scripts/python-runtime.sh`,
`scripts/install.sh`, `scripts/dap.sh`, `Makefile`, `tests/test_package.py`,
`tests/package_e2e.py`, `docs/INSTALL.md`, `docs/PACKAGING.md`,
`.github/workflows/release.yml`.

Задачи:

1. `make runtime` — jlink в `build/package/runtime`; модули из `jdeps` + запас.
2. `make python-runtime` — скачать python-build-standalone, проверить SHA256.
3. `make package` — собрать `build/package/dap-<ver>-<os>-<arch>/` и tar.gz.
4. `scripts/dap.sh` — subcommands engine/mcp/doctor/install/version/home.
5. `scripts/install.sh` — download/verify/unpack/PATH/client/doctor.
6. `tests/package_e2e.py` — распаковать, health, analyze, recipe.
7. Документация установки и упаковки.
8. Release workflow: тег `v*` → matrix macOS/Linux × x64/arm64 → archives +
   SHA256 + GitHub Release.

Приёмка: `make package` и `python3 tests/package_e2e.py` зелёные; архив не
требует JDK/Python.

### B. Рецепты и промпты (owner: recipes)

Файлы: `recipes/*.json`, `examples/data/*.csv`, `examples/README.md`,
`adapters/mcp/resources.py`, `tests/test_mcp_resources.py`,
`tests/mcp_recipe_e2e.py`, `docs/MCP.md` (раздел рецептов).

Задачи:

1. 8 рецептов из спеки; каждый с `recipe.dataset`, `recipe.params`, `output`.
2. Demo-данные ≤ 200 строк, детерминированные, с колонками под рецепты.
3. 7 новых промптов + рендер; тесты на аргументы и текст.
4. E2E: `dap_recipe_run` каждого рецепта на demo-данных.
5. Документация: таблица рецептов, примеры, как адаптировать колонки.

Приёмка: `python3 -m unittest tests.test_mcp_resources` и
`python3 tests/mcp_recipe_e2e.py` зелёные.

### C. CI-гейты (owner: ci)

Файлы: `tests/p6_bench.py`, `tests/test_p6_bench.py`, `tests/p6_bench_gate.json`,
`tests/p6_bench_reference.json`, `tests/reference/validate_dap.py`,
`tests/reference/requirements.txt`, `tests/test_validate_gate.py`,
`.github/workflows/ci.yml`, `Makefile` (только targets `accuracy`, `bench-gate`).

Задачи:

1. `p6_bench.py --gate FILE`: проверка порогов, exit 1 при нарушении, `--json`.
2. `--reference FILE` для checked-in эталона; accuracy для iris/p2.
3. `validate_dap.py --json OUT --min-cases N`; сводка и exit code.
4. `requirements.txt` с pinned версиями.
5. Unit-тесты парсера gate (без движка).
6. CI jobs `accuracy` и `bench`; артефакты.
7. `make accuracy`, `make bench-gate`; документация в `docs/QUALITY.md`.

Приёмка: `python3 -m unittest tests.test_p6_bench tests.test_validate_gate`;
`make bench-gate` на smoke зелёный; CI YAML валиден.

### D. DSH-плагин и open-source (owner: plugin)

Файлы: `dsh-plugin/` (`package.json`, `cordis.patch.yml`, `bin/launch.mjs`,
`README.md`, `LICENSE`), `tests/test_dsh_plugin.py`, `docs/DSH-PLUGIN.md`,
`CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `CHANGELOG.md`,
`.github/ISSUE_TEMPLATE/*`, `.github/pull_request_template.md`.

Задачи:

1. Bundle-пакет `dap-dsh` с patch, engines, files, license.
2. Launcher: найти `~/.dap/bin/dap`, проверить версию, понятная ошибка.
3. Тест: манифест, YAML, launcher, отсутствие CJK.
4. Open-source файлы; `CHANGELOG` 1.3.0.
5. Документация установки плагина (Desktop UI и CLI).

Приёмка: `python3 -m unittest tests.test_dsh_plugin`;
`npx dsh-standards dsh-plugin --scope entry --entry-claim static` (или not-run).

### E. Интеграция (owner: lead)

1. CJK-проверка в `scripts/check_repo.py`.
2. Свести `Makefile` и `ci.yml` после потоков A/C.
3. Обновить `README.md`, `VERSION`, `api/openapi.yaml`, `adapters/mcp/dap_mcp.py`.
4. `make check`, `make install-e2e`, `make package-e2e`, `make bench-gate`.
5. Финальный diff, коммиты, отчёт.

## Порядок слияния

1. A (база: launcher, package, docs).
2. C (гейты; Makefile/ci.yml — lead).
3. B (рецепты; `resources.py` независим).
4. D (плагин; зависит от A).
5. E — финальная проверка.

## Definition of Done

- Все критерии приёмки спеки выполнены.
- Нет CJK в документации.
- `VERSION` 1.3.0, OpenAPI и адаптер согласованы.
- CI зелёный; артефакты релиза собираются.
