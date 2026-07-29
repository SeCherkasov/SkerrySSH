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

/** Opens the desktop's players; the device is resolved per session, when the sound actually starts. */
class JavaSoundPlayers : RemoteAudioPlayerFactory {
    override fun open(deviceId: String): RemoteAudioPlayer =
        PcmPlayer(JavaSoundSinks(deviceId), trace = audioTrace)
}

/**
 * Opens a [SourceDataLine] on the mixer named [deviceId] — the system default when it is blank or no
 * longer there, since an unplugged headset must not cost the sound. When to open one, and what a
 * refusal costs, is [PcmPlayer]'s.
 */
internal class JavaSoundSinks(private val deviceId: String) : PcmSinkOpener {

    override fun open(format: RemoteAudioFormat): PcmSink? {
        val audioFormat = lineFormat(format)
        val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
        val line = mixerLine(info)
            ?: runCatching { AudioSystem.getLine(info) as SourceDataLine }.getOrNull()
            ?: return null
        return runCatching {
            line.open(audioFormat, format.bufferBytes())
            line.start()
            JavaSoundSink(line)
        }.getOrElse {
            runCatching { line.close() }
            null
        }
    }

    /** The line on the profile's chosen mixer, or null when it is gone or cannot take this format. */
    private fun mixerLine(info: DataLine.Info): SourceDataLine? {
        if (deviceId.isEmpty()) return null
        val mixerInfo = runCatching { AudioSystem.getMixerInfo() }.getOrNull()
            ?.firstOrNull { it.name.trim() == deviceId } ?: return null
        return runCatching { AudioSystem.getMixer(mixerInfo).getLine(info) as SourceDataLine }.getOrNull()
    }
}

/** One open line. Writes block while it drains, which is the back-pressure [PcmPlayer] relies on. */
internal class JavaSoundSink(private val line: SourceDataLine) : PcmSink {

    // A short write is what the line reports when it was stopped mid-block; there is nothing to
    // retry, since whoever stopped it wanted the rest dropped.
    override fun write(pcm: ByteArray) {
        line.write(pcm, 0, pcm.size)
    }

    override fun flush() {
        line.flush()
    }

    /**
     * Give the device back. stop() comes before close(): it releases a write parked on a full
     * buffer, which is what lets the playback loop finish instead of holding the session open. Each
     * step stands alone — a line that fails to stop still has to be closed, or it holds the device
     * for the rest of the process.
     */
    override fun close() {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }
}

/**
 * The RDP format as `javax.sound.sampled` takes it. 8-bit PCM is unsigned and everything wider is
 * signed — that is what `WAVE_FORMAT_PCM` means — and the samples arrive little-endian.
 */
internal fun lineFormat(format: RemoteAudioFormat): AudioFormat = AudioFormat(
    format.sampleRate.toFloat(),
    format.bitsPerSample,
    format.channels,
    format.bitsPerSample > 8,
    false,
)

private val Mixer.supportsPlayback: Boolean
    get() = isLineSupported(Line.Info(SourceDataLine::class.java))
