package app.skerry.shared.rdp

import app.skerry.shared.audio.RemoteAudioFormat
import app.skerry.shared.audio.RemoteAudioPlayer
import app.skerry.shared.rdp.egfx.DynamicChannelHandler
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * The audio output channel (MS-RDPEA): the session's sound, server to client.
 *
 * The protocol rides two transports, and which one a server picks is its choice, not ours. A host
 * without dynamic virtual channels speaks it on the static `rdpsnd` channel; a Windows host that has
 * `drdynvc` open opens [DVC_NAME] instead and never sends a byte on the static one. Both land in the
 * same channel object — the negotiated formats and the playback queue belong to the session, not to
 * the pipe it arrived on — so the reply path travels with each payload rather than sitting in the
 * constructor.
 *
 * Only uncompressed PCM is negotiated. The other formats a Windows server offers (GSM 6.10, ADPCM,
 * and on newer hosts AAC) each need a decoder of their own, and a format announced without one would
 * arrive as noise rather than as nothing — announcing only what can be played is what keeps the
 * server sending audio this client can actually turn into sound.
 *
 * Decoding runs on the session's read loop, playback does not: [onData] parks a block in a bounded
 * queue and returns, while [play] — collected on a thread of its own — hands blocks to the device at
 * the rate it drains them. Without that split a full device buffer would stall the read loop, and
 * the picture would freeze whenever the sound ran ahead. A queue that overflows drops its oldest
 * block: audio that is seconds late is worth less than audio that is current.
 */
class AudioChannel(
    private val player: RemoteAudioPlayer,
    queuedBlocks: Int = QUEUED_BLOCKS,
    /**
     * Where a line about each step of the protocol goes. Silent by default; the session turns it on
     * from an environment variable. Sound that never arrives looks exactly like sound that arrived
     * in a format the device refused, and only the server's own messages tell the two apart.
     */
    private val trace: (String) -> Unit = {},
) {
    private val queue = Channel<Block>(capacity = queuedBlocks, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The formats this client announced; the server names one by its index in that list. */
    private var formats: List<RemoteAudioFormat> = emptyList()

    /** A WaveInfo PDU waiting for the samples that follow it in a PDU of their own. */
    private var pendingWave: PendingWave? = null

    private class PendingWave(
        val format: RemoteAudioFormat?,
        val timestamp: Int,
        val blockNo: Int,
        val head: ByteArray,
        val totalBytes: Int,
    )

    private class Block(val format: RemoteAudioFormat, val pcm: ByteArray)

    /** Handle one payload, answering through [send] — the transport it arrived on. */
    suspend fun onData(data: ByteArray, send: suspend (ByteArray) -> Unit) {
        // The samples of a WaveInfo PDU arrive as a bare payload with no header of their own, so a
        // pending wave decides what the next payload is before anything is parsed (2.2.3.3.2).
        pendingWave?.let { wave ->
            pendingWave = null
            completeWave(wave, data, send)
            return
        }
        val reader = RdpReader(data)
        if (reader.remaining < HEADER_SIZE) return
        val messageType = reader.u8()
        reader.u8() // bPad
        val bodySize = reader.u16le()
        val body = reader.slice(minOf(bodySize, reader.remaining))
        trace("pdu 0x${messageType.toString(16)} body=$bodySize")
        when (messageType) {
            SNDC_FORMATS -> serverFormats(body, send)
            SNDC_TRAINING -> training(body, send)
            SNDC_WAVE -> waveInfo(body, bodySize)
            SNDC_WAVE2 -> wave2(body, send)
            SNDC_CLOSE -> stop()
            // Volume, pitch and the UDP variants are never negotiated (see [clientFormats]); the
            // legacy crypt-key exchange belongs to standard RDP security, which this client refuses.
            else -> Unit
        }
    }

    /**
     * The same channel as a dynamic-channel handler, for the transport a Windows host prefers once
     * `drdynvc` is open. Registering it is what decides the question: a client that answers the
     * server's `AUDIO_PLAYBACK_DVC` request with "no such channel" gets a session with no sound at
     * all — the server does not fall back to the static one it also opened.
     */
    fun dynamicHandler(send: suspend (ByteArray) -> Unit): DynamicChannelHandler =
        object : DynamicChannelHandler {
            override suspend fun onOpen() = trace("the server opened $DVC_NAME")

            override suspend fun onMessage(data: ByteArray) = onData(data, send)
        }

    /**
     * Play queued blocks until the channel is closed. Blocks on the device, so it belongs on a
     * thread that may block — the session runs it on the IO dispatcher.
     */
    suspend fun play() {
        for (block in queue) player.play(block.format, block.pcm)
    }

    /** Stop playback and release the device; [play] returns. Idempotent. */
    fun close() {
        queue.close()
        player.close()
    }

    /**
     * Answer the server's format list with the subset this client can play, and — for a server new
     * enough to ask — the quality it should send.
     */
    private suspend fun serverFormats(body: RdpReader, send: suspend (ByteArray) -> Unit) {
        if (body.remaining < SERVER_FORMATS_HEADER) return
        body.skip(12) // dwFlags, dwVolume, dwPitch
        body.u16le() // wDGramPort: UDP audio, which this client never asks for
        val count = body.u16le()
        body.u8() // cLastBlockConfirmed
        val version = body.u16le()
        body.u8() // bPad
        val playable = buildList {
            repeat(count) {
                if (body.remaining < FORMAT_SIZE) return@buildList
                val tag = body.u16le()
                val channels = body.u16le()
                val sampleRate = body.u32le()
                body.u32le() // nAvgBytesPerSec
                body.u16le() // nBlockAlign
                val bits = body.u16le()
                val extra = body.u16le()
                if (extra > body.remaining) return@buildList
                body.skip(extra)
                val format = RemoteAudioFormat(sampleRate, channels, bits)
                if (tag == WAVE_FORMAT_PCM && format.isPlayable) add(format)
            }
        }
        // A server that offers only formats we cannot decode still hears one it can encode: plain
        // 16-bit stereo PCM is the format every Windows host can produce, and the alternative is a
        // channel that is open and permanently silent.
        formats = playable.ifEmpty { listOf(DEFAULT_FORMAT) }
        trace("server version $version offered $count formats, answering with $formats")
        send(clientFormats(formats))
        if (version >= QUALITY_MODE_VERSION) {
            send(pdu(SNDC_QUALITYMODE, RdpWriter(4).u16le(HIGH_QUALITY).u16le(0).toByteArray()))
        }
    }

    /**
     * Echo the training PDU. The server measures the round trip to size its buffers, and takes no
     * answer as "this client cannot play sound" — after which it stops sending any.
     */
    private suspend fun training(body: RdpReader, send: suspend (ByteArray) -> Unit) {
        if (body.remaining < 4) return
        val timestamp = body.u16le()
        val packSize = body.u16le()
        send(pdu(SNDC_TRAINING, RdpWriter(4).u16le(timestamp).u16le(packSize).toByteArray()))
    }

    /**
     * The head of a wave block (2.2.3.3.1). Its BodySize counts the whole block, not this PDU: the
     * four sample bytes here are its first ones, and the rest arrive next.
     */
    private fun waveInfo(body: RdpReader, bodySize: Int) {
        if (body.remaining < WAVE_INFO_BODY) return
        val timestamp = body.u16le()
        val formatNo = body.u16le()
        val blockNo = body.u8()
        body.skip(3) // bPad
        val head = body.bytes(WAVE_HEAD_BYTES)
        pendingWave = PendingWave(
            format = formats.getOrNull(formatNo),
            timestamp = timestamp,
            blockNo = blockNo,
            head = head,
            totalBytes = (bodySize - WAVE_INFO_BODY + WAVE_HEAD_BYTES).coerceAtLeast(WAVE_HEAD_BYTES),
        )
    }

    private suspend fun completeWave(wave: PendingWave, data: ByteArray, send: suspend (ByteArray) -> Unit) {
        // The payload repeats four padding bytes where the head's samples went.
        val tail = if (data.size > WAVE_HEAD_BYTES) {
            data.copyOfRange(WAVE_HEAD_BYTES, minOf(data.size, wave.totalBytes))
        } else {
            ByteArray(0)
        }
        enqueue(wave.format, wave.head + tail)
        confirm(wave.timestamp, wave.blockNo, send)
    }

    /** A whole wave block in one PDU (2.2.3.10), which is what a version 8 server sends. */
    private suspend fun wave2(body: RdpReader, send: suspend (ByteArray) -> Unit) {
        if (body.remaining < WAVE2_BODY) return
        val timestamp = body.u16le()
        val formatNo = body.u16le()
        val blockNo = body.u8()
        body.skip(3) // bPad
        body.u32le() // dwAudioTimeStamp, for a client that syncs sound to video
        enqueue(formats.getOrNull(formatNo), body.rest())
        confirm(timestamp, blockNo, send)
    }

    /**
     * Confirm a block. Sent as soon as the block is queued rather than after it plays: the server
     * allows only a few unconfirmed blocks at a time, so confirming at playback speed would leave
     * the stream stalling for a device buffer it cannot see.
     */
    private suspend fun confirm(timestamp: Int, blockNo: Int, send: suspend (ByteArray) -> Unit) {
        send(pdu(SNDC_WAVECONFIRM, RdpWriter(4).u16le(timestamp).u8(blockNo).u8(0).toByteArray()))
    }

    private fun enqueue(format: RemoteAudioFormat?, pcm: ByteArray) {
        if (format == null || pcm.isEmpty()) {
            trace("dropped a block of ${pcm.size} bytes in an unknown format")
            return
        }
        if (blocksQueued++ == 0) trace("first block: ${pcm.size} bytes of $format")
        queue.trySend(Block(format, pcm))
    }

    private var blocksQueued = 0

    /** The server closed the stream: what is still queued belongs to sound nobody will hear. */
    private fun stop() {
        while (queue.tryReceive().isSuccess) Unit
        player.flush()
    }

    private fun clientFormats(offered: List<RemoteAudioFormat>): ByteArray {
        val body = RdpWriter(SERVER_FORMATS_HEADER + offered.size * FORMAT_SIZE)
        // Only TSSNDCAPS_ALIVE: volume and pitch are the server's to apply, and claiming them would
        // have it send changes this client does nothing with.
        body.u32le(TSSNDCAPS_ALIVE)
        body.u32le(VOLUME_FULL)
        body.u32le(0) // dwPitch, unused
        body.u16le(0) // wDGramPort: no UDP audio
        body.u16le(offered.size)
        body.u8(0) // cLastBlockConfirmed
        body.u16le(CLIENT_VERSION)
        body.u8(0) // bPad
        for (format in offered) {
            val frameBytes = format.channels * format.bitsPerSample / 8
            body.u16le(WAVE_FORMAT_PCM)
                .u16le(format.channels)
                .u32le(format.sampleRate)
                .u32le(format.sampleRate * frameBytes)
                .u16le(frameBytes)
                .u16le(format.bitsPerSample)
                .u16le(0) // cbSize: PCM carries no extra format data
        }
        return pdu(SNDC_FORMATS, body.toByteArray())
    }

    private fun pdu(messageType: Int, body: ByteArray): ByteArray =
        RdpWriter(body.size + HEADER_SIZE)
            .u8(messageType)
            .u8(0) // bPad
            .u16le(body.size)
            .bytes(body)
            .toByteArray()

    /** What a platform player is expected to open: mono or stereo, 8 or 16 bits, at a sane rate. */
    private val RemoteAudioFormat.isPlayable: Boolean
        get() = channels in 1..2 && (bitsPerSample == 8 || bitsPerSample == 16) &&
            sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE

    companion object {
        /** The dynamic virtual channel the audio protocol travels on when `drdynvc` is open. */
        const val DVC_NAME = "AUDIO_PLAYBACK_DVC"

        /** SNDPROLOG (2.2.1): msgType, bPad, BodySize. */
        private const val HEADER_SIZE = 4

        private const val SNDC_CLOSE = 0x01
        private const val SNDC_WAVE = 0x02
        private const val SNDC_WAVECONFIRM = 0x05
        private const val SNDC_TRAINING = 0x06
        private const val SNDC_FORMATS = 0x07
        private const val SNDC_QUALITYMODE = 0x0C
        private const val SNDC_WAVE2 = 0x0D

        private const val WAVE_FORMAT_PCM = 0x0001

        /** dwFlags, dwVolume, dwPitch, wDGramPort, wNumberOfFormats, cLastBlockConfirmed, wVersion, bPad. */
        private const val SERVER_FORMATS_HEADER = 20

        /** AUDIO_FORMAT without its (absent for PCM) extra data. */
        private const val FORMAT_SIZE = 18

        private const val WAVE_INFO_BODY = 12
        private const val WAVE2_BODY = 12
        /** Samples the WaveInfo PDU carries ahead of the rest of its block. */
        private const val WAVE_HEAD_BYTES = 4

        private const val TSSNDCAPS_ALIVE = 0x00000001

        /** dwVolume is two 16-bit channel volumes; both at maximum leave the level to the server. */
        private const val VOLUME_FULL = -1 // 0xFFFFFFFF

        /** Version 6 is what mstsc announces, and the first that understands the quality mode. */
        private const val CLIENT_VERSION = 6
        private const val QUALITY_MODE_VERSION = 6
        private const val HIGH_QUALITY = 0x0002

        private val DEFAULT_FORMAT = RemoteAudioFormat(44100, 2, 16)

        private const val MIN_SAMPLE_RATE = 8000
        private const val MAX_SAMPLE_RATE = 48000

        /**
         * Blocks held between the read loop and the device. A block is around 20 ms of audio, so this
         * is roughly a second of slack — enough to ride out a stalled device, short enough that what
         * survives a drop is still close to live.
         */
        private const val QUEUED_BLOCKS = 48
    }
}
