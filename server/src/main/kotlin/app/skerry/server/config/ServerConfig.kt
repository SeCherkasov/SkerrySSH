package app.skerry.server.config

/**
 * Конфигурация сервера из переменных окружения (модель «один .env», см.
 * `docs/skerry-sync-design.md` §5). Все значения имеют разумные дефолты для локального
 * запуска; в проде обязателен только устойчивый [jwtSecret] — иначе при рестарте все
 * выданные токены инвалидируются (для одиночного инстанса допустимо, для прода — нет).
 *
 * Хранилище: по умолчанию SQLite-файл рядом с процессом; PostgreSQL включается заменой
 * [databaseUrl] на `jdbc:postgresql://…` (драйвер выбирается по схеме URL).
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val jwtIssuer: String,
    /** Статический токен админ-консоли (`/admin/stats`). Пустой ⇒ админ-эндпоинты с данными закрыты. */
    val adminToken: String,
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlSeconds: Long,
    /** Время жизни одноразовой pairing-сессии (вариант B быстрого паринга). */
    val pairingTtlSeconds: Long,
    /** Сколько хранить tombstone-записи до физической уборки. */
    val tombstoneRetentionDays: Long,
    /** Разрешённые CORS-источники. Пусто ⇒ CORS не включается (нативные клиенты ему не подвержены). */
    val corsHosts: List<String>,
    /** Верхняя граница тела запроса в байтах (защита от OOM/abuse). По Content-Length → 413. */
    val maxRequestBodyBytes: Long,
) {
    val isPostgres: Boolean get() = databaseUrl.startsWith("jdbc:postgresql")

    val usesDefaultJwtSecret: Boolean get() = jwtSecret == DEFAULT_JWT_SECRET

    companion object {
        /** Заведомо небезопасный дефолт; прод обязан переопределить (см. guard в Application.module). */
        const val DEFAULT_JWT_SECRET = "dev-insecure-change-me"

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            fun str(key: String, default: String) = env[key]?.takeIf { it.isNotBlank() } ?: default
            fun long(key: String, default: Long) = env[key]?.toLongOrNull() ?: default
            fun int(key: String, default: Int) = env[key]?.toIntOrNull() ?: default

            return ServerConfig(
                host = str("SKERRY_HOST", "0.0.0.0"),
                port = int("SKERRY_PORT", 8080),
                databaseUrl = str("SKERRY_DB_URL", "jdbc:sqlite:skerry-sync.db"),
                databaseUser = str("SKERRY_DB_USER", ""),
                databasePassword = str("SKERRY_DB_PASSWORD", ""),
                // Дев-дефолт намеренно очевиден; CI/прод обязаны задать свой секрет.
                jwtSecret = str("SKERRY_JWT_SECRET", DEFAULT_JWT_SECRET),
                jwtIssuer = str("SKERRY_JWT_ISSUER", "skerry-sync"),
                adminToken = str("SKERRY_ADMIN_TOKEN", ""),
                accessTokenTtlSeconds = long("SKERRY_ACCESS_TTL", 900),        // 15 минут
                refreshTokenTtlSeconds = long("SKERRY_REFRESH_TTL", 2_592_000), // 30 дней
                pairingTtlSeconds = long("SKERRY_PAIRING_TTL", 300),            // 5 минут (design §3)
                tombstoneRetentionDays = long("SKERRY_TOMBSTONE_DAYS", 90),     // design §2
                corsHosts = str("SKERRY_CORS_HOSTS", "")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                maxRequestBodyBytes = long("SKERRY_MAX_BODY_BYTES", 4L * 1024 * 1024), // 4 MiB
            )
        }
    }
}
