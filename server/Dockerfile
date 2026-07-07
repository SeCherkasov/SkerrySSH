# Skerry Sync server (AGPL-3.0). Build context = repo root: docker build -f server/Dockerfile .
# serverOnly excludes the Android modules so the image doesn't need the Android SDK.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon -PserverOnly :server:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server/ /app/

# Unprivileged user (least privilege): RCE in the process must not grant root in the container.
# The /data volume belongs to it so the SQLite file is created without root.
RUN groupadd --system skerry && useradd --system --gid skerry --home /app skerry \
    && mkdir -p /data && chown -R skerry:skerry /app /data
USER skerry

# SQLite file lives in the /data volume by default; mount a volume for persistence.
ENV SKERRY_HOST=0.0.0.0 \
    SKERRY_PORT=8080 \
    SKERRY_DB_URL=jdbc:sqlite:/data/skerry-sync.db
VOLUME ["/data"]
EXPOSE 8080

# The container health check hits the open liveness endpoint.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
    CMD ["sh", "-c", "wget -qO- http://localhost:${SKERRY_PORT}/healthz || exit 1"]

ENTRYPOINT ["/app/bin/server"]
