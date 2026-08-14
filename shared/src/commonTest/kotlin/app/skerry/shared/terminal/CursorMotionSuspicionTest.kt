package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a cursor-motion control costs the tracked line (issue #246). An exemption for motions on an
 * EMPTY line was tried — Ctrl-A and Ctrl-B double as the screen and tmux prefixes, and marking a
 * fresh prompt suspect costs the next command its ghost and its history entry — and reverted: a
 * prefix is never pressed alone, and the command key after it is swallowed by the multiplexer and
 * never reaches the shell. With the exemption that key landed in the tracked line as content, so
 * the next commit read `cuptime`, a command nobody ran, persisted, offered back as a ghost, and
 * quoted by the production guard as what would run. Losing an entry is the safe direction;
 * fabricating one is not. These tests pin that decision.
 */
class CursorMotionSuspicionTest {

    @Test
    fun `a cursor motion on an empty line is suspect — the byte may be a multiplexer prefix`() {
        val e = AutocompleteEngine()
        e.onUserInput(byteArrayOf(2)) // Ctrl-B: tmux's default prefix, sent on a fresh prompt

        assertTrue(e.lineSuspect)

        // The window-switch key tmux swallowed must not become part of the next command.
        e.onUserInput("c".encodeToByteArray())
        e.onUserInput("uptime\r".encodeToByteArray())
        assertTrue(
            e.commandHistory.commands.isEmpty(),
            "a fabricated command reached history: ${e.commandHistory.commands}",
        )
    }

    @Test
    fun `a cursor motion inside a typed line makes it a guess`() {
        val e = AutocompleteEngine()
        e.onUserInput("deploy".encodeToByteArray())
        e.onUserInput(byteArrayOf(5)) // Ctrl-E: to the end of the line

        assertTrue(e.lineSuspect)
    }

    @Test
    fun `an edit control on an empty line is suspect too`() {
        // Ctrl-Y yanks the kill ring: the host's line grows while the tracked one stays empty.
        val e = AutocompleteEngine()
        e.onUserInput(byteArrayOf(25))

        assertTrue(e.lineSuspect)
    }
}
