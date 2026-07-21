package app.skerry.ui.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.CastEvent
import app.skerry.shared.terminal.TerminalState
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
