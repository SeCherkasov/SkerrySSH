package app.skerry.android.audio

import app.skerry.shared.audio.AndroidPlaybackTrack
import app.skerry.shared.audio.AndroidTrackSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the session does to an Android track. The track itself needs a real device, so the sequences
 * that matter — the one that drops a stream mid-session and the one that gives the output back —
 * are exercised against a fake, the way the desktop sink is against a fake line.
 */
class AndroidTrackSinkTest {

    @Test
    fun `the track takes the whole block`() {
        val track = FakeTrack()

        AndroidTrackSink(track).write(ByteArray(320))

        assertEquals(listOf("write 320"), track.events)
    }

    /** The track has to be paused before its buffer can be dropped; it resumes on the next write. */
    @Test
    fun `flush pauses the track, drops the buffer and plays on`() {
        val track = FakeTrack()

        AndroidTrackSink(track).flush()

        assertEquals(listOf("pause", "flush", "play"), track.events)
    }

    /**
     * A track left paused takes every block the session hands it and plays none, and nothing
     * reopens it until the server changes format — so play() runs even when the steps before it did
     * not.
     */
    @Test
    fun `a track that will not pause is played on anyway`() {
        val track = FakeTrack(failPause = true)

        AndroidTrackSink(track).flush()

        assertTrue("play" in track.events, "a session whose sound went silent for good")
    }

    /**
     * pause() comes first: it unblocks a write parked on a full buffer, which is what ends the
     * playback loop instead of leaving it stuck on a track nobody listens to.
     */
    @Test
    fun `the track is paused before it is given back`() {
        val track = FakeTrack()

        AndroidTrackSink(track).close()

        assertEquals(listOf("pause", "flush", "stop", "release"), track.events)
    }

    @Test
    fun `a track that throws on the way out is still released`() {
        val track = FakeTrack(failPause = true, failStop = true)

        AndroidTrackSink(track).close()

        assertTrue(track.released, "a track left unreleased holds the output for the rest of the process")
    }
}

/** An [AndroidPlaybackTrack] that records what was asked of it. */
private class FakeTrack(
    private val failPause: Boolean = false,
    private val failStop: Boolean = false,
) : AndroidPlaybackTrack {

    val events = mutableListOf<String>()
    var released = false
        private set

    override fun write(pcm: ByteArray) {
        events += "write ${pcm.size}"
    }

    override fun pause() {
        events += "pause"
        if (failPause) error("the track is gone")
    }

    override fun flush() {
        events += "flush"
    }

    override fun play() {
        events += "play"
    }

    override fun stop() {
        events += "stop"
        if (failStop) error("the track is gone")
    }

    override fun release() {
        events += "release"
        released = true
    }
}
