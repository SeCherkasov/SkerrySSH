package app.skerry.shared.securitykey

private val AUTH_DATA_COMMANDS = setOf(1, 2, 8)

/** Strict CTAP CBOR subset, checked without trusting encoded allocation sizes or recursion. */
internal fun validateAuthenticatorCbor(response: ByteArray, command: Int) {
    if (response.isEmpty() || response.size > 16384) invalidAuthenticator()
    if (response[0].toInt() != 0) {
        if (response.size != 1) invalidAuthenticator()
        return
    }
    if (response.size == 1) return
    val reader = SafeCbor(response, 1, response.size)
    val count = reader.mapSize()
    repeat(count) {
        val key = reader.smallIntegerOrSkip()
        val containsAuthData = command in AUTH_DATA_COMMANDS
        if (containsAuthData && key == 2) {
            val bytes = reader.bytesRange()
            validateAuthDataCbor(response, bytes.first, bytes.second)
        } else reader.item(1)
    }
    if (reader.position != response.size) invalidAuthenticator()
}

private fun validateAuthDataCbor(bytes: ByteArray, start: Int, end: Int) {
    if (end - start < 37) invalidAuthenticator()
    val flags = bytes[start + 32].toInt() and 255
    var position = start + 37
    if (flags and 0x40 != 0) {
        if (end - position < 18) invalidAuthenticator()
        val size = ((bytes[position + 16].toInt() and 255) shl 8) or (bytes[position + 17].toInt() and 255)
        if (size !in 1..4096 || end - position - 18 < size) invalidAuthenticator()
        position += 18 + size
        val cose = SafeCbor(bytes, position, end)
        cose.mapItem()
        position = cose.position
    }
    if (flags and 0x80 != 0) {
        val extensions = SafeCbor(bytes, position, end)
        extensions.mapItem()
        position = extensions.position
    }
    if (position != end) invalidAuthenticator()
}

private class SafeCbor(private val bytes: ByteArray, start: Int, private val end: Int) {
    var position: Int = start
        private set
    private var items = 0
    private fun read(): Int {
        if (position >= end) invalidAuthenticator()
        return bytes[position++].toInt() and 255
    }
    private fun argument(head: Int): Int {
        val additional = head and 31
        if (additional < 24) return additional
        val count = when (additional) { 24 -> 1; 25 -> 2; 26 -> 4; else -> invalidAuthenticator() }
        var value = 0L
        repeat(count) { value = (value shl 8) or read().toLong() }
        if (value > Int.MAX_VALUE) invalidAuthenticator()
        return value.toInt()
    }
    fun mapSize(): Int {
        val head = read()
        if (head shr 5 != 5) invalidAuthenticator()
        val size = argument(head)
        if (size > 1024 || size > (end - position) / 2) invalidAuthenticator()
        return size
    }
    fun smallIntegerOrSkip(): Int? {
        if (position >= end) invalidAuthenticator()
        return if (bytes[position].toInt() and 0xe0 == 0) argument(read()) else { item(1); null }
    }
    fun bytesRange(): Pair<Int, Int> {
        val head = read()
        if (head shr 5 != 2) invalidAuthenticator()
        val length = argument(head)
        if (length > end - position) invalidAuthenticator()
        val start = position
        position += length
        return start to position
    }
    fun mapItem() {
        val size = mapSize()
        repeat(size) { item(1); item(1) }
    }
    private fun collection(size: Int, depth: Int, map: Boolean) {
        val width = if (map) 2 else 1
        if (size > 1024 || size > (end - position) / width) invalidAuthenticator()
        repeat(size) {
            item(depth + 1)
            if (map) item(depth + 1)
        }
    }
    fun item(depth: Int) {
        if (depth > 16 || ++items > 2048) invalidAuthenticator()
        val head = read()
        val major = head shr 5
        if (major == 7) {
            if (head !in 0xf4..0xf6) invalidAuthenticator()
            return
        }
        val size = argument(head)
        when (major) {
            0, 1 -> Unit
            2, 3 -> {
                if (size > end - position) invalidAuthenticator()
                position += size
            }
            4 -> collection(size, depth, map = false)
            5 -> collection(size, depth, map = true)
            else -> invalidAuthenticator()
        }
    }
}
internal fun invalidAuthenticator(): Nothing = throw SecurityKeyException(SecurityKeyFailure.VerificationFailed)
