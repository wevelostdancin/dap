# AGENTS.md — dap

DAP — автономный аналитический движок для агентов: HTTP API над vendored-рантаймом KNIME 5.12
(832 узла, сборка и исполнение workflow, профили, RO-Crate). Java 21 + JVM, Python — только для
тестовых клиентов.

## Окружение

- JDK 21: `DAP_JDK=/path/to/jdk-21` (см. README). `runtime/` — vendored KNIME, не коммитится.
- Python — только `~/miniforge3/bin/python3` (см. глобальный AGENTS.md); venv внутри проекта.
- `build/`, `runtime/`, `workspace/`, `.tools/`, `engine-native/target/` — игнорируются.

## Проверки перед «готово»

```bash
make test            # golden + contract на отдельном инстансе :7810 (не трогает :7800)
make test-native     # JUnit-suite engine-native
make build-engine    # com.dap.engine_1.0.0.jar против runtime/knime
make build-native    # engine-native/target/dap-native.jar
```

CI (GitHub Actions) нет — проверки локальные, `make test` и `make test-native`.

## Правила

- Ветки `phase1/*` живут в отдельных worktree (`.worktrees/`): не удалять, не переключать и не
  переписывать их историю без явного запроса.
- `catalog/` и `catalog/schemas/` — контрактные артефакты, коммитятся намеренно (не игнорировать).
- Ветка по умолчанию на GitHub — `main`; текущая рабочая — `phase3-native`.
- Коммиты — conventional commits, английский: `docs(roadmap): …`, `fix(engine): …`.
