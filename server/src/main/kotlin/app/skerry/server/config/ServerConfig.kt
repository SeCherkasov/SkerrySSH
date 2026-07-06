package app.skerry.server.config

/**
 * Server config from environment variables (single-.env model, see `docs/skerry-sync-design.md`
 * §5). All values have sane defaults for local runs; production only requires a stable [jwtSecret]
 * — otherwise a restart invalidates every issued token.
 *
 * Storage: defaults to a SQLite file next to the process; PostgreSQL is enabled by pointing
 * [databaseUrl] at `jdbc:postgresql://...` (driver is picked by URL scheme).
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val jwtIssuer: String,
    /** Static admin console token (`/admin/stats`). Empty means admin data endpoints are closed. */
    val adminToken: String,
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlSeconds: Long,
    /** Lifetime of a one-shot pairing session (variant B quick pairing). */
    val pairingTtlSeconds: Long,
    /** How long to retain tombstone records before physical cleanup. */
    val tombstoneRetentionDays: Long,
    /** Allowed CORS origins. Empty disables CORS (native clients aren't subject to it). */
    val corsHosts: List<String>,
    /** Upper bound on request body size in bytes (OOM/abuse guard). Enforced via Content-Length -> 413. */
    val maxRequestBodyBytes: Long,
) {
    val isPostgres: Boolean get() = databaseUrl.startsWith("jdbc:postgresql")

    val usesDefaultJwtSecret: Boolean get() = jwtSecret == DEFAULT_JWT_SECRET

    companion object {
        /** Known-unsafe default; production must override it (see the guard in Application.module). */
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
                // The dev default is intentionally obvious; CI/prod must set their own secret.
                jwtSecret = str("SKERRY_JWT_SECRET", DEFAULT_JWT_SECRET),
                jwtIssuer = str("SKERRY_JWT_ISSUER", "skerry-sync"),
                adminToken = str("SKERRY_ADMIN_TOKEN", ""),
                accessTokenTtlSeconds = long("SKERRY_ACCESS_TTL", 900),        // 15 minutes
                refreshTokenTtlSeconds = long("SKERRY_REFRESH_TTL", 2_592_000), // 30 days
                pairingTtlSeconds = long("SKERRY_PAIRING_TTL", 300),            // 5 minutes (design §3)
                tombstoneRetentionDays = long("SKERRY_TOMBSTONE_DAYS", 90),     // design §2
                corsHosts = str("SKERRY_CORS_HOSTS", "")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                maxRequestBodyBytes = long("SKERRY_MAX_BODY_BYTES", 4L * 1024 * 1024), // 4 MiB
            )
        }
    }
}
