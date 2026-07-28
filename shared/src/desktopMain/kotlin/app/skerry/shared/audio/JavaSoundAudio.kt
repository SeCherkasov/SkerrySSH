package app.skerry.shared.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/**
 * The desktop's playback devices, as `javax.sound.sampled` sees them: every mixer that can open a
 * [SourceDataLine]. A mixer's name is its id — it survives a restart, unlike the index in this list,
 * which shifts as soon as a USB headset is plugged in.
 */
class JavaSoundOutputs : AudioOutputs {
    override fun devices(): List<AudioOutputDevice> =
        runCatching {
            AudioSystem.getMixerInfo()
                .filter { info -> runCatching { AudioSystem.getMixer(info).supportsPlayback }.getOrDefault(false) }
                .map { info -> AudioOutputDevice(id = info.name.trim(), name = label(info)) }
                .filter { it.id.isNotEmpty() }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())

    /**
     * ALSA names a mixer after the card's short id (`UA4 [plughw:3,0]`) and puts the product name in
     * the description, so the list would otherwise read as a column of abbreviations. Everywhere
     * else the name is already what the user would recognise, and is left alone.
     */
    private fun label(info: Mixer.Info): String = mixerLabel(info.name, info.description)

    internal companion object {
        private const val DIRECT_AUDIO_PREFIX = "Direct Audio Device: "

        /** [label] over the two strings a `Mixer.Info` carries, which is all of it that has rules. */
        internal fun mixerLabel(rawName: String, description: String): String {
            val name = rawName.trim()
            val port = name.substringAfter('[', "").substringBefore(']').trim()
            if (!port.startsWith("plughw") && !port.startsWith("hw")) return name
            val product = description.removePrefix(DIRECT_AUDIO_PREFIX).substringBefore(',').trim()
            return if (product.isEmpty()) name else "$product [$port]"
        }
    }
}

/** Opens [JavaSoundPlayer]s; the device is resolved per session, when the sound actually starts. */
class JavaSoundPlayers : RemoteAudioPlayerFactory {
    override fun open(deviceId: String): RemoteAudioPlayer = JavaSoundPlayer(deviceId)
}

/**
 * Plays PCM through a [SourceDataLine] on the mixer named [deviceId] (the system default when it is
 * blank or no longer there).
 *
 * The line is opened on the first block rather than at connect time: the format is the server's to
 * choose, and a line opened for the wrong one would have to be torn down again. A format change
 * reopens it, which is what happens when the remote session switches from a 22 kHz notification
 * sound to 44 kHz media.
 */
class JavaSoundPlayer(private val deviceId: String) : RemoteAudioPlayer {

    /**
     * Written by the playback thread, read by whoever closes the session. Volatile rather than
     * synchronized: [play] blocks inside the line for as long as the device takes to drain, and a
     * lock around it would make [close] wait exactly when it is trying to cut the sound short.
     */
    @Volatile
    private var line: SourceDataLine? = null

    @Volatile
    private var current: RemoteAudioFormat? = null

    @Volatile
    private var closed = false

    override fun play(format: RemoteAudioFormat, pcm: ByteArray) {
        if (closed) return
        if (format != current) reopen(format)
        val open = line ?: return
        // A short write is what the line reports when it was stopped mid-block; there is nothing to
        // retry, since whoever stopped it wanted the rest dropped.
        runCatching { open.write(pcm, 0, pcm.size) }
    }

    override fun flush() {
        runCatching { line?.flush() }
    }

    override fun close() {
        closed = true
        release()
    }

    /**
     * Give the device back. stop() comes before close(): it releases a write parked on a full
     * buffer, which is what lets the playback loop finish instead of holding the session open.
     */
    private fun release() {
        val open = line ?: return
        line = null
        current = null
        runCatching {
            open.stop()
            open.flush()
            open.close()
        }
    }

    private fun reopen(format: RemoteAudioFormat) {
        release()
        if (closed) return
        val audioFormat = AudioFormat(
            format.sampleRate.toFloat(),
            format.bitsPerSample,
            format.channels,
            // 8-bit PCM is unsigned and everything wider is signed — that is what WAVE_FORMAT_PCM means.
            format.bitsPerSample > 8,
            false, // little-endian, as RDP sends it
        )
        val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
        val opened = mixerLine(info) ?: runCatching { AudioSystem.getLine(info) as SourceDataLine }.getOrNull()
        line = opened?.also { target ->
            runCatching {
                // A buffer of a fifth of a second: enough that a scheduling hiccup does not become an
                // audible gap, short enough that the sound stays with the picture.
                target.open(audioFormat, bufferBytes(format))
                target.start()
            }.onFailure {
                runCatching { target.close() }
                line = null
            }
        }
        current = if (line != null) format else null
    }

    /** The line on the profile's chosen mixer, or null when it is gone or cannot take this format. */
    private fun mixerLine(info: DataLine.Info): SourceDataLine? {
        if (deviceId.isEmpty()) return null
        val mixerInfo = runCatching { AudioSystem.getMixerInfo() }.getOrNull()
            ?.firstOrNull { it.name.trim() == deviceId } ?: return null
        return runCatching { AudioSystem.getMixer(mixerInfo).getLine(info) as SourceDataLine }.getOrNull()
    }

    private fun bufferBytes(format: RemoteAudioFormat): Int =
        format.sampleRate * format.channels * (format.bitsPerSample / 8) / 5
}

private val Mixer.supportsPlayback: Boolean
    get() = isLineSupported(Line.Info(SourceDataLine::class.java))
