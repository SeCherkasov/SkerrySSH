package app.skerry.ui.vault

/**
 * Idle-timeout auto-lock threshold (Settings -> Security -> Auto-lock). Drives the idle timer in
 * [app.skerry.ui.vault.VaultGate]: inactivity longer than [idleMs] locks the vault. `null` [idleMs]
 * ([Never]) disables the idle timer (background auto-lock is a separate policy, see
 * `deviceMandatesAutoLock`). Stable [id] survives restarts (persisted to file).
 */
enum class AutoLockDuration(val id: String, val idleMs: Long?) {
    OneMinute("1m", 60_000L),
    FiveMinutes("5m", 5 * 60_000L),
    FifteenMinutes("15m", 15 * 60_000L),
    ThirtyMinutes("30m", 30 * 60_000L),
    Never("never", null);

    companion object {
        /** Default value: 5 minutes. */
        val DEFAULT: AutoLockDuration = FiveMinutes

        /** Parses a stable [id] from storage; unknown/blank falls back to [DEFAULT]. */
        fun fromId(id: String?): AutoLockDuration = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
