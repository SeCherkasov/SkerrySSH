package app.skerry.shared.securitykey

/** CTAP PIN UTF-8 length, checked without making an immutable secret String or encoded copy. */
internal fun isSecurityKeyPinValid(pin: CharArray): Boolean {
    if (pin.isEmpty() || pin.size > 63) return false
    var position = 0
    var bytes = 0
    while (position < pin.size) {
        val char = pin[position++]
        bytes += when {
            char == '\u0000' -> return false
            char.isHighSurrogate() -> {
                if (position >= pin.size || !pin[position++].isLowSurrogate()) return false
                4
            }
            char.isLowSurrogate() -> return false
            char.code < 128 -> 1
            char.code < 2048 -> 2
            else -> 3
        }
        if (bytes > 63) return false
    }
    return true
}
