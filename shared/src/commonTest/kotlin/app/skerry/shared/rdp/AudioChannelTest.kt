package app.skerry.shared.rdp

import app.skerry.shared.audio.RemoteAudioFormat
import app.skerry.shared.audio.RemoteAudioPlayer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** The audio output channel (MS-RDPEA, `rdpsnd`): format negotiation, wave transfer, confirmations. */
class AudioChannelTest {

    private val sent = mutableListOf<ByteArray>()
    private val player = RecordingPlayer()
    private val channel = AudioChannel(player)

    /** The static `rdpsnd` transport: what a host without dynamic virtual channels uses. */
    private suspend fun AudioChannel.receive(data: ByteArray) = onData(data) { sent.add(it) }

    @Test
    fun `the server's formats are answered with the ones we can actually play`() = runTest {
        channel.receive(serverFormats(version = 6, formats = listOf(PCM_44_STEREO, GSM_610, PCM_22_MONO_8BIT)))

        val answer = sent.first()
        assertEquals(SNDC_FORMATS, typeOf(answer))
        // The compressed format is dropped: nothing here decodes GSM, and offering it would have the
        // server send audio this client can only play as noise.
        assertEquals(listOf(PCM_44_STEREO, PCM_22_MONO_8BIT), formatsOf(answer))
        // A version 6 server expects the quality mode right after the formats.
        assertEquals(listOf(SNDC_FORMATS, SNDC_QUALITYMODE), sent.map { typeOf(it) })
    }

    @Test
    fun `a server too old for the quality mode is not sent one`() = runTest {
        channel.receive(serverFormats(version = 2, formats = listOf(PCM_44_STEREO)))

        assertEquals(listOf(SNDC_FORMATS), sent.map { typeOf(it) })
    }

    @Test
    fun `a server offering nothing playable still hears a format, or it would send silence`() = runTest {
        channel.receive(serverFormats(version = 6, formats = listOf(GSM_610)))

        assertEquals(listOf(RemoteAudioFormat(44100, 2, 16)), formatsOf(sent.first()))
    }

    @Test
    fun `a training request is echoed back, which is what proves the channel works`() = runTest {
        channel.receive(pdu(SNDC_TRAINING, RdpWriter(8).u16le(0x1234).u16le(1024).zeros(4).toByteArray()))

        val confirm = sent.single()
        assertEquals(SNDC_TRAINING, typeOf(confirm))
        val body = bodyOf(confirm)
        assertEquals(0x1234, body.u16le())
        assertEquals(1024, body.u16le())
    }

    @Test
    fun `a wave split over two PDUs is played as one block and confirmed`() = runTest {
        negotiate()
        val playback = launch { channel.play() }
        val samples = ByteArray(20) { (it + 1).toByte() }

        channel.receive(waveInfo(timestamp = 0x0A0B, formatNo = 0, blockNo = 7, samples = samples))
        // The four bytes the info PDU carried are repeated as padding here (MS-RDPEA 2.2.3.3.2).
        channel.receive(ByteArray(4) + samples.copyOfRange(4, samples.size))
        advanceUntilIdle()

        playback.cancel()
        assertEquals(1, player.played.size)
        assertEquals(PCM_44_STEREO, player.played.single().first)
        assertContentEquals(samples, player.played.single().second)
        val confirm = sent.single()
        assertEquals(SNDC_WAVECONFIRM, typeOf(confirm))
        val body = bodyOf(confirm)
        assertEquals(0x0A0B, body.u16le())
        assertEquals(7, body.u8())
    }

    @Test
    fun `a wave2 PDU carries its samples whole`() = runTest {
        negotiate()
        val playback = launch { channel.play() }
        val samples = ByteArray(16) { it.toByte() }

        channel.receive(wave2(timestamp = 0x11, formatNo = 1, blockNo = 3, samples = samples))
        advanceUntilIdle()

        playback.cancel()
        assertEquals(PCM_22_MONO_8BIT, player.played.single().first)
        assertContentEquals(samples, player.played.single().second)
        val confirm = bodyOf(sent.single())
        assertEquals(0x11, confirm.u16le())
        assertEquals(3, confirm.u8())
    }

    @Test
    fun `sound in a format that was never agreed is dropped, but still confirmed`() = runTest {
        negotiate()
        val playback = launch { channel.play() }

        channel.receive(wave2(timestamp = 1, formatNo = 9, blockNo = 4, samples = ByteArray(8)))
        advanceUntilIdle()
        playback.cancel()

        assertTrue(player.played.isEmpty())
        // Without the confirmation the server stops sending after a handful of blocks, and the
        // session goes quiet for good.
        assertEquals(SNDC_WAVECONFIRM, typeOf(sent.single()))
    }

    @Test
    fun `closing the stream drops what has not been played`() = runTest {
        negotiate()

        channel.receive(pdu(SNDC_CLOSE, ByteArray(0)))

        assertEquals(1, player.flushes)
    }

    @Test
    fun `a truncated PDU is ignored rather than ending the session`() = runTest {
        channel.receive(byteArrayOf(SNDC_FORMATS.toByte(), 0))
        channel.receive(pdu(SNDC_TRAINING, byteArrayOf(1)))
        channel.receive(pdu(SNDC_WAVE2, byteArrayOf(1, 2, 3)))

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a format count larger than the PDU carries takes only the formats that are there`() = runTest {
        val body = RdpWriter(32)
            .u32le(0).u32le(0).u32le(0).u16le(0)
            .u16le(9) // wNumberOfFormats, a lie
            .u8(0).u16le(6).u8(0)
        writeFormat(body, PCM_44_STEREO)

        channel.receive(pdu(SNDC_FORMATS, body.toByteArray()))

        assertEquals(listOf(PCM_44_STEREO), formatsOf(sent.first()))
    }

    @Test
    fun `the dynamic transport answers on its own channel, and shares the negotiated formats`() = runTest {
        // What a Windows host with drdynvc open actually does: it speaks MS-RDPEA over
        // AUDIO_PLAYBACK_DVC and sends nothing at all on the static channel.
        val dvc = mutableListOf<ByteArray>()
        val handler = channel.dynamicHandler { dvc.add(it) }
        val playback = launch { channel.play() }

        handler.onMessage(serverFormats(version = 6, formats = listOf(PCM_44_STEREO)))
        handler.onMessage(wave2(timestamp = 5, formatNo = 0, blockNo = 2, samples = ByteArray(8) { 1 }))
        advanceUntilIdle()
        playback.cancel()

        // The replies go back the way the request came, not onto the static channel.
        assertTrue(sent.isEmpty())
        assertEquals(listOf(SNDC_FORMATS, SNDC_QUALITYMODE, SNDC_WAVECONFIRM), dvc.map { typeOf(it) })
        assertEquals(PCM_44_STEREO, player.played.single().first)
    }

    @Test
    fun `muted sound never reaches the device, and what was queued is dropped`() = runTest {
        negotiate()
        val playback = launch { channel.play() }

        channel.setMuted(true)
        channel.receive(wave2(timestamp = 3, formatNo = 0, blockNo = 1, samples = ByteArray(8) { 1 }))
        advanceUntilIdle()
        playback.cancel()

        assertTrue(player.played.isEmpty())
        assertEquals(1, player.flushes)
        // The server allows only a few unconfirmed blocks: a muted client that stopped confirming
        // would be a client the server stops sending to, and unmuting would then stay silent.
        assertEquals(SNDC_WAVECONFIRM, typeOf(sent.single()))
    }

    @Test
    fun `unmuting lets the next block through`() = runTest {
        negotiate()
        val playback = launch { channel.play() }

        channel.setMuted(true)
        channel.receive(wave2(timestamp = 3, formatNo = 0, blockNo = 1, samples = ByteArray(8) { 1 }))
        channel.setMuted(false)
        channel.receive(wave2(timestamp = 4, formatNo = 0, blockNo = 2, samples = ByteArray(8) { 2 }))
        advanceUntilIdle()
        playback.cancel()

        assertContentEquals(ByteArray(8) { 2 }, player.played.single().second)
    }

    @Test
    fun `muting drops what was already queued, or the mute would play out first`() = runTest {
        negotiate()

        // Queued while unmuted and not yet handed to the device: playback starts after the mute.
        channel.receive(wave2(timestamp = 1, formatNo = 0, blockNo = 1, samples = ByteArray(8) { 1 }))
        channel.setMuted(true)
        val playback = launch { channel.play() }
        advanceUntilIdle()
        playback.cancel()

        assertTrue(player.played.isEmpty())
        assertEquals(1, player.flushes)
    }

    /** Settle on the two formats the wave tests index into. */
    private suspend fun negotiate() {
        channel.receive(serverFormats(version = 6, formats = listOf(PCM_44_STEREO, PCM_22_MONO_8BIT)))
        sent.clear()
    }

    private fun serverFormats(version: Int, formats: List<RemoteAudioFormat>): ByteArray {
        val body = RdpWriter(64)
        body.u32le(1) // dwFlags: TSSNDCAPS_ALIVE
        body.u32le(0) // dwVolume
        body.u32le(0) // dwPitch
        body.u16le(0) // wDGramPort
        body.u16le(formats.size)
        body.u8(0) // cLastBlockConfirmed
        body.u16le(version)
        body.u8(0) // bPad
        // Everything but the deliberately unplayable entry is announced as plain PCM.
        for (format in formats) writeFormat(body, format, tag = if (format == GSM_610) WAVE_FORMAT_GSM610 else 1)
        return pdu(SNDC_FORMATS, body.toByteArray())
    }

    private fun waveInfo(timestamp: Int, formatNo: Int, blockNo: Int, samples: ByteArray): ByteArray {
        val body = RdpWriter(16)
            .u16le(timestamp)
            .u16le(formatNo)
            .u8(blockNo)
            .zeros(3)
            .bytes(samples, 0, 4)
        // BodySize covers the whole wave, not just this PDU: the header is 8 bytes past the samples.
        return RdpWriter(16).u8(SNDC_WAVE).u8(0).u16le(samples.size + 8).bytes(body.toByteArray()).toByteArray()
    }

    private fun wave2(timestamp: Int, formatNo: Int, blockNo: Int, samples: ByteArray): ByteArray =
        pdu(
            SNDC_WAVE2,
            RdpWriter(16 + samples.size)
                .u16le(timestamp).u16le(formatNo).u8(blockNo).zeros(3).u32le(0)
                .bytes(samples).toByteArray(),
        )

    private fun pdu(type: Int, body: ByteArray): ByteArray =
        RdpWriter(body.size + 4).u8(type).u8(0).u16le(body.size).bytes(body).toByteArray()

    private fun writeFormat(writer: RdpWriter, format: RemoteAudioFormat, tag: Int = 1) {
        val frame = format.channels * format.bitsPerSample / 8
        writer.u16le(tag)
            .u16le(format.channels)
            .u32le(format.sampleRate)
            .u32le(format.sampleRate * frame)
            .u16le(frame)
            .u16le(format.bitsPerSample)
            .u16le(0) // cbSize
    }

    private fun typeOf(pdu: ByteArray): Int = pdu[0].toInt() and 0xFF

    private fun bodyOf(pdu: ByteArray): RdpReader = RdpReader(pdu, 4)

    /** The formats a client-side Formats PDU offers, in order. */
    private fun formatsOf(pdu: ByteArray): List<RemoteAudioFormat> {
        val reader = bodyOf(pdu)
        reader.skip(12) // dwFlags, dwVolume, dwPitch
        reader.u16le() // wDGramPort
        val count = reader.u16le()
        reader.u8() // cLastBlockConfirmed
        reader.u16le() // wVersion
        reader.u8() // bPad
        return List(count) {
            reader.u16le() // wFormatTag
            val channels = reader.u16le()
            val rate = reader.u32le()
            reader.u32le() // nAvgBytesPerSec
            reader.u16le() // nBlockAlign
            val bits = reader.u16le()
            reader.skip(2) // cbSize
            RemoteAudioFormat(rate, channels, bits)
        }
    }

    private class RecordingPlayer : RemoteAudioPlayer {
        val played = mutableListOf<Pair<RemoteAudioFormat, ByteArray>>()
        var flushes = 0

        override fun play(format: RemoteAudioFormat, pcm: ByteArray) {
            played.add(format to pcm)
        }

        override fun flush() {
            flushes++
        }

        override fun close() = Unit
    }

    private companion object {
        const val SNDC_CLOSE = 0x01
        const val SNDC_WAVE = 0x02
        const val SNDC_WAVECONFIRM = 0x05
        const val SNDC_TRAINING = 0x06
        const val SNDC_FORMATS = 0x07
        const val SNDC_QUALITYMODE = 0x0C
        const val SNDC_WAVE2 = 0x0D

        val PCM_44_STEREO = RemoteAudioFormat(44100, 2, 16)
        val PCM_22_MONO_8BIT = RemoteAudioFormat(22050, 1, 8)
        val GSM_610 = RemoteAudioFormat(8000, 1, 0)
        const val WAVE_FORMAT_GSM610 = 0x0031
    }
}
