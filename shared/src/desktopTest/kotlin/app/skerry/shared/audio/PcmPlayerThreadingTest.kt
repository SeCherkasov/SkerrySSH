package app.skerry.shared.audio

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two waits [PcmPlayer] must never make anyone else do. Playback and teardown are on different
 * threads by design — [app.skerry.shared.rdp.AudioChannel] drains its queue on the IO dispatcher
 * while the session closes the player from wherever the session ends — and keeping the device calls
 * outside the lock is what stops either of these from hanging.
 *
 * On the JVM because it takes real threads; the class under test is common.
 */
class PcmPlayerThreadingTest {

    private val stereo = RemoteAudioFormat(sampleRate = 44100, channels = 2, bitsPerSample = 16)

    @Test
    fun `close gives the device back while a write is parked on it`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val writing = CountDownLatch(1)
        val unpark = CountDownLatch(1)
        val sink = object : PcmSink {
            override fun write(pcm: ByteArray) {
                events += "write"
                writing.countDown()
                unpark.await()
            }

            override fun flush() = Unit

            override fun close() {
                events += "close"
                // A real device unparks the write from here; this one is unparked by the test.
                unpark.countDown()
            }
        }
        val player = PcmPlayer(PcmSinkOpener { sink })

        val playback = thread { player.play(stereo, ByteArray(320)) }
        assertTrue(writing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the write never started")
        val teardown = thread { player.close() }
        teardown.join(TIMEOUT_MILLIS)

        assertFalse(teardown.isAlive, "close waited for a device buffer to drain")
        assertEquals(listOf("write", "close"), events)
        unpark.countDown()
        playback.join(TIMEOUT_MILLIS)
    }

    /**
     * `SNDC_CLOSE` arrives on the session's read loop and ends in [PcmPlayer.flush]. Opening a
     * device is a mixer lookup or a binder call, and a read loop parked on one freezes the picture,
     * the clipboard and the graphics channel with it.
     */
    @Test
    fun `flush does not wait for a device that is still opening`() {
        val opening = CountDownLatch(1)
        val unpark = CountDownLatch(1)
        val sink = object : PcmSink {
            override fun write(pcm: ByteArray) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }
        val player = PcmPlayer(
            PcmSinkOpener {
                opening.countDown()
                unpark.await()
                sink
            },
        )

        val playback = thread { player.play(stereo, ByteArray(320)) }
        assertTrue(opening.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the device never started opening")
        val reader = thread { player.flush() }
        reader.join(TIMEOUT_MILLIS)

        assertFalse(reader.isAlive, "the read loop was left waiting for a device to open")
        unpark.countDown()
        playback.join(TIMEOUT_MILLIS)
    }

    private companion object {
        /** Long enough that a loaded CI runner does not fail a passing implementation. */
        const val TIMEOUT_SECONDS = 5L
        const val TIMEOUT_MILLIS = 5_000L
    }
}
