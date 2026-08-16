package app.skerry.ui.remote

import androidx.compose.ui.unit.IntSize
import app.skerry.shared.rdp.RdpClientSettings

/**
 * The desktop size an RDP session should ask for: the viewport it will live in, because RDP fixes
 * the resolution at connect time and anything else gets a scaled, soft picture (F-06). The width is
 * rounded down to the even value the protocol wants, both sides clamped to what a server accepts,
 * and an unmeasured viewport falls back to [fallback] rather than dialling a 0×0 desktop.
 */
fun rdpDesktopSize(viewport: IntSize, fallback: IntSize): IntSize {
    if (viewport.width <= 0 || viewport.height <= 0) return fallback
    return IntSize(
        (viewport.width and 1.inv())
            .coerceIn(RdpClientSettings.MIN_DIMENSION, RdpClientSettings.MAX_DIMENSION),
        viewport.height.coerceIn(RdpClientSettings.MIN_DIMENSION, RdpClientSettings.MAX_DIMENSION),
    )
}

/**
 * The Windows keyboard-layout id (LCID) for a locale, so the session comes up typing what the
 * local keyboard types instead of US (F-16). Language+country first, bare language second, US as
 * the last resort — a wrong-but-latin layout beats refusing to connect.
 */
fun keyboardLayoutFor(language: String, country: String): Int {
    val lang = language.lowercase()
    val exact = if (country.isEmpty()) null else KEYBOARD_LAYOUTS["$lang-${country.uppercase()}"]
    return exact ?: KEYBOARD_LAYOUTS[lang] ?: RdpClientSettings.KEYBOARD_LAYOUT_US
}

/** The local machine's active keyboard layout as an LCID; platform-detected. */
expect fun currentKeyboardLayout(): Int

// The common Windows keyboard-layout ids (MS-LCID), keyed by language and language-country.
private val KEYBOARD_LAYOUTS = mapOf(
    "ar" to 0x401, "bg" to 0x402, "cs" to 0x405, "da" to 0x406,
    "de" to 0x407, "de-CH" to 0x807,
    "el" to 0x408,
    "en" to 0x409, "en-GB" to 0x809, "en-AU" to 0xC09, "en-CA" to 0x1009,
    "es" to 0x40A, "es-MX" to 0x80A,
    "et" to 0x425, "fi" to 0x40B,
    "fr" to 0x40C, "fr-CH" to 0x100C, "fr-CA" to 0xC0C,
    "he" to 0x40D, "hr" to 0x41A, "hu" to 0x40E, "it" to 0x410,
    "ja" to 0x411, "ka" to 0x437, "kk" to 0x43F, "ko" to 0x412,
    "lt" to 0x427, "lv" to 0x426, "nb" to 0x414, "nl" to 0x413, "no" to 0x414,
    "pl" to 0x415,
    "pt" to 0x816, "pt-BR" to 0x416,
    "ro" to 0x418, "ru" to 0x419, "sk" to 0x41B, "sl" to 0x424, "sv" to 0x41D,
    "tr" to 0x41F, "uk" to 0x422, "be" to 0x423,
    "zh" to 0x804, "zh-TW" to 0x404,
)
