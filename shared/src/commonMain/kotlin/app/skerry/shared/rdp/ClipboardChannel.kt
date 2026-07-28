package app.skerry.shared.rdp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The clipboard virtual channel (MS-RDPECLIP). Text only, in both directions.
 *
 * The protocol is an offer/request pair rather than a transfer: whoever copies announces the
 * *formats* it holds, and the other side asks for the one it wants. So a copy on either machine
 * costs one small message, and the data itself only moves when someone pastes.
 *
 * Only `CF_UNICODETEXT` is offered and accepted. Files, bitmaps and rich text would each need their
 * own transfer machinery (file contents stream over a separate request/response protocol), and a
 * clipboard that silently pastes half a spreadsheet is worse than one that pastes its text.
 */
class ClipboardChannel(private val send: suspend (ByteArray) -> Unit) {

    /**
     * Two coroutines reach this state: the read loop answering the server, and the UI coroutine
     * that runs when the user copies something locally. A copy landing at the same moment as the
     * server's data request would otherwise be missed, and the paste would fail for no visible
     * reason — so the offer state is guarded, exactly like the pointer state in `RdpRemoteDesktop`.
     */
    private val lock = Mutex()

    private val incoming = ArrayDeque<String>()

    /** Text we hold locally and have offered to the server, kept until it asks for it. */
    private var offeredText: String? = null

    /** Whether the server has announced text of its own that we have not fetched yet. */
    private var serverHasText = false

    /** Handle one channel payload from the server. */
    suspend fun onData(data: ByteArray) {
        val reader = RdpReader(data)
        if (reader.remaining < HEADER_SIZE) return
        val messageType = reader.u16le()
        val flags = reader.u16le()
        val length = reader.u32le()
        if (length < 0 || length > reader.remaining) return
        val body = reader.slice(length)

        when (messageType) {
            CB_MONITOR_READY -> {
                // The server is ready: answer with our capabilities and an empty format list, which
                // is what tells it we speak the channel at all.
                send(capabilities())
                send(formatList(null))
            }

            CB_FORMAT_LIST -> {
                val hasText = announcesText(body, flags)
                lock.withLock { serverHasText = hasText }
                send(formatListResponse())
                if (hasText) send(formatDataRequest())
            }

            CB_FORMAT_DATA_REQUEST -> {
                val requested = if (body.remaining >= 4) body.u32le() else 0
                val text = lock.withLock { offeredText }
                send(
                    if (requested == CF_UNICODETEXT && text != null) {
                        formatDataResponse(text)
                    } else {
                        failureResponse()
                    },
                )
            }

            CB_FORMAT_DATA_RESPONSE -> {
                if (flags and CB_RESPONSE_FAIL != 0) return
                lock.withLock { serverHasText = false }
                decodeText(body)?.let { text -> lock.withLock { incoming.addLast(text) } }
            }
        }
    }

    /** Offer [text] to the server as the local clipboard's new contents. */
    suspend fun offerText(text: String) {
        lock.withLock { offeredText = text }
        send(formatList(text))
    }

    /** Text received from the server since the last call. */
    suspend fun drainIncoming(): List<String> = lock.withLock {
        if (incoming.isEmpty()) return@withLock emptyList()
        val out = incoming.toList()
        incoming.clear()
        out
    }

    private fun announcesText(body: RdpReader, flags: Int): Boolean {
        val asciiNames = flags and CB_ASCII_NAMES != 0
        while (body.remaining >= 4) {
            val formatId = body.u32le()
            // Long format names are null-terminated UTF-16 (or ASCII when the flag says so).
            if (asciiNames) {
                while (body.remaining > 0 && body.u8() != 0) Unit
            } else {
                while (body.remaining >= 2 && body.u16le() != 0) Unit
            }
            if (formatId == CF_UNICODETEXT || formatId == CF_TEXT || formatId == CF_OEMTEXT) return true
        }
        return false
    }

    /** UTF-16LE text, with the terminating null the format carries stripped. */
    private fun decodeText(body: RdpReader): String? {
        if (body.remaining < 2) return null
        val text = StringBuilder()
        while (body.remaining >= 2) {
            val code = body.u16le()
            if (code == 0) break
            text.append(code.toChar())
        }
        // Windows line endings arrive as CRLF; the rest of the app works in LF.
        return text.toString().replace("\r\n", "\n").takeIf { it.isNotEmpty() }
    }

    private fun message(type: Int, flags: Int, body: ByteArray): ByteArray =
        RdpWriter(body.size + HEADER_SIZE)
            .u16le(type)
            .u16le(flags)
            .u32le(body.size)
            .bytes(body)
            .toByteArray()

    private fun capabilities(): ByteArray {
        val body = RdpWriter(16)
        body.u16le(1) // cCapabilitiesSets
        body.u16le(0) // pad
        body.u16le(CB_CAPSTYPE_GENERAL)
        body.u16le(12) // lengthCapability
        body.u32le(CB_CAPS_VERSION_2)
        // Long format names only; file transfer and stream-based clipboard are deliberately absent.
        body.u32le(CB_USE_LONG_FORMAT_NAMES)
        return message(CB_CLIP_CAPS, 0, body.toByteArray())
    }

    /** Announce what we hold; [text] null means "the local clipboard has nothing for you". */
    private fun formatList(text: String?): ByteArray {
        val body = RdpWriter(16)
        if (text != null) {
            body.u32le(CF_UNICODETEXT)
            body.u16le(0) // an empty long format name
        }
        return message(CB_FORMAT_LIST, 0, body.toByteArray())
    }

    private fun formatListResponse(): ByteArray = message(CB_FORMAT_LIST_RESPONSE, CB_RESPONSE_OK, ByteArray(0))

    private fun formatDataRequest(): ByteArray =
        message(CB_FORMAT_DATA_REQUEST, 0, RdpWriter(4).u32le(CF_UNICODETEXT).toByteArray())

    private fun formatDataResponse(text: String): ByteArray {
        val payload = RdpWriter(text.length * 2 + 2)
        // Back to CRLF on the way out: a Windows text box shows lone line feeds as one long line.
        payload.utf16le(text.replace("\n", "\r\n"), nullTerminated = true)
        return message(CB_FORMAT_DATA_RESPONSE, CB_RESPONSE_OK, payload.toByteArray())
    }

    private fun failureResponse(): ByteArray = message(CB_FORMAT_DATA_RESPONSE, CB_RESPONSE_FAIL, ByteArray(0))

    private companion object {
        const val HEADER_SIZE = 8

        const val CB_MONITOR_READY = 0x0001
        const val CB_FORMAT_LIST = 0x0002
        const val CB_FORMAT_LIST_RESPONSE = 0x0003
        const val CB_FORMAT_DATA_REQUEST = 0x0004
        const val CB_FORMAT_DATA_RESPONSE = 0x0005
        const val CB_CLIP_CAPS = 0x0007

        const val CB_RESPONSE_OK = 0x0001
        const val CB_RESPONSE_FAIL = 0x0002
        const val CB_ASCII_NAMES = 0x0004

        const val CB_CAPSTYPE_GENERAL = 0x0001
        const val CB_CAPS_VERSION_2 = 0x00000002
        const val CB_USE_LONG_FORMAT_NAMES = 0x00000002

        const val CF_TEXT = 1
        const val CF_OEMTEXT = 7
        const val CF_UNICODETEXT = 13
    }
}
