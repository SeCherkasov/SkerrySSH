package app.skerry.server

import app.skerry.server.config.ServerConfig
import app.skerry.server.db.Db
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Skerry self-hosted sync server (AGPL-3.0). Zero-knowledge: stores only ciphertext and sync
 * metadata. Configured via
 * environment variables (see [ServerConfig], `.env.example`).
 */
fun main() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    val config = ServerConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

/** Ktor entry point: validates config, connects the DB, wires the server, starts cleanup. */
fun Application.module(config: ServerConfig = ServerConfig.fromEnv()) {
    guardConfig(config)
    val database = Db.connect(config)
    val services = Services(config, database)
    configureServer(services)
    scheduleCleanup(services)
}

/**
 * Fails fast on a known-unsafe config: the default JWT secret is public, so anyone could forge a
 * token for any account. Startup with it is blocked in prod; local dev unlocks it via explicit
 * `SKERRY_DEV=1`.
 */
private fun guardConfig(config: ServerConfig, env: Map<String, String> = System.getenv()) {
    if (config.usesDefaultJwtSecret && env["SKERRY_DEV"] != "1") {
        error(
            "SKERRY_JWT_SECRET is not set (an insecure default is in use). Provide a strong " +
                "secret (openssl rand -base64 48) or set SKERRY_DEV=1 for local development.",
        )
    }
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
