# Skerry Sync server (AGPL-3.0). Build context = repo root: docker build -f server/Dockerfile .
# serverOnly исключает Android-модули, чтобы образу не нужен был Android SDK.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon -PserverOnly :server:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server/ /app/

# Непривилегированный пользователь (least-privilege): RCE в процессе не должен давать root в контейнере.
# Том /data принадлежит ему, чтобы SQLite-файл создавался без root.
RUN groupadd --system skerry && useradd --system --gid skerry --home /app skerry \
    && mkdir -p /data && chown -R skerry:skerry /app /data
USER skerry

# По умолчанию SQLite-файл в томе /data; смонтируйте том для персистентности.
ENV SKERRY_HOST=0.0.0.0 \
    SKERRY_PORT=8080 \
    SKERRY_DB_URL=jdbc:sqlite:/data/skerry-sync.db
VOLUME ["/data"]
EXPOSE 8080

# Контейнерный health-check бьёт в открытый liveness-эндпоинт.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
    CMD ["sh", "-c", "wget -qO- http://localhost:${SKERRY_PORT}/healthz || exit 1"]

ENTRYPOINT ["/app/bin/server"]
