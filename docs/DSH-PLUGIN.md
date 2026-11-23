# DSH-плагин dap-dsh

`dap-dsh` — bundle-пакет для DSH, который подключает MCP-сервер DAP к активному
профилю и даёт понятную диагностику при отсутствии бинарника. Плагин не содержит
рантайм, не скачивает бинарник и не выполняет postinstall.

## Установка

Сначала установите сам DAP — плагин только подключает уже установленный сервер:

```bash
curl -fsSL https://raw.githubusercontent.com/wevelostdancin/dap/main/scripts/install.sh | sh
~/.dap/bin/dap doctor --client dsh
```

Затем установите плагин. **Desktop-профилем управляет само приложение:**
`dsh plugin --profile desktop add` возвращает «profile desktop is managed
exclusively by the Electron application», поэтому CLI для Desktop не подходит.

1. Опубликуйте пакет (один раз, нужен `npm login`):

   ```bash
   cd dsh-plugin
   npm publish --access public
   ```

2. В Desktop откройте **Settings -> Plugins**, найдите `dap-dsh`, установите и
   перезапустите DSH, чтобы применился слой патчей.

Если плагинный менеджер умеет ставить по URL, используйте tarball из GitHub
Release: `.../releases/download/v1.3.0/dap-dsh-1.3.0.tgz` (после публикации
релиза).

Для обычного `dsh web` или отдельного профиля CLI работает:

```bash
dsh plugin --profile web add dap-dsh
dsh plugin --profile web remove dap-dsh
```

После установки/удаления перезапустите DSH. Каталог `~/.dap` остаётся на месте.

## Как работает

- `package.json` описывает bundle: `dsh.manifestVersion: 1`, `kind: plugin`,
  `dsh.bundle.patch: ./cordis.patch.yml`, пустые `capabilities` и `labels`,
  `engines.dsh >=0.2.2-0 <0.3.0` и опциональный peer `@deepseek-ai/dsh`.
- `index.js` экспортирует только `name`, `inject` и `apply`; на module scope
  нет побочных эффектов.
- `cordis.patch.yml` — массив строк загрузчика: сначала `insert` строки самого
  пакета (`id: dap-dsh`, `name: dap-dsh`), затем патч `mcp-manager` с сервером
  `dap`: `transport: stdio`, `command: !!js process.env.HOME + '/.dap/bin/dap'`,
  `args: ['mcp']`, `env.DAP_HOME`, `startupTimeoutMs: 60000`,
  `toolCallTimeoutMs: 300000`, `reconnect.enabled: true`.
- `bin/launch.mjs` — резервный лаунчер для запуска вне профиля: находит
  `~/.dap/bin/dap` (или `$DAP_HOME/bin/dap`), проверяет бит исполнения,
  запускает `dap mcp` с `stdio: 'inherit'`, передаёт SIGINT/SIGTERM и печатает
  команду установки с выходом 2, если бинарник отсутствует.
- Токен `!!js` вычисляется загрузчиком DSH при чтении патча; `dap` получает
  абсолютный путь, потому что DSH может очищать окружение дочернего процесса.

## Ограничения

- Патч задаёт всю запись `mcp-manager` по `id`. Если в профиле уже настроены
  другие MCP-серверы в этой записи, объедините их в собственном слое патчей.
- Плагин не проверяет и не обновляет версию DAP: это делает `install.sh` и
  `dap doctor`. Лаунчер печатает версию в stderr лучшим усилием, но не
  блокирует запуск, если `dap version` недоступен.
- Требуется установленный DAP 1.3.0 с `~/.dap/bin/dap`; без него сервер в DSH
  не запустится.
- Пакет не запрашивает capabilities; доступ к данным определяется самим
  MCP-сервером DAP.

## Проверка

```bash
python3 -m unittest tests.test_dsh_plugin -v
node --check dsh-plugin/index.js
node --check dsh-plugin/bin/launch.mjs
python3 scripts/check_repo.py
# опционально, при наличии сети и пакета:
npx --yes dsh-standards dsh-plugin --scope entry --entry-claim static
```

Ручная проверка после установки: в сессии DSH должны появиться инструменты
`mcp__dap__*`; `~/.dap/bin/dap doctor --client dsh` должен пройти без ошибок.
Отсутствие `!!js` в поддержке `yaml.safe_load` ожидаемо: тест снимает тег перед
разбором и дополнительно проверяет исходный текст.

## Лицензия

Apache-2.0; текст совпадает с корневым `LICENSE`.
