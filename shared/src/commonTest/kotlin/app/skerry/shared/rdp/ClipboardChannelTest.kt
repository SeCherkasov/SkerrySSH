package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The clipboard channel (MS-RDPECLIP): offer, request, transfer — in both directions. */
class ClipboardChannelTest {

    private val sent = mutableListOf<ByteArray>()
    private val channel = ClipboardChannel { data -> sent.add(data) }

    @Test
    fun `monitor ready is answered with capabilities and a format list`() = runTest {
        channel.onData(message(CB_MONITOR_READY))

        assertEquals(listOf(CB_CLIP_CAPS, CB_FORMAT_LIST), sent.map { typeOf(it) })
    }

    @Test
    fun `a server offering text is asked for it, and the reply reaches the caller`() = runTest {
        channel.onData(message(CB_FORMAT_LIST, formatListBody(CF_UNICODETEXT)))

        assertEquals(listOf(CB_FORMAT_LIST_RESPONSE, CB_FORMAT_DATA_REQUEST), sent.map { typeOf(it) })

        channel.onData(message(CB_FORMAT_DATA_RESPONSE, utf16("hello\r\nworld"), flags = CB_RESPONSE_OK))

        // CRLF becomes LF: the rest of the app works in line feeds.
        assertEquals(listOf("hello\nworld"), channel.drainIncoming())
        assertEquals(emptyList(), channel.drainIncoming())
    }

    @Test
    fun `a server offering only formats we do not take is acknowledged but not fetched`() = runTest {
        channel.onData(message(CB_FORMAT_LIST, formatListBody(CF_BITMAP)))

        assertEquals(listOf(CB_FORMAT_LIST_RESPONSE), sent.map { typeOf(it) })
    }

    @Test
    fun `a failed data response is dropped rather than pasted as empty text`() = runTest {
        channel.onData(message(CB_FORMAT_LIST, formatListBody(CF_UNICODETEXT)))
        sent.clear()

        channel.onData(message(CB_FORMAT_DATA_RESPONSE, ByteArray(0), flags = CB_RESPONSE_FAIL))

        assertEquals(emptyList(), channel.drainIncoming())
    }

    @Test
    fun `local text is offered, then handed over when the server asks`() = runTest {
        channel.offerText("copied\nline")
        sent.clear()

        channel.onData(message(CB_FORMAT_DATA_REQUEST, RdpWriter(4).u32le(CF_UNICODETEXT).toByteArray()))

        val response = sent.single()
        assertEquals(CB_FORMAT_DATA_RESPONSE, typeOf(response))
        assertEquals(CB_RESPONSE_OK, flagsOf(response))
        // Line feeds go back out as CRLF, or a Windows text box shows one long line.
        assertTrue(textOf(response).contains("copied\r\nline"))
    }

    @Test
    fun `a request for a format we never offered fails instead of sending something else`() = runTest {
        channel.offerText("text")
        sent.clear()

        channel.onData(message(CB_FORMAT_DATA_REQUEST, RdpWriter(4).u32le(CF_BITMAP).toByteArray()))

        assertEquals(CB_RESPONSE_FAIL, flagsOf(sent.single()))
    }

    @Test
    fun `a message claiming more data than it carries is ignored`() = runTest {
        val lying = RdpWriter(16).u16le(CB_FORMAT_LIST).u16le(0).u32le(1000).u32le(CF_UNICODETEXT).toByteArray()

        channel.onData(lying)

        assertEquals(emptyList(), sent.map { typeOf(it) })
    }

    private fun message(type: Int, body: ByteArray = ByteArray(0), flags: Int = 0): ByteArray =
        RdpWriter(body.size + 8).u16le(type).u16le(flags).u32le(body.size).bytes(body).toByteArray()

    private fun formatListBody(formatId: Int): ByteArray =
        RdpWriter(8).u32le(formatId).u16le(0).toByteArray()

    private fun utf16(text: String): ByteArray = RdpWriter(text.length * 2 + 2).utf16le(text, true).toByteArray()

    private fun typeOf(message: ByteArray): Int = RdpReader(message).u16le()

    private fun flagsOf(message: ByteArray): Int = RdpReader(message).run { u16le(); u16le() }

    private fun textOf(message: ByteArray): String {
        val reader = RdpReader(message)
        reader.skip(8)
        return buildString {
            while (reader.remaining >= 2) {
                val code = reader.u16le()
                if (code == 0) break
                append(code.toChar())
            }
        }
    }

    private companion object {
        const val CB_MONITOR_READY = 0x0001
        const val CB_FORMAT_LIST = 0x0002
        const val CB_FORMAT_LIST_RESPONSE = 0x0003
        const val CB_FORMAT_DATA_REQUEST = 0x0004
        const val CB_FORMAT_DATA_RESPONSE = 0x0005
        const val CB_CLIP_CAPS = 0x0007
        const val CB_RESPONSE_OK = 0x0001
        const val CB_RESPONSE_FAIL = 0x0002
        const val CF_BITMAP = 2
        const val CF_UNICODETEXT = 13
    }
}
