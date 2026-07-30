package app.skerry.shared.rdp

import kotlinx.serialization.Serializable

/**
 * How much of the remote desktop the session asks the server to draw.
 *
 * RDP settles this once, in the Client Info PDU (MS-RDPBCGR 2.2.1.11.1.1.1), and the server holds it
 * for the life of the session — which is why this belongs to the profile and not to the panel beside
 * a live picture: a menu there could only take effect on the next connection.
 *
 * [Medium] is what every session sent before the profile could choose, so an existing profile keeps
 * exactly the picture it had.
 *
 * Serialized by name (like [app.skerry.shared.ssh.ConnectionType]): enum order does not affect
 * stored profiles, and a missing field reads as [Medium].
 */
@Serializable
enum class RdpImageQuality {
    /** Everything decorative off, cursor effects included — for a link where each frame is paid for. */
    Low,

    /** No wallpaper, window drag, menu animations or theming; the rest of the desktop as it is. */
    Medium,

    /** The desktop as the user set it up, with font smoothing and composition asked for explicitly. */
    High,

    ;

    /** TS_EXTENDED_INFO_PACKET::performanceFlags for this level. */
    val performanceFlags: Int
        get() = when (this) {
            Low -> MEDIUM_FLAGS or PERF_DISABLE_CURSOR_SHADOW or PERF_DISABLE_CURSORSETTINGS
            Medium -> MEDIUM_FLAGS
            High -> PERF_ENABLE_FONT_SMOOTHING or PERF_ENABLE_DESKTOP_COMPOSITION
        }

    companion object {
        /** MS-RDPBCGR 2.2.1.11.1.1.1. */
        private const val PERF_DISABLE_WALLPAPER = 0x00000001
        private const val PERF_DISABLE_FULLWINDOWDRAG = 0x00000002
        private const val PERF_DISABLE_MENUANIMATIONS = 0x00000004
        private const val PERF_DISABLE_THEMING = 0x00000008
        private const val PERF_DISABLE_CURSOR_SHADOW = 0x00000020
        private const val PERF_DISABLE_CURSORSETTINGS = 0x00000040
        private const val PERF_ENABLE_FONT_SMOOTHING = 0x00000080
        private const val PERF_ENABLE_DESKTOP_COMPOSITION = 0x00000100

        private const val MEDIUM_FLAGS = PERF_DISABLE_WALLPAPER or PERF_DISABLE_FULLWINDOWDRAG or
            PERF_DISABLE_MENUANIMATIONS or PERF_DISABLE_THEMING

        /** What a profile that never chose gets: the picture this client has always asked for. */
        val DEFAULT = Medium
    }
}
