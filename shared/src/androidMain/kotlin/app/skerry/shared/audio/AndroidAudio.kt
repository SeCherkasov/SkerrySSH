package app.skerry.shared.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

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

/** Opens [AndroidAudioTrackPlayer]s on the device an RDP profile named. */
class AndroidAudioPlayers(context: Context) : RemoteAudioPlayerFactory {

    private val appContext = context.applicationContext

    override fun open(deviceId: String): RemoteAudioPlayer = AndroidAudioTrackPlayer(appContext, deviceId)
}

/**
 * Plays PCM through an [AudioTrack] routed to [deviceId] (the system's own routing when it is blank
 * or the device is gone).
 *
 * Like the desktop player, the track is built on the first block — the server picks the format — and
 * rebuilt when the format changes. Writes are blocking, so the session keeps this off its read loop.
 */
class AndroidAudioTrackPlayer(
    private val context: Context,
    private val deviceId: String,
) : RemoteAudioPlayer {

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var current: RemoteAudioFormat? = null

    @Volatile
    private var closed = false

    override fun play(format: RemoteAudioFormat, pcm: ByteArray) {
        if (closed) return
        if (format != current) reopen(format)
        val open = track ?: return
        runCatching { open.write(pcm, 0, pcm.size) }
    }

    override fun flush() {
        // The track has to be paused before its buffer can be dropped; it resumes on the next write.
        runCatching {
            track?.let { open ->
                open.pause()
                open.flush()
                open.play()
            }
        }
    }

    override fun close() {
        closed = true
        release()
    }

    private fun release() {
        val open = track ?: return
        track = null
        current = null
        // pause() first: it unblocks a write parked on a full buffer, which is what ends the
        // playback loop instead of leaving it stuck on a track nobody listens to.
        runCatching {
            open.pause()
            open.flush()
            open.stop()
        }
        runCatching { open.release() }
    }

    private fun reopen(format: RemoteAudioFormat) {
        release()
        if (closed) return
        val encoding = when (format.bitsPerSample) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            else -> return
        }
        val channelMask =
            if (format.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val built = runCatching {
            val minimum = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
                .coerceAtLeast(format.sampleRate * format.channels * (format.bitsPerSample / 8) / 5)
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
        }.getOrNull() ?: return
        built.preferredDevice = preferredDevice()
        runCatching { built.play() }.onFailure {
            runCatching { built.release() }
            return
        }
        track = built
        current = format
    }

    private fun preferredDevice(): AudioDeviceInfo? {
        if (deviceId.isEmpty()) return null
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.stableId == deviceId }
    }
}

/** Type plus product name: the same headset gets the same id across reconnects. */
private val AudioDeviceInfo.stableId: String get() = "$type:${productName ?: ""}"

private val AudioDeviceInfo.label: String
    get() {
        val product = productName?.toString()?.trim().orEmpty()
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
