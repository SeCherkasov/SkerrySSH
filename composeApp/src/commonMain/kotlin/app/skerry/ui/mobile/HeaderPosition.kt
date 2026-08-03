package app.skerry.ui.mobile

/**
 * Where the mobile terminal's session header sits (More → Appearance → Terminal → Header
 * position). [Top] is the default — the upstream layout; [Bottom] moves the status bar to the
 * bottom edge for phones whose punch-hole/notch cuts off the top of the screen. Persisted per
 * device, like the other appearance settings.
 */
enum class HeaderPosition(val id: String) {
    Top("top"),
    Bottom("bottom");

    companion object {
        /** Default: keep the header at the top (upstream layout). */
        val DEFAULT: HeaderPosition = Top

        /** Parses a stable [id] from storage; unknown/blank falls back to [DEFAULT]. */
        fun fromId(id: String?): HeaderPosition = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
