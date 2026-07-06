package app.skerry.shared.vault

/**
 * Parsed local moment of a security event for UI: whole days ago ([daysAgo], >= 0) and time of
 * day ([timeOfDay], "HH:MM" in local zone). Day granularity — the UI builds the label from
 * localized strings ("today"/"yesterday"/"N days ago") rather than a java.time locale.
 */
data class SecurityMoment(val daysAgo: Int, val timeOfDay: String)

/**
 * Parse an ISO-8601 event timestamp ([SecurityEvent.at], as written by [Vault]'s clock) into a
 * local [SecurityMoment]. `null` if the string doesn't parse. Thin wrapper over the platform
 * clock/timezone (JVM: java.time), hence [expect]/[actual] shared across JVM targets.
 */
expect fun securityMoment(iso: String): SecurityMoment?
