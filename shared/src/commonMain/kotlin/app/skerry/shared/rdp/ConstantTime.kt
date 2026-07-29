package app.skerry.shared.rdp

/**
 * Compare two byte strings without leaking where they first differ. Every MAC and signature the RDP
 * stack checks goes through this: `contentEquals` bails on the first mismatch, and that timing is
 * what lets an attacker walk a forgery byte by byte.
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
