package app.skerry.shared.files

/** Local calendar decomposition of a file timestamp, for the panel's modified column. */
data class LocalFileTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
)

/**
 * Converts [epochSeconds] to local wall-clock components in [zoneId] (`null` — the system zone).
 * Thin wrapper over the platform clock/timezone (JVM: java.time), hence `expect`/`actual` shared
 * across the JVM targets, like [app.skerry.shared.vault.securityMoment].
 */
expect fun localFileTime(epochSeconds: Long, zoneId: String? = null): LocalFileTime
