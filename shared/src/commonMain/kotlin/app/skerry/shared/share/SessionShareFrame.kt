package app.skerry.shared.share

/**
 * Who sent a frame. The relay is untrusted and echoes whatever it is given, so the direction is
 * authenticated as part of the AAD ([shareAad]): a guest cannot take a host's output frame and have
 * it accepted as keystrokes on the host's shell, and vice versa.
 */
enum class ShareDirection(internal val tag: String) {
    /** Terminal output, screen size — from the host of the shared session to its viewers. */
    HOST_TO_GUEST("h"),

    /** Keystrokes from a viewer, applied only while the host allows input. */
    GUEST_TO_HOST("g"),
}

/** One message of the session-sharing protocol; travels sealed (see [SessionShareCodec]). */
sealed interface ShareFrame {
    /** Raw PTY bytes the host's shell produced. */
    class Output(val bytes: ByteArray) : ShareFrame

    /**
     * Raw keystrokes from a viewer. [sender] identifies the viewer's socket and [seq] counts its
     * frames: the relay sees every ciphertext it forwards, and without a freshness marker it could
     * hand the host a captured keystroke frame again — it would re-authenticate and be typed into
     * the shell a second time (see [SessionShareHost]). Several viewers type into one host, so the
     * counter is per sender, not per share.
     */
    class Input(val bytes: ByteArray, val sender: Long = 0, val seq: Long = 0) : ShareFrame

    /** The host's terminal geometry; sent on start, on resize, and whenever a viewer joins. */
    data class Resize(val cols: Int, val rows: Int) : ShareFrame

    /** The host ended the sharing session (the shell is gone or sharing was stopped). */
    data object End : ShareFrame

    /**
     * A viewer naming itself to the host, once, right after joining. The relay knows who is on each
     * socket, but the host must not have to take its word for it: this travels sealed under the team
     * key like everything else, so the name beside "someone is typing" is one a team member wrote.
     */
    class Hello(val sender: Long, val accountId: String) : ShareFrame

    /** A viewer asking the host to let it type ("request remote control"). */
    class ControlRequest(val sender: Long) : ShareFrame

    /** The host's answer to every viewer: whether typing is currently allowed. */
    data class ControlState(val granted: Boolean) : ShareFrame
}

/**
 * Largest plaintext payload of one frame. Sealing adds a 24-byte nonce, a 16-byte tag and the type
 * byte, and the relay caps a WebSocket frame at [SHARE_MAX_FRAME_BYTES] — output is chunked to fit
 * ([chunkShareOutput]).
 */
const val SHARE_MAX_CHUNK_BYTES: Int = 3072

/** Relay frame cap (the server's `maxFrameSize`); a larger frame closes the socket. */
const val SHARE_MAX_FRAME_BYTES: Int = 4096

/** Sealed-frame floor: 24-byte nonce + 16-byte tag + at least the type byte. */
private const val MIN_SEALED_BYTES = 24 + 16 + 1

/** Highest terminal dimension a frame may carry; anything else is a malformed peer. */
private const val MAX_DIMENSION = 9999

/** Sender id + sequence number in front of a keystroke payload (see [ShareFrame.Input]). */
private const val INPUT_HEADER_BYTES = 16

private fun longBytes(value: Long) = ByteArray(8) { i -> (value ushr (56 - i * 8)).toByte() }

private fun readLong(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    return value
}

private const val TYPE_OUTPUT: Byte = 1
private const val TYPE_INPUT: Byte = 2
private const val TYPE_RESIZE: Byte = 3
private const val TYPE_END: Byte = 4
private const val TYPE_HELLO: Byte = 5
private const val TYPE_CONTROL_REQUEST: Byte = 6
private const val TYPE_CONTROL_STATE: Byte = 7

/** Account ids are capped like everywhere else; a longer one is a peer making things up. */
private const val MAX_ACCOUNT_CHARS = 320

/**
 * AAD binding a sealed frame to one share and one direction. Includes a protocol version so a later
 * format can't be confused with this one, and the share id so a frame captured in one session can't
 * be replayed into another under the same team key.
 */
fun shareAad(shareId: String, direction: ShareDirection): ByteArray =
    "skerry-share:v1:$shareId:${direction.tag}".encodeToByteArray()

/**
 * AAD for a share's **label** — a different domain from the frames ([shareAad]), even though both
 * travel under the same team key. Sharing one AAD would let the relay hand back a captured output
 * frame as the session's label: it would decrypt cleanly and put real terminal output in front of
 * every member of the team, including ones who never joined.
 */
fun shareMetaAad(shareId: String): ByteArray = "skerry-share:v1:$shareId:meta".encodeToByteArray()

/** Frame -> plaintext. */
internal fun encodeShareFrame(frame: ShareFrame): ByteArray = when (frame) {
    is ShareFrame.Output -> byteArrayOf(TYPE_OUTPUT) + frame.bytes
    is ShareFrame.Input -> byteArrayOf(TYPE_INPUT) + longBytes(frame.sender) + longBytes(frame.seq) + frame.bytes
    is ShareFrame.Resize -> {
        val cols = frame.cols.coerceIn(1, MAX_DIMENSION)
        val rows = frame.rows.coerceIn(1, MAX_DIMENSION)
        byteArrayOf(
            TYPE_RESIZE,
            (cols shr 8).toByte(), cols.toByte(),
            (rows shr 8).toByte(), rows.toByte(),
        )
    }
    ShareFrame.End -> byteArrayOf(TYPE_END)
    is ShareFrame.Hello ->
        byteArrayOf(TYPE_HELLO) + longBytes(frame.sender) + frame.accountId.take(MAX_ACCOUNT_CHARS).encodeToByteArray()
    is ShareFrame.ControlRequest -> byteArrayOf(TYPE_CONTROL_REQUEST) + longBytes(frame.sender)
    is ShareFrame.ControlState -> byteArrayOf(TYPE_CONTROL_STATE, if (frame.granted) 1 else 0)
}

/**
 * Plaintext -> frame, or `null` for anything this version doesn't understand: an empty payload, a
 * type introduced by a newer peer, a truncated body. The bytes decrypted under the right key, so
 * this is a version/format mismatch rather than an attack, and dropping the frame is the whole
 * handling — the stream continues with the next one.
 */
internal fun decodeShareFrame(plaintext: ByteArray): ShareFrame? {
    if (plaintext.isEmpty()) return null
    val body = plaintext.copyOfRange(1, plaintext.size)
    return when (plaintext[0]) {
        TYPE_OUTPUT -> ShareFrame.Output(body)
        TYPE_INPUT -> {
            if (body.size < INPUT_HEADER_BYTES) return null
            ShareFrame.Input(
                bytes = body.copyOfRange(INPUT_HEADER_BYTES, body.size),
                sender = readLong(body, 0),
                seq = readLong(body, 8),
            )
        }
        TYPE_RESIZE -> {
            if (body.size != 4) return null
            val cols = (body[0].toInt() and 0xFF shl 8) or (body[1].toInt() and 0xFF)
            val rows = (body[2].toInt() and 0xFF shl 8) or (body[3].toInt() and 0xFF)
            if (cols !in 1..MAX_DIMENSION || rows !in 1..MAX_DIMENSION) null else ShareFrame.Resize(cols, rows)
        }
        TYPE_END -> ShareFrame.End
        TYPE_HELLO -> {
            if (body.size <= 8) return null
            val name = body.copyOfRange(8, body.size).decodeToString()
            if (name.isBlank() || name.length > MAX_ACCOUNT_CHARS) null
            else ShareFrame.Hello(readLong(body, 0), name)
        }
        TYPE_CONTROL_REQUEST -> if (body.size != 8) null else ShareFrame.ControlRequest(readLong(body, 0))
        TYPE_CONTROL_STATE -> if (body.size != 1) null else ShareFrame.ControlState(body[0] == 1.toByte())
        else -> null
    }
}

/**
 * Splits a PTY write into frame-sized pieces. Chunks are cut on byte boundaries, not character
 * ones: the viewer feeds them into a terminal emulator, which reassembles a split UTF-8 sequence
 * itself, exactly as it does for the PTY's own arbitrary chunking.
 */
fun chunkShareOutput(bytes: ByteArray, max: Int = SHARE_MAX_CHUNK_BYTES): List<ByteArray> {
    if (bytes.isEmpty()) return emptyList()
    if (bytes.size <= max) return listOf(bytes)
    val chunks = ArrayList<ByteArray>((bytes.size + max - 1) / max)
    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(offset + max, bytes.size)
        chunks += bytes.copyOfRange(offset, end)
        offset = end
    }
    return chunks
}

/** Guards the sealed-frame floor before handing untrusted bytes to the AEAD (which throws on those). */
internal fun isPlausibleSealedFrame(blob: ByteArray): Boolean =
    blob.size in MIN_SEALED_BYTES..SHARE_MAX_FRAME_BYTES
