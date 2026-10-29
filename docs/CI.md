# CI

В репозитории два уровня проверок.

## 1. static (всегда)

GitHub Actions job `static` не требует runtime и проходит на чистом ubuntu-latest:

- `python scripts/check_repo.py` — версии, OpenAPI, каталог, shell-синтаксис, Python-синтаксис;
- `py_compile` для MCP-адаптера и тестов;
- `bash -n` и `shellcheck -S warning` для `scripts/*.sh`.

## 2. engine + golden + image (нужен runtime)

Job `engine` собирает движок, каталог, гоняет golden/contract тесты и single-arch
образ. Он запускается только при одном из условий:

1. В GitHub repository variable задан `DAP_RUNTIME_URL` — HTTPS-ссылка на архив
   `runtime/knime` (release asset). CI скачает его, проверит опциональный
   `DAP_RUNTIME_SHA256` и распакует в `runtime/knime`.
2. Задан `DAP_ENGINE_RUNNER` — label self-hosted runner, на котором
   `runtime/knime` уже лежит.

Если ни одного условия нет, job пропускается: без 680 МБ рантайма собрать
`com.dap.engine` и запустить тесты нельзя. Это осознанное решение: runtime
не хранится в git (GPLv3-бинарь и размер), а поставляется release-ассетом
или внутри OCI-образа.

### Как получить runtime-ассет

На машине, где уже есть vendored runtime:

    make runtime-archive
    # build/dap-runtime-1.0.0.tar.zst + sha256

Загрузите архив в GitHub Release и задайте repository variables:

    DAP_RUNTIME_URL    = https://github.com/<org>/<repo>/releases/download/v1.0.0/dap-runtime-1.0.0.tar.zst
    DAP_RUNTIME_SHA256 = <sha256 из вывода>

Локально CI-раннер можно проверить так:

    ./scripts/fetch-runtime.sh "$DAP_RUNTIME_URL"
    make build-engine
    make build-catalog
    make test
    ./scripts/package-oci.sh --single-arch

## Артефакты CI

| артефакт | что внутри |
|---|---|
| `dap-catalog` | `catalog/catalog.json`, `curated.json`, `schemas/*.json`, `schema-report.json` |
| `dap-engine-jar` | `build/com.dap.engine_1.0.0.jar` |
| `dap-image-amd64` | сжатый `docker save` образ для текущей платформы runner |

## Что не входит в CI

- Публикация в registry и multi-arch релиз — отдельный release-pipeline.
- Подпись/SBOM/скан лицензий — следующий шаг.
- Полный набор узлов: golden-тесты покрывают core-сценарии, не все 832 узла.
