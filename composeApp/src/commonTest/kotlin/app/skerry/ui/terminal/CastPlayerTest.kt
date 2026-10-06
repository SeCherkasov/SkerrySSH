package app.skerry.ui.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.CastEvent
import app.skerry.shared.terminal.TerminalState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import app.skerry.shared.terminal.CastEventSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastPlayerTest {

    private val cast = Asciicast(
        columns = 80,
        rows = 24,
        title = "root@alpha",
        events = listOf(CastEvent(0.5, "a"), CastEvent(1.5, "b"), CastEvent(2.0, "c")),
    )

    @Test
    fun `segmented source plays and seeks without materializing the cast`() = runTest {
        val source = object : app.skerry.shared.terminal.CastEventSource {
            override val size = 3
            override val duration = 2.0
            override suspend fun event(index: Int) = cast.events[index]
            override fun close() = Unit
        }
        val streamed = cast.copy(events = emptyList(), source = source)
        val player = CastPlayer(streamed, backgroundScope)
        val seen = collect(player)
        player.play()
        advanceTimeBy(600)
        assertEquals(listOf("a"), seen)
        player.seekTo(1.5)
        runCurrent()
        assertTrue(seen.joinToString("").contains("ab"))
        advanceTimeBy(600)
        assertEquals("c", seen.last())
    }

    @Test
    fun `play during suspended seek preserves uncommitted segmented catch up`() = runTest {
        interruptedSeek(wasPlaying = false)
    }

    @Test
    fun `pause then play during suspended seek preserves uncommitted segmented catch up`() = runTest {
        interruptedSeek(wasPlaying = true)
    }

    @Test
    fun `cancelled seek download failure cannot overwrite newer playback state`() = runTest {
        val releaseOldDownload = CompletableDeferred<Unit>()
        var firstRead = true
        val source = object : CastEventSource {
            override val size = cast.events.size
            override val duration = cast.duration
            override suspend fun event(index: Int): CastEvent {
                if (firstRead) {
                    firstRead = false
                    withContext(NonCancellable) {
                        releaseOldDownload.await()
                        error("old segment download failed")
                    }
                }
                return cast.events[index]
            }
            override fun close() = Unit
        }
        val player = CastPlayer(cast.copy(events = emptyList(), source = source), backgroundScope)
        val seen = collect(player)
        player.seekTo(1.6)
        runCurrent()
        player.seekTo(1.6)
        runCurrent()
        player.play()
        runCurrent()
        assertEquals(listOf(RESET + "ab"), seen)

        releaseOldDownload.complete(Unit)
        runCurrent()
        assertFalse(player.failed)
        assertTrue(player.playing)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf(RESET + "ab", "c"), seen)
    }

    private suspend fun TestScope.interruptedSeek(wasPlaying: Boolean) {
        val readingLaterSegment = CompletableDeferred<Unit>()
        val releaseLaterSegment = CompletableDeferred<Unit>()
        val source = object : CastEventSource {
            override val size = cast.events.size
            override val duration = cast.duration
            override suspend fun event(index: Int): CastEvent {
                if (index == 2) {
                    readingLaterSegment.complete(Unit)
                    releaseLaterSegment.await()
                }
                return cast.events[index]
            }
            override fun close() = Unit
        }
        val player = CastPlayer(cast.copy(events = emptyList(), source = source), backgroundScope)
        val seen = collect(player)
        if (wasPlaying) {
            player.play()
            advanceTimeBy(600)
            assertEquals(listOf("a"), seen)
            seen.clear()
        }
        player.seekTo(1.6)
        runCurrent()
        assertTrue(readingLaterSegment.isCompleted)
        assertEquals(emptyList(), seen) // RESET + ab are buffered, not delivered yet.

        if (wasPlaying) player.pause()
        player.play()
        runCurrent()
        releaseLaterSegment.complete(Unit)
        runCurrent()
        advanceTimeBy(600)
        runCurrent()

        assertEquals(listOf(RESET + "ab", "c"), seen)
        assertTrue(player.finished)
    }

    @Test
    fun `plays events at their recorded times`() = runTest {
        val player = CastPlayer(cast, backgroundScope)
        val seen = collect(player)
        player.play()

        advanceTimeBy(400)
        assertEquals(emptyList(), seen)
        advanceTimeBy(200) // 0.6s
        assertEquals(listOf("a"), seen)
        advanceTimeBy(1000) // 1.6s
        assertEquals(listOf("a", "b"), seen)
        advanceTimeBy(500) // 2.1s
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test
    fun `speed scales the waits`() = runTest {
        val player = CastPlayer(cast, backgroundScope)
        val seen = collect(player)
        player.changeSpeed(2f)
        player.play()

        advanceTimeBy(300) // 0.6s of recording time
        assertEquals(listOf("a"), seen)
    }

    @Test
    fun `pause holds the position and play resumes from it`() = runTest {
        val player = CastPlayer(cast, backgroundScope)
        val seen = collect(player)
        player.play()
        advanceTimeBy(600)
        player.pause()
        runCurrent()

        assertFalse(player.playing)
        advanceTimeBy(5_000)
        assertEquals(listOf("a"), seen) // nothing arrives while paused

        player.play()
        advanceTimeBy(1_100) // back past 1.5s of recording time
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun `reaching the end stops playback`() = runTest {
        val player = CastPlayer(cast, backgroundScope)
        collect(player)
        player.play()
        advanceTimeBy(3_000)

        assertTrue(player.finished)
        assertFalse(player.playing)
        assertEquals(cast.duration, player.position)
    }

    @Test
    fun `restart resets the screen and plays from the top`() = runTest {
        val player = CastPlayer(cast, backgroundScope)
        val seen = collect(player)
        player.play()
        advanceTimeBy(3_000)

        player.restart()
        runCurrent()
        assertEquals("a b c $RESET", seen.joinToString(" ")) // the reset is the terminal's RIS
        assertEquals(0.0, player.position)
        assertFalse(player.finished)

        advanceTimeBy(600)
        assertEquals("a b c $RESET a", seen.joinToString(" "))
    }

    @Test
    fun `seeking forward replays everything up to that point at once`() = runTest {
        val player = CastPlayer(cast, backgroundScope)
        val seen = collect(player)
        player.seekTo(1.6)
        runCurrent()

        // One catch-up write: a full reset followed by every event up to the target.
        assertEquals(listOf(RESET + "ab"), seen)
        assertEquals(1.6, player.position)

        player.play()
        advanceTimeBy(500)
        assertEquals(listOf(RESET + "ab", "c"), seen)
    }

    @Test
    fun `seeking backwards drops what came after`() = runTest {
        val player = CastPlayer(cast, backgroundScope)
        val seen = collect(player)
        player.play()
        advanceTimeBy(3_000)
        seen.clear()

        player.seekTo(0.7)
        runCurrent()
        assertEquals(listOf(RESET + "a"), seen)
        assertFalse(player.finished)
    }

    @Test
    fun `an empty recording finishes immediately`() = runTest {
        val player = CastPlayer(Asciicast(80, 24, null, emptyList()), backgroundScope)
        collect(player)
        player.play()
        runCurrent()

        assertTrue(player.finished)
        assertFalse(player.playing)
    }

    @Test
    fun `a recording is watched, not driven`() = runTest {
        val player = CastPlayer(cast, backgroundScope)
        val seen = collect(player)
        player.send("rm -rf /\n".encodeToByteArray())
        player.resize(PtySize(120, 40))
        runCurrent()

        assertEquals(emptyList(), seen) // input goes nowhere: there is no session behind the screen
        assertEquals(TerminalState.Open, player.state.value)
    }

    @Test
    fun `formats the clock`() {
        assertEquals("0:00", formatCastTime(0.0))
        assertEquals("0:07", formatCastTime(7.4))
        assertEquals("1:05", formatCastTime(65.0))
        assertEquals("1:00:00", formatCastTime(3600.0))
        assertEquals("2:03:04", formatCastTime(7384.0))
        assertEquals("0:00", formatCastTime(-5.0))
    }

    /** Collects the player's output into a list of decoded chunks. */
    private fun kotlinx.coroutines.test.TestScope.collect(player: CastPlayer): MutableList<String> {
        val seen = mutableListOf<String>()
        backgroundScope.launch { player.output.collect { seen += it.decodeToString() } }
        runCurrent()
        return seen
    }

    private companion object {
        /** RIS — full terminal reset, what the player sends before replaying from a position. */
        const val RESET = "\u001bc"
    }
}
