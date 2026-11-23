# Релиз DAP

Релиз — тег `v<version>` в GitHub. CI собирает архивы под macOS/Linux и
x86_64/arm64, проверяет контрольные суммы и создаёт GitHub Release.

## Перед релизом

1. Обновить `VERSION`, `api/openapi.yaml` (`info.version`) и строку версии в
   `adapters/mcp/dap_mcp.py`.
2. Обновить `CHANGELOG.md`.
3. Прогнать:

   ```bash
   make check
   make install-e2e
   make bench-gate
   build/refvenv/bin/python tests/reference/validate_dap.py --min-cases 27
   ```

4. Убедиться, что `scripts/check_repo.py` не находит CJK в документации.

## Тег и сборка

```bash
git tag -a v1.3.0 -m "DAP 1.3.0"
git push origin v1.3.0
```

`.github/workflows/release.yml` собирает:

| Артефакт | Платформа |
|---|---|
| `dap-1.3.0-macos-arm64.tar.gz` | macOS Apple Silicon |
| `dap-1.3.0-macos-x64.tar.gz` | macOS Intel |
| `dap-1.3.0-linux-x64.tar.gz` | Linux x86_64 |
| `dap-1.3.0-linux-arm64.tar.gz` | Linux arm64 |

Плюс `.sha256` для каждого и копии `dap-latest-<os>-<arch>.tar.gz` для
установщика.

## Проверка релиза

```bash
curl -fsSL https://raw.githubusercontent.com/wevelostdancin/dap/main/scripts/install.sh | sh -s -- --version 1.3.0
~/.dap/bin/dap version
~/.dap/bin/dap doctor
```

## Плагин DSH

Пакет `dsh-plugin/` версионируется вместе с движком. Публикация:

```bash
cd dsh-plugin
npm publish --access public
```

Registry отвечает `202 Accepted`: версия появляется в `npm view dap-dsh versions`
через несколько минут. Служебная версия `0.0.0-stage` — placeholder npm для
обрабатываемого первого релиза, её не удаляют. Проверка после публикации:
`npm view dap-dsh dist-tags` должен показывать `latest: <версия>`, а integrity
tarball — совпадать с `npm pack --dry-run --json`.

Перед публикацией — `npx dsh-standards dsh-plugin --scope entry --entry-claim static`.

## Откат

GitHub Release можно снять; установщик с `--version` ставит предыдущую версию.
Пользовательские `recipes/` и `build/` сохраняются при `--force`.
