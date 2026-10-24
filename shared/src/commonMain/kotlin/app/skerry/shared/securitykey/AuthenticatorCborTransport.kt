package app.skerry.shared.securitykey

/** Hold every CTAPHID_CBOR frame until the whole bounded message is safe for the SDK decoder. */
internal fun validatedHidCborFrames(first: ByteArray, command: Int, readNext: () -> ByteArray): List<ByteArray> {
    if (first.size != 64 || first[4] != 0x90.toByte()) invalidAuthenticator()
    val size = ((first[5].toInt() and 255) shl 8) or (first[6].toInt() and 255)
    if (size !in 1..7609) invalidAuthenticator()
    val payload = ByteArray(size)
    val frames = mutableListOf(first.copyOf())
    val initial = minOf(size, 57)
    first.copyInto(payload, 0, 7, 7 + initial)
    var position = initial
    var sequence = 0
    while (position < size) {
        val frame = readNext()
        if (frame.size != 64 || frame[4].toInt() != sequence++ || (0..3).any { frame[it] != first[it] }) invalidAuthenticator()
        val count = minOf(size - position, 59)
        frame.copyInto(payload, position, 5, 5 + count)
        position += count
        frames.add(frame.copyOf())
    }
    validateAuthenticatorCbor(payload, command)
    return frames
}

/** Finish ISO7816 response chaining before the SDK's recursive CBOR decoder sees any payload. */
internal fun validatedCtapApduResponse(first: ByteArray, command: Int, readNext: () -> ByteArray): ByteArray {
    val output = okio.Buffer()
    var response = first
    var chunks = 0
    while (true) {
        if (++chunks > 128 || response.size !in 2..16384 || output.size + response.size - 2 > 16384) invalidAuthenticator()
        output.write(response, 0, response.size - 2)
        val sw1 = response[response.size - 2].toInt() and 255
        val sw2 = response.last().toInt() and 255
        if (sw1 == 0x61) { response = readNext(); continue }
        val payload = output.readByteArray()
        if (sw1 == 0x90 && sw2 == 0) validateAuthenticatorCbor(payload, command)
        return payload + byteArrayOf(sw1.toByte(), sw2.toByte())
    }
}
