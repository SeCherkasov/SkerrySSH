package app.skerry.ui.securitykey

/** CTAP PINs are UTF-8 with a 63-byte maximum; reject malformed surrogate sequences. */
internal fun securityKeyPinValid(pin: String): Boolean {
    if (pin.isEmpty() || pin.length > 63 || pin.any { it == '\u0000' }) return false
    var index = 0
    while (index < pin.length) {
        val char = pin[index++]
        if (char.isHighSurrogate()) {
            if (index == pin.length || !pin[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return pin.encodeToByteArray().let { bytes ->
        try { bytes.size <= 63 } finally { bytes.fill(0) }
    }
}
