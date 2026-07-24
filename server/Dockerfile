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
# uid/gid are pinned (999): without --uid the allocation follows the base image's user table, so a
# base bump could shift it and lock the process out of an existing /data volume (SQLITE_READONLY).
# 1000 is taken by the base image's "ubuntu" user. Bind-mount owners: chown to 999:999.
RUN groupadd --system --gid 999 skerry && useradd --system --uid 999 --gid skerry --home /app skerry \
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
