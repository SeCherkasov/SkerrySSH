package app.skerry.shared.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop sink: how an RDP format becomes a `javax.sound.sampled` line, and what the session
 * does to that line. A real mixer is not part of it — the tests that need a device are the ones no
 * CI runner can run, and the rules that used to hide behind one are exercised against a fake line.
 */
class JavaSoundSinkTest {

    private val stereo = RemoteAudioFormat(sampleRate = 44100, channels = 2, bitsPerSample = 16)
    private val notification = RemoteAudioFormat(sampleRate = 22050, channels = 1, bitsPerSample = 8)

    @Test
    fun `16-bit PCM opens a signed little-endian line`() {
        val format = lineFormat(stereo)

        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.encoding)
        assertEquals(44100f, format.sampleRate)
        assertEquals(16, format.sampleSizeInBits)
        assertEquals(2, format.channels)
        assertEquals(4, format.frameSize)
        assertFalse(format.isBigEndian, "RDP sends PCM little-endian")
    }

    /** 8-bit PCM is unsigned and everything wider is signed — that is what `WAVE_FORMAT_PCM` means. */
    @Test
    fun `8-bit PCM opens an unsigned line`() {
        val format = lineFormat(notification)

        assertEquals(AudioFormat.Encoding.PCM_UNSIGNED, format.encoding)
        assertEquals(1, format.frameSize)
    }

    @Test
    fun `the line takes the whole block`() {
        val line = FakeLine()

        JavaSoundSink(line).write(ByteArray(320))

        assertEquals(listOf("write 0..320"), line.events)
    }

    @Test
    fun `flush drops the buffer and keeps the line`() {
        val line = FakeLine()

        JavaSoundSink(line).flush()

        assertEquals(listOf("flush"), line.events)
        assertFalse(line.closed)
    }

    /**
     * stop() comes before close(): it releases a write parked on a full buffer, which is what lets
     * the playback loop finish instead of holding the session open.
     */
    @Test
    fun `the line is stopped before it is given back`() {
        val line = FakeLine()

        JavaSoundSink(line).close()

        assertEquals(listOf("stop", "flush", "close"), line.events)
    }

    @Test
    fun `a line that throws on the way out is still closed`() {
        val line = FakeLine(failStop = true)

        JavaSoundSink(line).close()

        assertTrue(line.closed, "a line left open holds the device for the rest of the process")
    }
}

/** A [SourceDataLine] that records what was asked of it; everything else is dead weight. */
private class FakeLine(private val failStop: Boolean = false) : SourceDataLine {

    val events = mutableListOf<String>()
    var closed = false
        private set

    override fun write(b: ByteArray, off: Int, len: Int): Int {
        events += "write $off..$len"
        return len
    }

    override fun flush() {
        events += "flush"
    }

    override fun start() {
        events += "start"
    }

    override fun stop() {
        events += "stop"
        if (failStop) error("the line is gone")
    }

    override fun close() {
        events += "close"
        closed = true
    }

    override fun open(format: AudioFormat, bufferSize: Int) = Unit
    override fun open(format: AudioFormat) = Unit
    override fun open() = Unit
    override fun drain() = Unit
    override fun isRunning() = false
    override fun isActive() = false
    override fun isOpen() = !closed
    override fun getFormat(): AudioFormat = AudioFormat(44100f, 16, 2, true, false)
    override fun getBufferSize() = 0
    override fun available() = 0
    override fun getFramePosition() = 0
    override fun getLongFramePosition() = 0L
    override fun getMicrosecondPosition() = 0L
    override fun getLevel() = 0f
    override fun getLineInfo(): Line.Info = Line.Info(SourceDataLine::class.java)
    override fun getControls(): Array<Control> = emptyArray()
    override fun isControlSupported(control: Control.Type) = false
    override fun getControl(control: Control.Type): Control = throw IllegalArgumentException("no controls")
    override fun addLineListener(listener: LineListener) = Unit
    override fun removeLineListener(listener: LineListener) = Unit
}
