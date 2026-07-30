package app.skerry.shared.audio

/**
 * Uncompressed PCM as a remote session sends it: interleaved samples, little-endian, signed at 16
 * bits and unsigned at 8 — which is what `WAVE_FORMAT_PCM` means at each depth.
 */
data class RemoteAudioFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
)

/**
 * What a platform device should hold: a fifth of a second of this format. Enough that a scheduling
 * hiccup does not become an audible gap, short enough that the sound stays with the picture.
 */
internal fun RemoteAudioFormat.bufferBytes(): Int =
    sampleRate * channels * (bitsPerSample / 8) / BUFFER_FRACTION

private const val BUFFER_FRACTION = 5

/**
 * A playback device the platform offers. [id] is what a host profile stores, so it has to survive a
 * reboot and a reconnected device; [name] is what the user picks from.
 */
data class AudioOutputDevice(val id: String, val name: String)

/**
 * The platform's playback devices. A profile naming a device that is no longer there falls back to
 * [SYSTEM_DEFAULT_ID] rather than staying silent — an unplugged headset must not cost the sound.
 */
fun interface AudioOutputs {
    fun devices(): List<AudioOutputDevice>

    companion object {
        /** "Whatever the system plays through", the default a profile stores when nothing is picked. */
        const val SYSTEM_DEFAULT_ID = ""
    }
}

/**
 * Plays PCM on one output device.
 *
 * [play] blocks on purpose: the device buffer drains in real time, and that back-pressure is what
 * paces a stream arriving faster than it plays. So it must never be called from a loop that has to
 * stay responsive — [app.skerry.shared.rdp.AudioChannel] keeps a queue between the two for exactly
 * that reason. [flush] and [close] may be called from another thread while [play] is blocked, and
 * are what unblock it.
 */
interface RemoteAudioPlayer {
    /** Play [pcm]; a format different from the last call reopens the device. */
    fun play(format: RemoteAudioFormat, pcm: ByteArray)

    /** Drop whatever has not been played yet (the server closed the stream). */
    fun flush()

    /**
     * Whether the device this player opened has stopped taking blocks. Default false: a player that
     * cannot tell reports nothing rather than a permanent alarm.
     */
    val playbackFailed: Boolean get() = false

    /** Release the device. Idempotent. */
    fun close()
}

/**
 * Opens a player on a device id from [AudioOutputs]; `null` when the platform has no output at all.
 * An id that no longer resolves is not a failure — the player opens the system default instead.
 */
fun interface RemoteAudioPlayerFactory {
    fun open(deviceId: String): RemoteAudioPlayer?
}
