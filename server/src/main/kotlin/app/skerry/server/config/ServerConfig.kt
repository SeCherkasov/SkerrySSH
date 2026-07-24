package app.skerry.server.config

/**
 * One allowed CORS origin: [host] without a scheme (Ktor's `allowHost` rejects `://`), plus the
 * [schemes] to allow. A scheme-prefixed entry ("https://cdn.example.com") narrows to that scheme;
 * a bare host allows both http and https.
 */
data class CorsHost(val host: String, val schemes: List<String>)

/**
 * Server config from environment variables (single-.env model). All values have sane defaults for local runs; production only requires a stable [jwtSecret]
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
    val corsHosts: List<CorsHost>,
    /** Upper bound on request body size in bytes (OOM/abuse guard). Enforced via Content-Length -> 413. */
    val maxRequestBodyBytes: Long,
    /**
     * Trusted reverse-proxy IPs (the direct peers in front of the server). When a request's direct
     * peer is one of these, per-IP rate limits key on the real client IP from `X-Forwarded-For`;
     * otherwise the header is ignored (a client can't spoof it). Empty ⇒ no proxy, key on the
     * direct connection.
     */
    val trustedProxies: List<String>,
    /** Whether `POST /auth/register` accepts new accounts (Vaultwarden's SIGNUPS_ALLOWED). Closed ⇒ 403. */
    val registrationOpen: Boolean,
    /** Hard cap on total accounts (backstop for an instance left open). 0 ⇒ unlimited. */
    val maxAccounts: Int,
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
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .mapNotNull(::parseCorsHost),
                maxRequestBodyBytes = long("SKERRY_MAX_BODY_BYTES", 4L * 1024 * 1024), // 4 MiB
                trustedProxies = str("SKERRY_TRUSTED_PROXIES", "")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                // Default open for backward compatibility; anything other than "open" (case-insensitive) closes it.
                registrationOpen = str("SKERRY_REGISTRATION", "open").equals("open", ignoreCase = true),
                maxAccounts = int("SKERRY_MAX_ACCOUNTS", 0).coerceAtLeast(0),
            )
        }

        /**
         * Parse one SKERRY_CORS_HOSTS entry. Users paste full origins ("https://cdn.example.com/")
         * even though Ktor's `allowHost` wants a bare host — passing "://" through would crash the
         * server at startup. A scheme prefix narrows the allowed schemes to it; anything after the
         * first "/" (path, trailing slash) is dropped. Returns `null` for an entry with no host
         * left after stripping.
         */
        private fun parseCorsHost(raw: String): CorsHost? {
            val (schemes, rest) = when {
                raw.startsWith("https://", ignoreCase = true) -> listOf("https") to raw.drop("https://".length)
                raw.startsWith("http://", ignoreCase = true) -> listOf("http") to raw.drop("http://".length)
                else -> listOf("http", "https") to raw
            }
            val host = rest.substringBefore('/').trim()
            return if (host.isEmpty()) null else CorsHost(host, schemes)
        }
    }
}
