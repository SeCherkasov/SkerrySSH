package app.skerry.server

import app.skerry.server.config.ServerConfig
import app.skerry.server.db.Db
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Self-hosted sync-сервер Skerry (Phase 2, AGPL-3.0). Zero-knowledge: хранит только шифроблобы
 * и метаданные синхронизации; протокол и модель угроз — `docs/skerry-sync-design.md`.
 * Конфигурация — переменные окружения (см. [ServerConfig], `.env.example`).
 */
fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

/** Точка входа Ktor: проверяет конфиг, подключает БД, собирает сервер, запускает уборку. */
fun Application.module(config: ServerConfig = ServerConfig.fromEnv()) {
    guardConfig(config)
    val database = Db.connect(config)
    val services = Services(config, database)
    configureServer(services)
    scheduleCleanup(services)
}

/**
 * Fail-fast при заведомо небезопасной конфигурации: дефолтный JWT-секрет в открытом коде
 * позволил бы подделать токен любого аккаунта. В проде запуск с ним запрещён; для локальной
 * разработки разблокируется явным `SKERRY_DEV=1` (security-ревью H1).
 */
private fun guardConfig(config: ServerConfig, env: Map<String, String> = System.getenv()) {
    if (config.usesDefaultJwtSecret && env["SKERRY_DEV"] != "1") {
        error(
            "SKERRY_JWT_SECRET не задан (используется небезопасный дефолт). Задайте устойчивый " +
                "секрет (openssl rand -base64 48) либо SKERRY_DEV=1 для локальной разработки.",
        )
    }
}

/** Тромбстоуны команд живут 90 дней (политика tombstone дизайн-дока §2), дальше — возрастная уборка. */
private const val TEAM_TOMBSTONE_TTL_MILLIS = 90L * 24 * 60 * 60 * 1000

/**
 * Периодически чистит истёкшие pairing-сессии (capability-коды не копятся на диске) и старые
 * team-тромбстоуны: в team-scope watermark-компакции нет — состав команды нестабилен.
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
