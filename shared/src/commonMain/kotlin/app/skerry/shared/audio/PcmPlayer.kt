package app.skerry.shared.audio

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * One opened playback device, at its narrowest: bytes in, and the two ways a session gives it back.
 *
 * [write] blocks while the device drains, which is the back-pressure that paces the stream (see
 * [RemoteAudioPlayer]). Everything above it — when to open, when to reopen, what a dead device costs
 * — is [PcmPlayer]'s, so that a platform only has to answer for the device itself.
 */
interface PcmSink {
    /** Hand the whole block to the device, blocking until it has taken it. */
    fun write(pcm: ByteArray)

    /** Drop what has not been played yet, keeping the device open. */
    fun flush()

    /** Give the device back. Called once. */
    fun close()
}

/**
 * Opens a device for exactly one format; `null` when the platform has none it can play this on —
 * which is not fatal, the next block asks again.
 */
fun interface PcmSinkOpener {
    fun open(format: RemoteAudioFormat): PcmSink?
}

/**
 * The playback half of [RemoteAudioPlayer], as both platforms run it.
 *
 * The device is opened on the first block rather than at connect time: the format is the server's to
 * choose, and a device opened for the wrong one would have to be torn down again. A format change
 * reopens it — a remote session switches from a 22 kHz notification sound to 44 kHz media without
 * asking.
 *
 * Nothing here throws at the caller. A device that will not open costs the block it was given and is
 * asked for again on the next one. A device that dies mid-stream is not replaced: it keeps taking
 * blocks nobody hears until the server changes format, which is the trade both platform sinks made
 * before this class existed — rebuilding a device on every failed write would rebuild it fifty times
 * a second, and a write fails for reasons that outlive the retry.
 */
class PcmPlayer(
    private val sinks: PcmSinkOpener,
    /**
     * Where a line about the device goes. Silent by default; the session turns it on from an
     * environment variable. A device that never opens looks exactly like a server that stopped
     * sending, and only these lines tell the two apart.
     */
    private val trace: (String) -> Unit = {},
) : RemoteAudioPlayer {

    /**
     * Guards the fields below, and nothing slow: opening a device, writing to it and closing it all
     * happen outside it. [play] blocks inside the device for as long as it takes to drain and
     * [flush] runs on the session's read loop — a lock held across either would stall the picture
     * along with the sound.
     */
    private val lock = SynchronizedObject()

    private var sink: PcmSink? = null

    private var current: RemoteAudioFormat? = null

    private var closed = false

    /** Traced once per spell of silence: every block retries, and a line each would be a flood. */
    private var refused = false

    private var writesFailing = false

    /**
     * A device took blocks and then stopped: the session is mute and stays that way until a format
     * change reopens the device. Read from outside so the session can say so — a device that dies
     * mid-stream is otherwise indistinguishable from a server that went quiet.
     *
     * False while no device would open *at all*: nothing was playing, every block retries, and
     * calling that a dead device would raise the alarm on the first block of a session that has
     * none. A device that was playing and then would not reopen for a new format is a different
     * story and does count — see [device].
     */
    override val playbackFailed: Boolean
        get() = synchronized(lock) { writesFailing }

    /** Formats already announced to the trace; a session alternates between two or three of them. */
    private val traced = mutableSetOf<RemoteAudioFormat>()

    override fun play(format: RemoteAudioFormat, pcm: ByteArray) {
        val open = device(format) ?: return
        // A device that fails mid-block was unplugged or reclaimed; there is nothing to retry, and
        // the block it swallowed is 20 ms of a stream that keeps arriving.
        runCatching { open.write(pcm) }.onFailure { failure ->
            val first = synchronized(lock) { (!writesFailing).also { writesFailing = true } }
            if (first) trace("the device stopped taking blocks: $failure")
        }
    }

    override fun flush() {
        val open = synchronized(lock) { sink } ?: return
        runCatching { open.flush() }
    }

    override fun close() {
        val open = synchronized(lock) {
            closed = true
            take()
        }
        runCatching { open?.close() }
    }

    /**
     * The device to play [format] on, opened here when the last block was in another one. Only the
     * playback path calls this, one block at a time; [close] is the only other writer of the fields.
     */
    private fun device(format: RemoteAudioFormat): PcmSink? {
        val previous = synchronized(lock) {
            if (closed) return null
            if (format == current) return sink
            take()
        }
        runCatching { previous?.close() }
        val opened = runCatching { sinks.open(format) }.getOrNull()
        if (opened == null) {
            if (!refused) trace("no device would open for $format")
            refused = true
            // A reopen that fails is a device that was playing and now isn't — the same silence as a
            // write that stops being taken, one step earlier in the state machine. Only a session
            // that never had a device at all ([previous] null) is left unreported.
            if (previous != null) synchronized(lock) { writesFailing = true }
            return null
        }
        val published = synchronized(lock) {
            // close() may have run through the open above, on the thread that tore the session down
            // or from the opener itself. It found nothing to give back, and this device would stay
            // open for the rest of the process.
            if (closed) {
                false
            } else {
                refused = false
                writesFailing = false
                sink = opened
                current = format
                true
            }
        }
        if (!published) {
            runCatching { opened.close() }
            return null
        }
        if (traced.add(format)) trace("a device took $format")
        return opened
    }

    /** The device, out of the player and into the caller's hands. Call under [lock]. */
    private fun take(): PcmSink? {
        val open = sink ?: return null
        sink = null
        current = null
        return open
    }
}
