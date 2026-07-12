package app.skerry.shared.mosh

import kotlin.io.encoding.Base64

/**
 * 128-bit AES-OCB session key printed by `mosh-server` as 22 unpadded base64 characters
 * (mosh's `Base64Key`). Parsing is strict: the textual form must round-trip, so a
 * non-canonical final character is rejected rather than silently truncated.
 */
class MoshKey private constructor(val bytes: ByteArray, val encoded: String) {

    override fun toString(): String = "MoshKey(redacted)"

    companion object {
        private val ALPHABET = Regex("^[A-Za-z0-9+/]{22}$")

        fun parse(text: String): MoshKey? {
            if (!ALPHABET.matches(text)) return null
            val decoded = try {
                Base64.Default.decode("$text==")
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (decoded.size != 16) return null
            val canonical = Base64.Default.encode(decoded).removeSuffix("==")
            if (canonical != text) return null
            return MoshKey(decoded, text)
        }
    }
}
