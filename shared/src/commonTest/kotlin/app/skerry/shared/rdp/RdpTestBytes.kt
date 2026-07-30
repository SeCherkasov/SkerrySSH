package app.skerry.shared.rdp

/** Parse a whitespace-separated hex dump (as printed in the MS-RDPBCGR annotated sequences). */
fun hex(dump: String): ByteArray {
    val digits = dump.filterNot { it.isWhitespace() }
    require(digits.length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Render bytes the way the dumps do, so a failed assertion is comparable to the spec by eye. */
fun ByteArray.toHex(): String = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * Read [byteCount] bytes of a fixed-size UTF-16LE field and drop the null padding — the form every
 * string in the RDP connection sequence takes.
 */
fun RdpReader.utf16le(byteCount: Int): String = buildString {
    repeat(byteCount / 2) {
        val code = u16le()
        if (code != 0) append(code.toChar())
    }
}

/** Read [byteCount] bytes of a fixed-size ASCII field (virtual channel names) without its padding. */
fun RdpReader.ascii(byteCount: Int): String =
    bytes(byteCount).takeWhile { it.toInt() != 0 }.toByteArray().decodeToString()

/**
 * TS_EXTENDED_INFO_PACKET::performanceFlags of a Client Info PDU, from a reader standing on the code
 * page field — that is, right after the security header. Every field before the flags is variable
 * length, so this walks them rather than counting a fixed offset.
 */
fun readPerformanceFlags(reader: RdpReader): Int {
    reader.skip(8) // codePage, flags
    val lengths = List(5) { reader.u16le() } // domain, username, password, shell, working directory
    lengths.forEach { reader.skip(it + 2) } // each cb* excludes the terminator the string carries
    reader.skip(2) // clientAddressFamily
    repeat(2) { reader.skip(reader.u16le()) } // clientAddress, clientDir, each behind its own length
    reader.skip(172) // TS_TIME_ZONE_INFORMATION
    reader.skip(4) // clientSessionId
    return reader.u32le()
}
