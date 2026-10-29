# syntax=docker/dockerfile:1.7
# DAP: self-contained KNIME-рантайм + engine. Установленный KNIME не требуется.
# Сборка: docker build -f deploy/Dockerfile -t dap:1.0.0 .
FROM eclipse-temurin:21-jre-jammy

ARG DAP_UID=10001
ARG DAP_GID=10001

ENV DAP_PORT=7800 \
    DAP_BIND=0.0.0.0 \
    DAP_HEAP=2g \
    DAP_DATA=/opt/dap/workspace \
    DAP_JDK=/opt/java/openjdk \
    LANG=C.UTF-8

# curl — для HEALTHCHECK; python3 — только для перегенерации bundles.info при сборке.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl python3 ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid "${DAP_GID}" dap \
 && useradd --uid "${DAP_UID}" --gid "${DAP_GID}" \
      --create-home --home-dir /home/dap --shell /usr/sbin/nologin dap

WORKDIR /opt/dap

# 626 МБ vendored-рантайма; сначала самый тяжёлый слой, чтобы кэш переживал правки.
COPY --chown=dap:dap runtime/knime ./runtime/knime
COPY --chown=dap:dap build/com.dap.engine_1.0.0.jar ./build/com.dap.engine_1.0.0.jar
COPY --chown=dap:dap catalog ./catalog
COPY --chown=dap:dap adapters/mcp ./adapters/mcp
COPY --chown=dap:dap scripts/run.sh scripts/gen-bundles-info.py ./scripts/

# bundles.info/config.ini с хоста содержат абсолютные пути /Users/... — перегенерируем
# их для /opt/dap. Engine-бандл кладём в plugins: его сканирует gen-bundles-info.py.
RUN cp build/com.dap.engine_1.0.0.jar runtime/knime/plugins/com.dap.engine_1.0.0.jar \
 && chmod 0755 scripts/run.sh scripts/gen-bundles-info.py \
 && DAP_ROOT=/opt/dap python3 scripts/gen-bundles-info.py \
 && rm -rf runtime/knime/configuration/org.eclipse.osgi \
           runtime/knime/configuration/org.eclipse.core.runtime \
 && mkdir -p workspace \
 && touch workspace/.keep \
 && chown dap:dap workspace workspace/.keep

USER dap

EXPOSE 7800
VOLUME ["/opt/dap/workspace"]

HEALTHCHECK --interval=10s --timeout=3s --retries=12 CMD curl -fsS http://127.0.0.1:7800/health

ENTRYPOINT ["/opt/dap/scripts/run.sh"]
