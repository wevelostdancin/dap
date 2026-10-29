# deploy: OCI-образ DAP

DAP упакован как self-contained образ: vendored KNIME-рантайм + engine + каталог узлов
внутри. **Установленный KNIME не нужен** — ни на хосте сборки, ни в рантайме.

## Состав

| Файл | Назначение |
| --- | --- |
| `Dockerfile` | сборка образа: Temurin JRE 21, пользователь `dap` (10001), `/opt/dap` |
| `docker-compose.yaml` | запуск с named volume, публикацией 7800 и healthcheck |
| `../scripts/package-oci.sh` | multi-arch OCI-архив (`build/dap-1.0.0.tar`) или single-arch `--load` |
| `../.dockerignore` | контекст без workspace, тестов, docs, `build/classes`, кэшей и OCI-архивов |

В образ попадают: `runtime/knime` (~683 МБ, 610 бандлов), `build/com.dap.engine_1.0.0.jar`
(+ копия в `runtime/knime/plugins`), `catalog/` (catalog.json, curated.json, JSON-схемы узлов),
`adapters/mcp`, `scripts/run.sh`, `scripts/gen-bundles-info.py`.
`catalog/schema-report.json` (4,2 МБ) исключён — это отчёт, а не рантайм-данные.

## Требования

- Docker 23+ с buildx. Проверено на Docker 29.8.0, buildx v0.37.0, BuildKit v0.33.0
  (Docker Desktop, macOS arm64).
- Multi-arch требует builder, умеющий обе платформы. Docker Desktop с containerd-store
  умеет «из коробки». Если драйвер `docker` не поддерживает multi-platform:

  ```sh
  docker buildx create --name dap-builder --driver docker-container --bootstrap --use
  ```

## Сборка

```sh
# текущая платформа
docker build --platform linux/arm64 -f deploy/Dockerfile -t dap:1.0.0 .

# multi-arch (без загрузки в локальный docker)
docker buildx build --platform linux/amd64,linux/arm64 \
  -f deploy/Dockerfile -t dap:1.0.0 .

# через compose (соберёт и запустит)
docker compose -f deploy/docker-compose.yaml up -d --build
```

Что происходит при сборке:

1. `apt-get install curl python3`: curl — для HEALTHCHECK, python3 — только чтобы
   перегенерировать `bundles.info`/`config.ini` (в них с хоста записаны абсолютные
   пути `/Users/...`; в образе они должны указывать на `/opt/dap`).
2. Engine-бандл копируется в `runtime/knime/plugins` **до** генерации — иначе
   `com.dap.engine` не попадёт в `bundles.info`.
3. Удаляются хостовые кэши `configuration/org.eclipse.osgi` и
   `configuration/org.eclipse.core.runtime`; Equinox создаст их заново от пользователя `dap`.
4. `DAP_JDK=/opt/java/openjdk`: `scripts/run.sh` по умолчанию ищет JDK в
   `~/Projects/tools/jdk-21...`, в образе JDK лежит в базовом Temurin.
5. `workspace/.keep` (uid 10001) — чтобы пустой named volume при первом монтировании
   получил владельца `dap`, а не root.

`deploy/docker-entrypoint.sh не нужен`: `scripts/run.sh` уже executable
(`-rwxr-xr-x`, проверено и на хосте, и в образе) и используется напрямую как `ENTRYPOINT`.
Параметры сборки: `--build-arg DAP_UID=10001 --build-arg DAP_GID=10001`.

## Запуск

```sh
docker run -d --name dap -p 7800:7800 -v dap-workspace:/opt/dap/workspace dap:1.0.0
# если 7800 занят:
docker run -d --name dap -p 7820:7800 -v dap-workspace:/opt/dap/workspace dap:1.0.0
```

Проверка:

```sh
curl -fsS http://127.0.0.1:7800/health
# {"ok":true,"name":"DAP","version":"1.0.0","api":"1","nodes":832,"coreNodes":141,
#  "workflows":0,"sessions":0,"jobs":0,"memory":{"usedMb":171,"maxMb":2048}}
curl -fsS http://127.0.0.1:7800/v1/health   # тот же ответ, алиас
```

`nodes` — все узлы каталога, `coreNodes` — узлы из `catalog/curated.json` и зависит от
его версии на момент сборки (в образе от 2026-10-29 — 141).

Переменные (значения по умолчанию из Dockerfile): `DAP_PORT=7800`, `DAP_BIND=0.0.0.0`,
`DAP_HEAP=2g`, `DAP_DATA=/opt/dap/workspace`, `DAP_JDK=/opt/java/openjdk`;
`run.sh` дополнительно читает `DAP_JOB_TIMEOUT` (600) и `DAP_MAX_WORKFLOWS` (100).
`DAP_DATA` — единственный каталог, которому нужна запись; смонтирован как VOLUME.

### Compose

```sh
docker compose -f deploy/docker-compose.yaml up -d --build
docker compose -f deploy/docker-compose.yaml ps
docker compose -f deploy/docker-compose.yaml down -v
```

Volume: `deploy_dap-workspace` (имя проекта берётся из каталога `deploy`), порт
`7800:7800`, `restart: unless-stopped`, healthcheck с `start_period: 60s`.

### Права

Контейнер работает от `dap` (uid/gid 10001). Named volume получает владельца
автоматически. **Bind-mount** пустого каталога с хоста — нет:

```sh
mkdir -p ./dap-workspace && sudo chown -R 10001:10001 ./dap-workspace
docker run -d -p 7800:7800 -v "$PWD/dap-workspace:/opt/dap/workspace" dap:1.0.0
```

## Healthcheck

```dockerfile
HEALTHCHECK --interval=10s --timeout=3s --retries=12 CMD curl -fsS http://127.0.0.1:7800/health
```

Проверка статуса: `docker inspect --format '{{.State.Health.Status}}' dap` (или
`docker ps` → `(healthy)`). `/health` и `/v1/health` оба отвечают 200.

## Multi-arch OCI

```sh
./scripts/package-oci.sh                 # linux/amd64 + linux/arm64 -> build/dap-1.0.0.tar
./scripts/package-oci.sh --single-arch   # только linux/<host arch>, docker --load
./scripts/package-oci.sh -t dap:1.0.0 -o /tmp/dap.tar -p linux/amd64
```

Скрипт проверяет наличие `runtime/knime/plugins`, launcher-бандла, engine-jar,
`catalog/catalog.json|curated.json|schemas`, executable `scripts/run.sh`, затем печатает
размер, sha256 и платформы из `index.json` (attestation-манифесты пропускаются).

Фактический результат (2026-10-29):

```text
[package-oci] OK: .../build/dap-1.0.0.tar
-rw-r--r--  1.3G  build/dap-1.0.0.tar
  sha256: b67661490a713d12885fb0a22594a88b9d708caf4cec44d11f1837c585dff9db
    platform: linux/amd64 sha256:4e91839ea9542d0cb6c4f3d35c792312e2acdc1fcb5d67374675d364edc9fc24
    platform: linux/arm64 sha256:0e0c4755bb4f45d8a24c7d116f2d12cb37c2b1441e4e402afc56f09b5b45158d
[package-oci] done in 54s
```

Загрузка и запуск из архива:

```sh
docker load -i build/dap-1.0.0.tar      # тег dap:1.0.0 сохраняется (проверено, ~23 c)
docker run --rm -p 7800:7800 dap:1.0.0
docker run --rm --platform linux/amd64 -p 7820:7800 dap:1.0.0
```

Публикация в registry (не проверялась): `skopeo copy --all oci-archive:build/dap-1.0.0.tar
docker://registry.example/dap:1.0.0`.

## Почему KNIME не нужен

- `runtime/knime` — vendored-копия платформы (610 бандлов, `p2` не используется); в образе
  она целиком, launcher — обычный jar, запускается `java -jar` из базового JRE.
- `build/com.dap.engine_1.0.0.jar` — OSGi-бандл `com.dap.engine`, кладётся в `plugins`
  и стартует через `-application com.dap.engine.service`.
- `catalog/` и `catalog/schemas` — каталог узлов (832 в собранном образе) и JSON-схемы;
  готовятся заранее скриптами `build-catalog.sh`.
- `bundles.info`/`config.ini` генерируются заново внутри образа, чтобы не тащить пути хоста.
- JDK — из `eclipse-temurin:21-jre-jammy`; внешний JDK/KNIME не требуются.

## Проверено

| Проверка | Результат |
| --- | --- |
| `docker buildx version` | `v0.37.0`, BuildKit `v0.33.0` |
| `docker build --platform linux/arm64 ...` | OK, 8m11s первый раз (сеть apt), ~30 c из кэша |
| запуск `-p 7820:7800` | `healthy`, `/health` и `/v1/health` → 200, `nodes=832`, `maxMb=2048` |
| `--platform linux/amd64` (эмуляция) | `healthy` за ~30 c, `uname -m` → `x86_64`, `/v1/health` → 200 |
| `docker compose ... up --wait` | `deploy-dap-1 Up (healthy)`, volume writable от uid 10001 |
| `docker load -i build/dap-1.0.0.tar` | OK, тег `dap:1.0.0`, обе платформы |
| образ (arm64, `docker image inspect`) | 1 743 766 140 байт (~1,74 ГБ); rootfs контейнера ~947 МиБ |
| OCI-архив | 1,3 ГБ, amd64 + arm64 + attestation |

Docker с containerd-store показывает разные «размеры» одного и того же образа
(1,7–3,5 ГБ) из-за учёта общих слоёв — ориентир для распространения: OCI-архив 1,3 ГБ.

## Ограничения

- Vendored-рантайм собран из macOS-установки KNIME: в нём только
  `org.eclipse.swt.cocoa.macosx.aarch64`. На Linux `org.knime.workbench.core` не
  стартует (`NoClassDefFoundError: org/eclipse/swt/SWTError`) — в логах есть
  `[service] workbench.core not started`, после чего сервис нормально поднимается:
  `[dap] listening ... nodes=832 core=141`. Headless-функциональность, каталог и
  `/v1/*` не затронуты; для UI-совместимости понадобился бы Linux-набор SWT-бандлов.
- `python3` остаётся в образе (~22 МБ) хотя нужен только при сборке; вынос в
  multi-stage возможен, но усложняет Dockerfile без влияния на рантайм.
- Multi-arch на не-Desktop Docker может потребовать отдельного `docker-container`
  builder'а (см. «Требования»).
- Том `/opt/dap/workspace` не содержит данных в образе; состояние KNIME живёт в нём.
