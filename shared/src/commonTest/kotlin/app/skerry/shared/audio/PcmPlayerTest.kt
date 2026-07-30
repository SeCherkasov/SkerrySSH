package app.skerry.shared.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The playback state machine both platform sinks run on: when a device is opened, when it is torn
 * down and reopened, and what a device that refuses to open or dies mid-stream costs the session.
 *
 * The device itself (a `SourceDataLine` on the desktop, an `AudioTrack` on Android) cannot be opened
 * off a real machine, so the rules that used to live inside each of them are exercised here against
 * a recording sink instead.
 */
class PcmPlayerTest {

    private val stereo = RemoteAudioFormat(sampleRate = 44100, channels = 2, bitsPerSample = 16)
    private val notification = RemoteAudioFormat(sampleRate = 22050, channels = 1, bitsPerSample = 8)

    @Test
    fun `no device is opened until the first block arrives`() {
        val devices = FakeSinks()
        PcmPlayer(devices)

        assertEquals(emptyList(), devices.events)
    }

    @Test
    fun `the first block opens a device and reaches it whole`() {
        val devices = FakeSinks()
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))

        assertEquals(listOf("open#1 $stereo", "write#1 320"), devices.events)
    }

    @Test
    fun `blocks in the same format keep the device that is already open`() {
        val devices = FakeSinks()
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))
        player.play(stereo, ByteArray(160))

        assertEquals(listOf("open#1 $stereo", "write#1 320", "write#1 160"), devices.events)
    }

    /** A session that switches from a 22 kHz notification sound to 44 kHz media, mid-stream. */
    @Test
    fun `a format change releases the old device before opening the new one`() {
        val devices = FakeSinks()
        val player = PcmPlayer(devices)

        player.play(notification, ByteArray(64))
        player.play(stereo, ByteArray(320))

        assertEquals(
            listOf("open#1 $notification", "write#1 64", "close#1", "open#2 $stereo", "write#2 320"),
            devices.events,
        )
    }

    @Test
    fun `a device that will not open drops the block, and the next one tries again`() {
        val devices = FakeSinks(refuse = { it == stereo })
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))
        devices.refuse = { false }
        player.play(stereo, ByteArray(320))

        assertEquals(listOf("refused $stereo", "open#1 $stereo", "write#1 320"), devices.events)
    }

    @Test
    fun `an opener that throws is a device that is not there`() {
        val devices = FakeSinks(refuse = { error("no mixer") })
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))

        assertEquals(emptyList(), devices.events)
    }

    /**
     * A format the device refuses must not leave the previous one playing: the samples that follow
     * are in the new format, and a line still open on the old one would turn them into noise.
     */
    @Test
    fun `a refused format leaves no device behind`() {
        val devices = FakeSinks()
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))
        devices.refuse = { it == notification }
        player.play(notification, ByteArray(64))
        devices.refuse = { false }
        player.play(stereo, ByteArray(320))

        assertEquals(
            listOf(
                "open#1 $stereo", "write#1 320",
                "close#1", "refused $notification",
                "open#2 $stereo", "write#2 320",
            ),
            devices.events,
        )
    }

    @Test
    fun `an opener that throws on a format change leaves no device behind`() {
        val devices = FakeSinks()
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))
        devices.refuse = { error("the mixer went away") }
        player.play(notification, ByteArray(64))

        assertEquals(listOf("open#1 $stereo", "write#1 320", "close#1"), devices.events)
    }

    /**
     * The session is torn down while a device is still opening: [close] finds nothing to give back,
     * and without a second look the device that arrives a moment later is held for the rest of the
     * process.
     */
    @Test
    fun `a device that opens into a closed player is given back at once`() {
        val devices = FakeSinks()
        lateinit var player: PcmPlayer
        devices.onOpen = { player.close() }
        player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))

        assertEquals(listOf("open#1 $stereo", "close#1"), devices.events)
    }

    /** A device unplugged mid-session throws on write; the stream must not die with it. */
    @Test
    fun `a write that throws is not the session's problem`() {
        val devices = FakeSinks()
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))
        devices.failWrite = true
        player.play(stereo, ByteArray(320))
        devices.failWrite = false
        player.play(stereo, ByteArray(160))

        assertEquals(
            listOf("open#1 $stereo", "write#1 320", "write#1 320", "write#1 160"),
            devices.events,
        )
    }

    @Test
    fun `a device that stopped taking blocks says so, and stops saying it once one is open again`() {
        // The session goes mute with nothing on screen to explain it: the block is swallowed, the
        // server keeps sending, and the trace that used to be the only witness is off by default.
        val devices = FakeSinks()
        val player = PcmPlayer(devices)
        player.play(stereo, ByteArray(320))
        assertFalse(player.playbackFailed)

        devices.failWrite = true
        player.play(stereo, ByteArray(320))
        assertTrue(player.playbackFailed)

        // A format change is the one thing that reopens the device (see the class doc), so it is
        // also the only way back to sound.
        devices.failWrite = false
        player.play(notification, ByteArray(160))
        assertFalse(player.playbackFailed)
    }

    @Test
    fun `a device that was playing and will not reopen is a playback failure`() {
        // The same silence as a write that stops being taken, reached through the other door: sound
        // was working, the server switched format, and the device is gone by the time we ask for it
        // again. Reporting this as healthy would leave a mute session with nothing on screen.
        val devices = FakeSinks()
        val player = PcmPlayer(devices)
        player.play(stereo, ByteArray(320))
        assertFalse(player.playbackFailed)

        devices.refuse = { true }
        player.play(notification, ByteArray(160))

        assertTrue(player.playbackFailed)
    }

    @Test
    fun `a device that will not open is not a playback failure`() {
        // Nothing was ever taking blocks, so there is no device that stopped: the player retries on
        // every block, and reporting it as a dead device would be a false alarm on the first one.
        val devices = FakeSinks(refuse = { true })
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))

        assertFalse(player.playbackFailed)
    }

    @Test
    fun `flush drops what the device has not played yet`() {
        val devices = FakeSinks()
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))
        player.flush()

        assertEquals(listOf("open#1 $stereo", "write#1 320", "flush#1"), devices.events)
    }

    @Test
    fun `flushing before anything played touches no device`() {
        val devices = FakeSinks()

        PcmPlayer(devices).flush()

        assertEquals(emptyList(), devices.events)
    }

    @Test
    fun `close releases the device, and later blocks reach nothing`() {
        val devices = FakeSinks()
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))
        player.close()
        player.play(stereo, ByteArray(320))
        player.flush()

        assertEquals(listOf("open#1 $stereo", "write#1 320", "close#1"), devices.events)
    }

    @Test
    fun `close is idempotent`() {
        val devices = FakeSinks()
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))
        player.close()
        player.close()

        assertEquals(listOf("open#1 $stereo", "write#1 320", "close#1"), devices.events)
    }

    @Test
    fun `closing a player that never played releases nothing`() {
        val devices = FakeSinks()

        PcmPlayer(devices).close()

        assertTrue(devices.events.isEmpty())
    }

    /** Teardown races playback: the device may be gone by the time the session gives it back. */
    @Test
    fun `a device that throws on close does not stop the session from closing`() {
        val devices = FakeSinks(failClose = true)
        val player = PcmPlayer(devices)

        player.play(stereo, ByteArray(320))
        player.close()
        player.play(stereo, ByteArray(320))

        assertEquals(listOf("open#1 $stereo", "write#1 320", "close#1"), devices.events)
    }
}

/** Records what the player asks of a device, in the order it asks. */
private class FakeSinks(
    var refuse: (RemoteAudioFormat) -> Boolean = { false },
    private val failClose: Boolean = false,
) : PcmSinkOpener {

    val events = mutableListOf<String>()
    var failWrite = false

    /** Runs while the device is opening — where a teardown from another thread lands. */
    var onOpen: () -> Unit = {}

    private var opened = 0

    override fun open(format: RemoteAudioFormat): PcmSink? {
        if (refuse(format)) {
            events += "refused $format"
            return null
        }
        val no = ++opened
        events += "open#$no $format"
        onOpen()
        return object : PcmSink {
            override fun write(pcm: ByteArray) {
                events += "write#$no ${pcm.size}"
                if (failWrite) error("the device is gone")
            }

            override fun flush() {
                events += "flush#$no"
            }

            override fun close() {
                events += "close#$no"
                if (failClose) error("the device is gone")
            }
        }
    }
}
