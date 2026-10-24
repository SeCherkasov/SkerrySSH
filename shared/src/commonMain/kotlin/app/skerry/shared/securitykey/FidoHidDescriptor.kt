package app.skerry.shared.securitykey

/** HID Usage Tables: FIDO Alliance page 0xf1d0, U2F Authenticator application collection 1. */
internal fun isFidoHidDescriptor(bytes: ByteArray): Boolean {
    if (bytes.isEmpty() || bytes.size > 4096) return false
    var position = 0
    var usagePage = 0
    var usage = 0
    var found = false
    while (position < bytes.size) {
        val tag = bytes[position++].toInt() and 255
        if (tag == 0xfe) return false // No long items are needed by CTAPHID.
        val size = when (tag and 3) { 3 -> 4; else -> tag and 3 }
        if (size > bytes.size - position) return false
        var value = 0
        repeat(size) { value = value or ((bytes[position++].toInt() and 255) shl (it * 8)) }
        when (tag and 0xfc) {
            0x04 -> usagePage = value
            0x08 -> usage = value
            0x84 -> return false // Report IDs change the mandatory 64-byte CTAPHID framing.
            0xa0 -> if (isFidoApplication(value, usagePage, usage)) found = true
        }
    }
    return found
}

private fun isFidoApplication(collection: Int, page: Int, usage: Int): Boolean =
    collection == 1 && page == 0xf1d0 && usage == 1
