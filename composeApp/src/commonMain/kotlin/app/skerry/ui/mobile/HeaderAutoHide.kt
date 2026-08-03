package app.skerry.ui.mobile

/**
 * How long the mobile terminal's session header stays visible before it auto-hides
 * (More → Appearance → Terminal → Header auto-hide). [Never] keeps it permanently
 * visible — the swipe-down reveal exists but a permanently shown header needs no gesture.
 */
enum class HeaderAutoHideDelay(val id: String, val hideAfterMs: Long?) {
    Never("never", null),
    ThreeSeconds("3s", 3_000L),
    FiveSeconds("5s", 5_000L),
    TenSeconds("10s", 10_000L),
    FifteenSeconds("15s", 15_000L),
    ThirtySeconds("30s", 30_000L),
    SixtySeconds("60s", 60_000L);

    companion object {
        /** Default: keep the header visible (no auto-hide). */
        val DEFAULT: HeaderAutoHideDelay = Never

        /** Parses a stable [id] from storage; unknown/blank falls back to [DEFAULT]. */
        fun fromId(id: String?): HeaderAutoHideDelay = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
