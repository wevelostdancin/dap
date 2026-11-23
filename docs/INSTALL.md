# Установка DAP

DAP поставляется одним архивом: JRE (jlink), Python-адаптер, движок, рецепты и
демо-данные. На машине пользователя **не нужны JDK и Python**.

Поддерживаются macOS 13+ и Linux, x86_64 и arm64.

## Одной командой

```bash
curl -fsSL https://raw.githubusercontent.com/wevelostdancin/dap/main/scripts/install.sh | sh
```

Установщик:

1. определяет OS и архитектуру;
2. скачивает `dap-latest-<os>-<arch>.tar.gz` из GitHub Releases;
3. сверяет SHA256;
4. распаковывает в `~/.dap`;
5. по флагу `--client dsh|hermes` прописывает MCP-сервер;
6. запускает `dap doctor`.

Флаги:

| Флаг | Смысл |
|---|---|
| `--version 1.4.0` | конкретная версия (по умолчанию `latest`) |
| `--home DIR` | каталог установки (по умолчанию `~/.dap`) |
| `--client dsh\|hermes` | сразу подключить к клиенту |
| `--path` | добавить `~/.dap/bin` в `~/.zshrc`/`~/.bashrc` |
| `--archive PATH` | локальный архив вместо скачивания |
| `--force` | обновить существующую установку |
| `--dry-run` | проверить, ничего не записывая |
| `--skip-doctor` | не запускать `dap doctor` |

## Вручную

```bash
VERSION=1.4.0
OS=macos          # macos | linux
ARCH=arm64        # arm64 | x64
curl -fLO "https://github.com/wevelostdancin/dap/releases/download/v$VERSION/dap-$VERSION-$OS-$ARCH.tar.gz"
curl -fLO "https://github.com/wevelostdancin/dap/releases/download/v$VERSION/dap-$VERSION-$OS-$ARCH.tar.gz.sha256"
shasum -a 256 -c "dap-$VERSION-$OS-$ARCH.tar.gz.sha256"
tar -xzf "dap-$VERSION-$OS-$ARCH.tar.gz"
mv "dap-$VERSION-$OS-$ARCH" ~/.dap
~/.dap/bin/dap doctor
```

## Из исходников

```bash
export DAP_JDK=$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home
make build-native
make package            # build/package/dap-<version>-<os>-<arch>.tar.gz
```

Для сборки нужен JDK 21; для запуска собранного архива — нет.

## Проверка

```bash
~/.dap/bin/dap version
~/.dap/bin/dap doctor --client dsh
```

`doctor` проверяет Python-адаптер, jar, `/v1/health`, stdio-прогон MCP, запись
`dap` в конфиге клиента и свободный порт. PASS означает рабочую установку.

## Подключение к DSH

```bash
~/.dap/bin/dap install --client dsh
```

Или через плагин `dap-dsh` (см. [DSH-PLUGIN.md](DSH-PLUGIN.md)).

## Обновление и удаление

```bash
curl -fsSL https://raw.githubusercontent.com/wevelostdancin/dap/main/scripts/install.sh | sh -s -- --force
rm -rf ~/.dap
```

`--force` сохраняет пользовательские `recipes/` и `build/` (кэш Parquet).

## Если что-то не работает

- `dap: command not found` — запустите `~/.dap/bin/dap` напрямую или добавьте
  `--path` при установке.
- `checksum mismatch` — скачайте архив заново; не запускайте установку из
  непроверенного зеркала.
- `doctor` не видит движок — проверьте, что порт `7800` свободен
  (`DAP_PORT` меняет порт).
- macOS блокирует бинарник — `xattr -d com.apple.quarantine ~/.dap/bin/dap`
  (архив не подписан Developer ID).

## Лицензия

Apache-2.0; см. [LICENSE](../LICENSE) и [NOTICE](../NOTICE).
