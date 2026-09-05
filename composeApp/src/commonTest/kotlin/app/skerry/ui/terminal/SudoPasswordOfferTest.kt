package app.skerry.ui.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
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
import kotlin.test.assertTrue

/**
 * Issue #360: at a sudo prompt the session offers back the password it authenticated with, and
 * sends it only on an explicit Enter. The rules that carry the risk are all here — nothing is sent
 * without the offer standing, the password never reaches the screen, and one offer answers one
 * prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SudoPasswordOfferTest {

    private val prompt = "[sudo] password for deploy: "

    @Test
    fun `Enter at a sudo prompt sends the saved password`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        assertTrue(state.sudoOffer, "the offer did not stand at a sudo prompt")

        state.typeInput("\r")
        assertEquals(listOf("hunter2\r"), session.text(), "Enter did not send the saved password")
        scope.cancel()
    }

    /** Off by default: with no offer wired in, Enter is the byte the user pressed and nothing else. */
    @Test
    fun `without the setting Enter stays an Enter`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        assertFalse(state.sudoOffer)
        state.typeInput("\r")
        assertEquals(listOf("\r"), session.text())
        scope.cancel()
    }

    /** Any other input declines the offer and is forwarded normally — the user is typing their own. */
    @Test
    fun `typing declines the offer and the next Enter is the user's own`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.typeInput("s")
        assertFalse(state.sudoOffer, "the offer survived input that was not Enter")
        state.typeInput("\r")
        assertEquals(listOf("s", "\r"), session.text(), "the saved password was sent over a typed one")
        scope.cancel()
    }

    /** A paste answers the prompt too: an Enter after it commits what was pasted, not the secret. */
    @Test
    fun `a paste declines the offer`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.paste("typed-by-hand")
        assertFalse(state.sudoOffer)
        scope.cancel()
    }

    /** One offer answers one prompt: a second Enter is the user's, whatever the screen still shows. */
    @Test
    fun `the offer is spent by the Enter that took it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.typeInput("\r")
        assertFalse(state.sudoOffer, "the offer stood after it was taken")
        state.typeInput("\r")
        assertEquals(listOf("hunter2\r", "\r"), session.text(), "the password was sent twice")
        scope.cancel()
    }

    /** A fresh prompt is a fresh offer: sudo re-asking after a wrong password can be answered again. */
    @Test
    fun `the offer re-arms on the next prompt`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.typeInput("s")
        assertFalse(state.sudoOffer)
        session.emit("\r\nSorry, try again.\r\n$prompt".encodeToByteArray())
        assertTrue(state.sudoOffer, "the second prompt got no offer")
        scope.cancel()
    }

    /** An ordinary shell line is not a prompt: Enter runs the command. */
    @Test
    fun `no offer stands outside a sudo prompt`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit("deploy@web:~$ uptime".encodeToByteArray())
        assertFalse(state.sudoOffer)
        state.typeInput("\r")
        assertEquals(listOf("\r"), session.text())
        scope.cancel()
    }

    /**
     * The password is answered to the pane it was offered in. What crosses to a synchronized pane is
     * the fact that the prompt was answered, never the secret: the pane beside this one is another
     * host, and it answers its own prompt with its own credential.
     */
    @Test
    fun `the taken password is not mirrored to synchronized panes`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())
        val mirrored = mutableListOf<Pair<String, MirroredInput>>()
        state.inputMirror = { text, kind -> mirrored += text to kind }

        session.emit(prompt.encodeToByteArray())
        state.typeInput("\r")

        assertEquals(listOf("\r" to MirroredInput.SudoAnswer), mirrored, "the mirror carried the secret")
        scope.cancel()
    }

    /**
     * The attack the dwell exists for: a process on the host prints a prompt in the instant before
     * an Enter the user was already going to press, to harvest it. That Enter is the user's own.
     */
    @Test
    fun `a prompt drawn under the keystroke is not answered`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = eagerPublishClock())

        session.emit(prompt.encodeToByteArray())
        assertTrue(state.sudoOffer, "the offer did not arm")
        state.typeInput("\r")

        assertEquals(listOf("\r"), session.text(), "a prompt with no dwell behind it took the password")
        scope.cancel()
    }

    /** A fullscreen TUI paints whatever it likes; an Enter there edits a buffer, it answers nothing. */
    @Test
    fun `no offer stands on the alternate screen`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit("\u001b[?1049h$prompt".encodeToByteArray())
        assertFalse(state.sudoOffer, "a line inside a TUI armed the offer")
        state.typeInput("\r")
        assertEquals(listOf("\r"), session.text())
        scope.cancel()
    }

    /**
     * An Enter typed in a synchronized pane is aimed at that pane's screen: the user read its prompt,
     * not this one's. It declines here rather than spending this host's credential.
     */
    @Test
    fun `input mirrored from another pane never takes the offer`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.receiveMirrored("\r")

        assertEquals(listOf("\r"), session.text(), "a mirrored Enter sent this host's password")
        scope.cancel()
    }

    /**
     * The other half of synchronized input: the origin pane answered its own prompt, so this one
     * answers its own — with the password it authenticated with, not the origin's.
     */
    @Test
    fun `a mirrored answer sends this pane's own password`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.answerSudoPrompt()

        assertEquals(listOf("hunter2\r"), session.text())
        scope.cancel()
    }

    /**
     * A pane with no offer standing is left alone. The mirrored answer reaches only panes that are
     * themselves at a prompt — `paneSyncTargets` filters on `awaitingSecret` while the origin is
     * taking a secret — so a bare Enter here would submit an EMPTY password to that host's sudo and
     * burn one of its three attempts, seven times over on an eight-pane group.
     */
    @Test
    fun `a mirrored answer at a prompt of its own without an offer sends nothing`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        // A prompt for another account: this pane is at a password prompt, but not one its own
        // credential answers.
        session.emit("[sudo] password for root: ".encodeToByteArray())
        assertFalse(state.sudoOffer, "the offer armed on another account's prompt")
        state.answerSudoPrompt()

        assertEquals(emptyList(), session.text(), "an empty password was submitted to sudo")
        scope.cancel()
    }

    /** Turning the setting off under a live session ends the offer there and then. */
    @Test
    fun `turning the setting off disarms an open session`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.applySudoOfferEnabled(false)
        assertFalse(state.sudoOffer, "the offer survived the setting being turned off")

        state.typeInput("\r")
        assertEquals(listOf("\r"), session.text(), "a revoked offer still sent the password")
        scope.cancel()
    }

    /** A snippet or keybar sequence answering the prompt is the user entering their own secret. */
    @Test
    fun `sent user input declines the offer`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.sendUserInput("from-a-snippet")
        assertFalse(state.sudoOffer)
        state.typeInput("\r")

        assertEquals(listOf("from-a-snippet", "\r"), session.text())
        scope.cancel()
    }

    /** A viewer of a shared session types on the same prompt: their keystroke ends the offer too. */
    @Test
    fun `a shared-session viewer's keystroke declines the offer`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.sendSharedInput("v".encodeToByteArray())
        assertFalse(state.sudoOffer)
        state.typeInput("\r")

        assertEquals(listOf("v", "\r"), session.text(), "the viewer's password was completed by ours")
        scope.cancel()
    }

    /**
     * The same rule for ordinary mirrored typing, not just Enter: a synced `ls\n` typed in another
     * pane must not answer this one's prompt while it happens to be sitting at one.
     */
    @Test
    fun `mirrored typing never takes the offer either`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.receiveMirrored("ls\n")

        assertEquals(listOf("ls\n"), session.text(), "mirrored typing sent this host's password")
        scope.cancel()
    }

    /**
     * A TUI can exit leaving application-keypad mode set. The hint names Enter, so the numpad's
     * Enter has to answer it — otherwise the key the user pressed spends the offer on an empty
     * password instead of taking it.
     */
    @Test
    fun `the numpad Enter answers the offer under DECKPAM`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        state.typeInput("\u001bOM")

        assertEquals(listOf("hunter2\r"), session.text(), "the numpad Enter burned the offer")
        scope.cancel()
    }

    /** The credential does not outlive its connection: the session ending drops it. */
    @Test
    fun `a closed session drops the password`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeSudoSession()
        val state = TerminalScreenState(session, scope, sudo = offer(), nowMillis = dwellingClock())

        session.emit(prompt.encodeToByteArray())
        assertTrue(state.sudoOffer)
        session.close()

        assertFalse(state.sudoOffer, "the offer survived the session it belongs to")
        scope.cancel()
    }

    private fun offer() = SudoPasswordOffer("deploy", "deploy@web", "hunter2")
}

private class FakeSudoSession : TerminalSession {
    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state

    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    override val output: Flow<ByteArray> = flow { for (chunk in emissions) emit(chunk) }

    private val sent = mutableListOf<ByteArray>()

    fun text(): List<String> = sent.map { it.decodeToString() }

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
