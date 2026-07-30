package app.skerry.shared.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.IOException

/**
 * The device's audio outputs (speaker, wired headset, a Bluetooth headset, an HDMI sink).
 *
 * The id is the sink's type and product name rather than [AudioDeviceInfo.id], which is handed out
 * per connection: a headset unplugged and plugged back in gets a new numeric id, and a profile that
 * stored one would silently fall back to the speaker.
 */
class AndroidAudioOutputs(context: Context) : AudioOutputs {

    private val appContext = context.applicationContext

    override fun devices(): List<AudioOutputDevice> {
        val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .map { AudioOutputDevice(id = it.stableId, name = it.label) }
            .distinctBy { it.id }
    }
}

/** Opens the Android players on the device an RDP profile named. */
class AndroidAudioPlayers(context: Context) : RemoteAudioPlayerFactory {

    private val appContext = context.applicationContext

    override fun open(deviceId: String): RemoteAudioPlayer =
        PcmPlayer(AndroidTrackSinks(appContext, deviceId), trace = audioTrace)
}

/**
 * Opens an [AudioTrack] routed to [deviceId] (the system's own routing when it is blank or the
 * device is gone). When to open one, and what a refusal costs, is [PcmPlayer]'s.
 */
internal class AndroidTrackSinks(
    private val context: Context,
    private val deviceId: String,
) : PcmSinkOpener {

    override fun open(format: RemoteAudioFormat): PcmSink? {
        val encoding = AndroidAudioMapping.encoding(format.bitsPerSample) ?: return null
        val channelMask = AndroidAudioMapping.channelMask(format.channels)
        val built = runCatching {
            val minimum = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
                .coerceAtLeast(format.bufferBytes())
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(minimum)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return null
        return runCatching {
            // The setter shares play()'s guard: the sink the profile named can go stale between the
            // lookup and the assignment (a Bluetooth headset dropping), and a track built but never
            // released holds the output for the rest of the process.
            built.preferredDevice = preferredDevice()
            built.play()
            AndroidTrackSink(AudioTrackHandle(built))
        }.getOrElse {
            runCatching { built.release() }
            null
        }
    }

    private fun preferredDevice(): AudioDeviceInfo? {
        if (deviceId.isEmpty()) return null
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.stableId == deviceId }
    }
}

/**
 * The calls a sink makes on an [AudioTrack], as an interface.
 *
 * A track cannot be built off a real device, so without this seam the teardown sequence below —
 * which is what a session's sound hangs on when it ends — could only be read, never run. Public for
 * the same reason as [AndroidAudioMapping]: its tests live in `:androidApp`.
 */
interface AndroidPlaybackTrack {
    /**
     * Hands [pcm] to the device and returns what it answered: the byte count on success, or one of
     * `AudioTrack`'s negative `ERROR_*` codes. `AudioTrack.write` reports a dead or reclaimed device
     * by return value rather than by throwing, so the code has to travel to whoever can act on it.
     */
    fun write(pcm: ByteArray): Int
    fun pause()
    fun flush()
    fun play()
    fun stop()
    fun release()
}

/** One playing track. Writes block while it drains, which is the back-pressure [PcmPlayer] relies on. */
class AndroidTrackSink(private val track: AndroidPlaybackTrack) : PcmSink {

    override fun write(pcm: ByteArray) {
        // A negative code means the device is gone (unplugged, reclaimed, mediaserver restarted).
        // It has to become an exception here: [PcmPlayer] tells a dead device from a quiet server by
        // a failing write, and a code returned quietly reads as a block that played. The desktop
        // sink reaches the same state by throwing, so both platforms report it the same way.
        val written = track.write(pcm)
        if (written < 0) throw IOException("the audio device rejected a block of ${pcm.size} bytes: $written")
    }

    /**
     * The track has to be paused before its buffer can be dropped; it resumes on the next write.
     * Each step stands alone, and play() runs whatever happened before it: a track left paused takes
     * every block the session hands it and plays none, and nothing reopens it until the server
     * changes format.
     */
    override fun flush() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.play() }
    }

    /**
     * Give the device back. pause() comes first: it unblocks a write parked on a full buffer, which
     * is what ends the playback loop instead of leaving it stuck on a track nobody listens to. Each
     * step stands alone — a track that fails to stop still has to be released.
     */
    override fun close() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}

/** The real thing behind [AndroidPlaybackTrack]; nothing here has a decision of its own. */
private class AudioTrackHandle(private val track: AudioTrack) : AndroidPlaybackTrack {

    override fun write(pcm: ByteArray): Int = track.write(pcm, 0, pcm.size)

    override fun pause() = track.pause()

    override fun flush() = track.flush()

    override fun play() = track.play()

    override fun stop() = track.stop()

    override fun release() = track.release()
}

/**
 * How an RDP format and a platform output are read into `android.media`.
 *
 * Separate from the sinks it serves, and public rather than internal, because this is the part of
 * the Android audio path a test can hold: an [AudioTrack] or an [AudioDeviceInfo] cannot be built
 * off a real device, while the mapping decides what a profile stores and what the server is told
 * this client can play. Its tests live in `:androidApp` — the only module here with an Android host
 * test source set (see that module's build file).
 */
object AndroidAudioMapping {

    /** `null` for a depth `AudioTrack` has no encoding for, which is a device that will not open. */
    fun encoding(bitsPerSample: Int): Int? = when (bitsPerSample) {
        8 -> AudioFormat.ENCODING_PCM_8BIT
        16 -> AudioFormat.ENCODING_PCM_16BIT
        else -> null
    }

    /** Anything the server calls multi-channel is played as stereo — the track takes no more. */
    fun channelMask(channels: Int): Int =
        if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

    /** Type plus product name: the same headset gets the same id across reconnects. */
    fun outputId(type: Int, productName: String?): String = "$type:${productName.orEmpty()}"

    /** What the device picker shows: the kind of sink, named after its product when it has one. */
    fun outputLabel(type: Int, productName: String?): String {
        val product = productName?.trim().orEmpty()
        val kind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Audio output"
        }
        return if (product.isEmpty() || product == kind) kind else "$kind — $product"
    }
}

private val AudioDeviceInfo.stableId: String
    get() = AndroidAudioMapping.outputId(type, productName?.toString())

private val AudioDeviceInfo.label: String
    get() = AndroidAudioMapping.outputLabel(type, productName?.toString())
