package app.skerry.shared.vault

/**
 * Compares secret bytes in time independent of the first differing position (OR-accumulate XOR
 * diffs, no early exit) so timing can't be used to brute-force a key byte by byte. A length
 * mismatch returns false immediately: key length is fixed by the vault format, not a secret.
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
