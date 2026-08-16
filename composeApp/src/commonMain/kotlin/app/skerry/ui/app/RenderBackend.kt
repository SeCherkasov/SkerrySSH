package app.skerry.ui.app

/**
 * The app's Skia render backend (F-30) — the closest thing this client has to FreeRDP's
 * `/gdi:hw` vs `/gdi:sw`. [AUTO] leaves the choice to Skiko; [HARDWARE] forces the platform's GPU
 * API; [SOFTWARE] forces the raster path (and, with it, software H.264 decode — F-29). Read once
 * at startup on desktop, before AWT/Skiko initialise, so a change needs a restart; Android has no
 * Skiko property and ignores it.
 */
enum class RenderBackend(val id: String) {
    AUTO("auto"),
    HARDWARE("hardware"),
    SOFTWARE("software");

    companion object {
        val DEFAULT = AUTO
        fun fromId(id: String): RenderBackend = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
