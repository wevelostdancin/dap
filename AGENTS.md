# AGENTS.md — dap

DAP — локальный аналитический бэкенд для агентов: один JVM-процесс (Java 21, DuckDB,
Arrow, Javalin), HTTP `/v1` и stdio MCP-адаптер. Без KNIME.

## Окружение

- JDK 21: `DAP_JDK` (по умолчанию `~/Projects/tools/jdk-21.0.13+9/Contents/Home`).
- Python — только `~/miniforge3/bin/python3` (см. глобальный AGENTS.md); venv внутри проекта.
- `engine-native/target/`, `build/`, `workspace/`, `/tmp/dap-goldens` — игнорируются.

## Проверки перед «готово»

```bash
make check          # JUnit + golden suite + lint
make test-native    # JUnit
make goldens        # golden suite (сам поднимает движок на :7811 и гасит его)
make lint           # scripts/check_repo.py
```

CI — `.github/workflows/ci.yml`: unit, package, lint, goldens, smoke.

## Правила

- Рабочая ветка — `phase3-native`; `phase1/*` живут в отдельных worktree — не трогать.
- `engine/`, `runtime/`, `catalog/` удалены вместе с KNIME: не возвращать.
- Новый узел = `NodeSpec` JSON + executor + unit-тесты + golden + строка в `docs/NODES.md`.
- Правка `/v1` — `api/openapi.yaml` + `docs/API.md`.
- Коммиты — conventional commits, английский: `feat(nodes): …`, `fix(engine): …`.
