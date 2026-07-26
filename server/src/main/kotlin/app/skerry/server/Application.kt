package app.skerry.server

import app.skerry.server.config.ServerConfig
import app.skerry.server.db.Db
import app.skerry.server.metrics.ServerMetrics
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Skerry self-hosted sync server (AGPL-3.0). Zero-knowledge: stores only ciphertext and sync
 * metadata. Configured via
 * environment variables (see [ServerConfig], `.env.example`).
 */
fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

/** Ktor entry point: validates config, connects the DB, wires the server, starts cleanup. */
fun Application.module(config: ServerConfig = ServerConfig.fromEnv()) {
    guardConfig(config)
    // Metrics come first: the registry has to exist before the pool so HikariCP can publish into it.
    val metrics = ServerMetrics(config, version = SERVER_VERSION)
    monitor.subscribe(ApplicationStopped) { metrics.close() }
    val database = Db.connect(config, metrics.registry)
    val services = Services(config, database, metrics)
    configureServer(services)
    scheduleCleanup(services)
    startObservers(services)
}

/**
 * Fails fast on a known-unsafe config: the default JWT secret is public, so anyone could forge a
 * token for any account. Startup with it is blocked in prod; local dev unlocks it via explicit
 * `SKERRY_DEV=1`. `SKERRY_METRICS=token` without a token is refused for the same reason — a typo
 * there would otherwise publish the exposition to anyone who asks.
 */
internal fun guardConfig(config: ServerConfig, env: Map<String, String> = System.getenv()) {
    if (config.usesDefaultJwtSecret && env["SKERRY_DEV"] != "1") {
        error(
            "SKERRY_JWT_SECRET is not set (an insecure default is in use). Provide a strong " +
                "secret (openssl rand -base64 48) or set SKERRY_DEV=1 for local development.",
        )
    }
    if (config.metricsTokenMissing) {
        error(
            "SKERRY_METRICS=token requires SKERRY_METRICS_TOKEN (openssl rand -hex 24). " +
                "Use SKERRY_METRICS=open only when /metrics is unreachable from outside the host.",
        )
    }
}

/**
 * Background observers: the readiness probe (also feeding `skerry_db_up`) and the metrics inventory
 * snapshot. Both are deliberately off the request path — nothing here may run during a scrape or a
 * readiness check, because the default SQLite pool is a single connection.
 */
private fun Application.startObservers(services: Services) {
    services.dbProbe.start(this)
    val interval = services.config.metricsInventoryIntervalSeconds
    if (interval > 0) services.inventory.start(this, interval)
}

/** Team tombstones live 90 days (design doc §2 tombstone policy), then are aged out. */
private const val TEAM_TOMBSTONE_TTL_MILLIS = 90L * 24 * 60 * 60 * 1000

/**
 * Periodically purges expired pairing sessions (capability codes don't pile up on disk) and old
 * team tombstones; team scope has no watermark compaction since team membership is unstable.
 */
private fun Application.scheduleCleanup(services: Services) {
    launch {
        while (true) {
            delay(15 * 60 * 1000L)
            runCatching { services.pairing.cleanupExpired() }
                .onFailure { log.warn("pairing cleanup failed", it) }
            runCatching {
                val cutoff = java.time.Instant
                    .ofEpochMilli(System.currentTimeMillis() - TEAM_TOMBSTONE_TTL_MILLIS)
                    .toString()
                services.teamRecords.purgeTombstones(cutoff)
            }.onFailure { log.warn("team tombstone cleanup failed", it) }
        }
    }
}
