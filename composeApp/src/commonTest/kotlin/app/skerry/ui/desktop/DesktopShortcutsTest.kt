package app.skerry.ui.desktop

import androidx.compose.ui.input.key.Key
import app.skerry.shared.ssh.SshAuth
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.FakeShellChannel
import app.skerry.ui.connection.FakeSshConnection
import app.skerry.ui.connection.FakeSshTransport
import app.skerry.ui.connection.testTarget
import app.skerry.shared.vnc.VncAuth
import app.skerry.shared.vnc.VncRemoteDesktop
import app.skerry.ui.remote.RemoteDesktopController
import app.skerry.ui.connection.FakeVncTransport
import app.skerry.ui.session.MAX_PANES
import app.skerry.ui.session.PaneDirection
import app.skerry.ui.session.SessionView
import app.skerry.ui.session.SessionsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopShortcutsTest {

    private fun match(ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false, key: Key) =
        matchDesktopShortcut(ctrl, shift, alt, meta, key)

    @Test
    fun `Alt plus digit selects the tab by zero-based index`() {
        assertEquals(DesktopShortcut.SelectTab(0), match(alt = true, key = Key.One))
        assertEquals(DesktopShortcut.SelectTab(2), match(alt = true, key = Key.Three))
        assertEquals(DesktopShortcut.SelectTab(8), match(alt = true, key = Key.Nine))
    }

    @Test
    fun `Alt plus zero is not a tab shortcut`() {
        assertNull(match(alt = true, key = Key.Zero))
    }

    @Test
    fun `AltGr (Ctrl plus Alt) plus digit is left to the terminal`() {
        assertNull(match(ctrl = true, alt = true, key = Key.One))
    }

    @Test
    fun `Ctrl plus Tab cycles tabs`() {
        assertEquals(DesktopShortcut.NextTab, match(ctrl = true, key = Key.Tab))
        assertEquals(DesktopShortcut.PrevTab, match(ctrl = true, shift = true, key = Key.Tab))
    }

    @Test
    fun `the command palette is on the app modifier plus K`() {
        assertEquals(DesktopShortcut.CommandPalette, match(meta = true, key = Key.K))
        assertEquals(DesktopShortcut.CommandPalette, match(ctrl = true, shift = true, key = Key.K))
        // Plain Ctrl+K stays with the terminal (readline kill-line).
        assertNull(match(ctrl = true, key = Key.K))
    }

    @Test
    fun `broadcast is on the app modifier plus B`() {
        assertEquals(DesktopShortcut.Broadcast, match(meta = true, key = Key.B))
        assertEquals(DesktopShortcut.Broadcast, match(ctrl = true, shift = true, key = Key.B))
        assertNull(match(ctrl = true, key = Key.B)) // plain Ctrl+B belongs to the terminal
    }

    @Test
    fun `the snippet palette is on the app modifier plus S`() {
        assertEquals(DesktopShortcut.SnippetPalette, match(meta = true, key = Key.S))
        assertEquals(DesktopShortcut.SnippetPalette, match(ctrl = true, shift = true, key = Key.S))
        assertNull(match(ctrl = true, key = Key.S)) // plain Ctrl+S is the terminal's flow control
    }

    @Test
    fun `session recording is on the app modifier plus R`() {
        assertEquals(DesktopShortcut.ToggleRecording, match(meta = true, key = Key.R))
        assertEquals(DesktopShortcut.ToggleRecording, match(ctrl = true, shift = true, key = Key.R))
        // Plain Ctrl+R is reverse history search in the shell — it must not be stolen.
        assertNull(match(ctrl = true, key = Key.R))
    }

    @Test
    fun `the recording player is on the app modifier plus P`() {
        assertEquals(DesktopShortcut.PlayRecording, match(meta = true, key = Key.P))
        assertEquals(DesktopShortcut.PlayRecording, match(ctrl = true, shift = true, key = Key.P))
        assertNull(match(ctrl = true, key = Key.P))
    }

    @Test
    fun `find in terminal output is on the app modifier plus F`() {
        // The chord every terminal binds to "find" (GNOME Terminal, Windows Terminal); SFTP moved to E.
        assertEquals(DesktopShortcut.FindInTerminal, match(meta = true, key = Key.F))
        assertEquals(DesktopShortcut.FindInTerminal, match(ctrl = true, shift = true, key = Key.F))
        // Plain Ctrl+F is readline's forward-char and belongs to the shell.
        assertNull(match(ctrl = true, key = Key.F))
    }

    @Test
    fun `the app modifier plus an arrow moves focus between panes`() {
        assertEquals(DesktopShortcut.FocusPane(PaneDirection.Left), match(meta = true, key = Key.DirectionLeft))
        assertEquals(DesktopShortcut.FocusPane(PaneDirection.Right), match(ctrl = true, shift = true, key = Key.DirectionRight))
        assertEquals(DesktopShortcut.FocusPane(PaneDirection.Up), match(meta = true, key = Key.DirectionUp))
        assertEquals(DesktopShortcut.FocusPane(PaneDirection.Down), match(ctrl = true, shift = true, key = Key.DirectionDown))
    }

    @Test
    fun `a bare or terminal-bound arrow is left alone`() {
        // Arrows are the shell's history and cursor keys, and Ctrl/Shift+arrow are word-wise
        // movement and selection — only the app modifier claims them.
        assertNull(match(key = Key.DirectionLeft))
        assertNull(match(ctrl = true, key = Key.DirectionLeft))
        assertNull(match(shift = true, key = Key.DirectionRight))
        assertNull(match(alt = true, key = Key.DirectionUp))
    }

    @Test
    fun `the pane grid acts on the arrow chord and on nothing else`() {
        assertEquals(PaneDirection.Up, paneGridDirection(DesktopShortcut.FocusPane(PaneDirection.Up), searchOpen = false))
        assertNull(paneGridDirection(DesktopShortcut.AddPane, searchOpen = false))
        assertNull(paneGridDirection(null, searchOpen = false))
    }

    @Test
    fun `an open find bar keeps the chord for its own field`() {
        // Ctrl+Shift+arrow selects by word in the search field — the grid must not steal it there.
        assertNull(paneGridDirection(DesktopShortcut.FocusPane(PaneDirection.Left), searchOpen = true))
    }

    @Test
    fun `app modifier on macOS is Cmd alone`() {
        assertEquals(DesktopShortcut.NewConnection, match(meta = true, key = Key.N))
        assertEquals(DesktopShortcut.AddPane, match(meta = true, key = Key.D))
        assertEquals(DesktopShortcut.SyncPanes, match(meta = true, key = Key.I))
        assertEquals(DesktopShortcut.OpenSftp, match(meta = true, key = Key.E))
        assertEquals(DesktopShortcut.Lock, match(meta = true, key = Key.L))
        assertEquals(DesktopShortcut.OpenAssistant, match(meta = true, key = Key.Slash))
    }

    @Test
    fun `app modifier off macOS is Ctrl plus Shift`() {
        assertEquals(DesktopShortcut.NewConnection, match(ctrl = true, shift = true, key = Key.N))
        assertEquals(DesktopShortcut.AddPane, match(ctrl = true, shift = true, key = Key.D))
        assertEquals(DesktopShortcut.SyncPanes, match(ctrl = true, shift = true, key = Key.I))
        assertEquals(DesktopShortcut.OpenSftp, match(ctrl = true, shift = true, key = Key.E))
        assertEquals(DesktopShortcut.Lock, match(ctrl = true, shift = true, key = Key.L))
        assertEquals(DesktopShortcut.OpenAssistant, match(ctrl = true, shift = true, key = Key.Slash))
    }

    @Test
    fun `plain Ctrl plus letter is left to the terminal`() {
        // Ctrl+L clear screen, Ctrl+D EOF, Ctrl+N — must not be intercepted.
        assertNull(match(ctrl = true, key = Key.L))
        assertNull(match(ctrl = true, key = Key.D))
        assertNull(match(ctrl = true, key = Key.N))
    }

    @Test
    fun `plain Alt plus letter (terminal Meta prefix) is not an app shortcut`() {
        assertNull(match(alt = true, key = Key.N))
        assertNull(match(alt = true, key = Key.D))
    }

    @Test
    fun `unmodified letters and digits are ignored`() {
        assertNull(match(key = Key.N))
        assertNull(match(key = Key.One))
        assertNull(match(shift = true, key = Key.D))
    }

    @Test
    fun `a formatted chord is recognized as reserved by the shell`() {
        // What the snippet editor stores, matched back against the shell's own shortcuts.
        assertEquals(DesktopShortcut.ToggleRecording, matchDesktopShortcut("Ctrl+Shift+R"))
        assertEquals(DesktopShortcut.SnippetPalette, matchDesktopShortcut("Meta+S"))
        assertEquals(DesktopShortcut.SelectTab(0), matchDesktopShortcut("Alt+1"))
    }

    @Test
    fun `a free chord is not reserved`() {
        assertNull(matchDesktopShortcut("Ctrl+Shift+X"))
        assertNull(matchDesktopShortcut("Ctrl+G"))
        assertNull(matchDesktopShortcut("nonsense"))
    }

    /**
     * A modal owns the keyboard, and this handler runs above the focus its field takes — so a chord
     * typed into a connect password must not act on the session waiting underneath. Locking is the
     * exception: it tears that session down, so nothing is left to type into.
     */
    @Test
    fun only_the_vault_lock_survives_an_open_modal() {
        assertTrue(survivesModal(DesktopShortcut.Lock))
        for (other in listOf(
            DesktopShortcut.NewConnection,
            DesktopShortcut.CommandPalette,
            DesktopShortcut.SnippetPalette,
            DesktopShortcut.Broadcast,
            DesktopShortcut.OpenSftp,
            DesktopShortcut.FindInTerminal,
            DesktopShortcut.NextTab,
            DesktopShortcut.SelectTab(0),
        )) {
            assertFalse(survivesModal(other), "$other must not act on the session under a modal")
        }
        assertFalse(survivesModal(null), "an unmatched chord runs nothing")
    }

    /**
     * The add-pane chord is the one refusal in this file that fires over a live terminal, so it
     * stays consumed: Ctrl+Shift+D let through is EOT on the wire, and the shell it reaches exits.
     */
    @Test
    fun `Add pane is consumed even once the tab cannot take another`() = runTest {
        val (sessions, scope) = sessions()
        sessions.open(hostId = "h", title = "h", subtitle = "u@h:22", target = testTarget, auth = auth)
        advanceUntilIdle()
        val state = DesktopDesignState()
        repeat(MAX_PANES - 1) {
            assertTrue(runDesktopShortcut(DesktopShortcut.AddPane, state, sessions) {}, "pane ${it + 2} of $MAX_PANES")
        }
        assertTrue(
            runDesktopShortcut(DesktopShortcut.AddPane, state, sessions) {},
            "a full tab has nowhere to put a pane, but the chord must not reach the shell as EOT",
        )
        scope.cancel()
    }

    /**
     * The chords that nudge a toolbar button are worth no more than the button: with nothing
     * connected the button refuses, so the chord has to fall through rather than be spent on a
     * request nobody acts on. Playback is the exception — it opens a file, not a session.
     */
    @Test
    fun `the session chords fall through with nothing connected`() = runTest {
        val (sessions, scope) = sessions()
        val state = DesktopDesignState()
        for (chord in listOf(DesktopShortcut.SnippetPalette, DesktopShortcut.ToggleRecording, DesktopShortcut.CommandPalette)) {
            assertFalse(runDesktopShortcut(chord, state, sessions) {}, "$chord must fall through with no session")
        }
        assertTrue(runDesktopShortcut(DesktopShortcut.PlayRecording, state, sessions) {}, "playback needs no session")

        sessions.open(hostId = "h", title = "h", subtitle = "u@h:22", target = testTarget, auth = auth)
        advanceUntilIdle()
        // The half that keeps the gate honest: consuming the chord and doing nothing would satisfy
        // the refusals above and the two "consumed and nothing more" tests both.
        val asked = recordRequests(state)
        for (chord in listOf(DesktopShortcut.SnippetPalette, DesktopShortcut.ToggleRecording, DesktopShortcut.CommandPalette)) {
            assertTrue(runDesktopShortcut(chord, state, sessions) {}, "$chord acts on a connected session")
        }
        advanceUntilIdle()
        assertEquals(listOf("snippets", "record"), asked, "both buttons are nudged")
        assertTrue(state.commandPaletteOpen, "the palette has a terminal to insert into")
        scope.cancel()
    }

    /**
     * The palette fills the command line of the pane behind it, so it brings that view forward
     * first — pressed over the file panel it would otherwise write into a terminal nobody is
     * looking at, and the command would be found half-typed on the next switch back. Find and the
     * assistant chord already do this; the palette was the one that did not.
     */
    @Test
    fun `the command palette brings the terminal view forward`() = runTest {
        val (sessions, scope) = sessions()
        sessions.open(hostId = "h", title = "h", subtitle = "u@h:22", target = testTarget, auth = auth)
        advanceUntilIdle()
        sessions.setActiveView(SessionView.Sftp)
        val state = DesktopDesignState()
        assertTrue(runDesktopShortcut(DesktopShortcut.CommandPalette, state, sessions) {})
        assertEquals(SessionView.Terminal, sessions.active?.view, "the palette writes into the terminal")
        assertTrue(state.commandPaletteOpen)
        scope.cancel()
    }

    /**
     * Mock mode — no session manager at all, which is how the offscreen screenshot pipeline runs
     * the app. Nothing is connected there, so nothing is nudged; the chord is consumed all the
     * same, because that shell draws a terminal too and a fall-through would type Ctrl+Shift+S into
     * it as XOFF.
     */
    @Test
    fun `the session chords are consumed with no session manager`() = runTest {
        val state = DesktopDesignState()
        val asked = recordRequests(state)
        for (chord in listOf(DesktopShortcut.SnippetPalette, DesktopShortcut.ToggleRecording, DesktopShortcut.CommandPalette)) {
            assertTrue(runDesktopShortcut(chord, state, sessions = null) {}, "$chord in mock mode")
        }
        advanceUntilIdle()
        assertFalse(state.commandPaletteOpen, "there is no pane for the palette to insert into")
        assertEquals(emptyList(), asked, "no button to nudge, so nothing is asked for")
    }

    /**
     * A remote desktop has no terminal for the chord to act on — and the framebuffer under it holds
     * the keyboard, so letting the chord through does not lose it, it types it into the guest.
     *
     * Consumed and nothing more: swallowing the key is the whole job here. Opening a panel that
     * cannot reach a terminal would trade a keystroke in the guest for an overlay whose every row
     * is a no-op, which is the worse half of both.
     */
    @Test
    fun `the session chords are consumed over a remote desktop`() = runTest {
        val (sessions, scope) = sessions(vnc = FakeVncTransport())
        sessions.openVnc(hostId = "h", title = "h", subtitle = "h:5900", target = testTarget, auth = VncAuth.None)
        advanceUntilIdle()
        val state = DesktopDesignState()
        val asked = recordRequests(state)
        for (chord in listOf(DesktopShortcut.SnippetPalette, DesktopShortcut.ToggleRecording, DesktopShortcut.CommandPalette)) {
            assertTrue(runDesktopShortcut(chord, state, sessions) {}, "$chord must not reach the guest")
        }
        advanceUntilIdle()
        assertFalse(state.commandPaletteOpen, "the command palette has no terminal to insert into here")
        assertEquals(emptyList(), asked, "no toolbar button is composed over a desktop tab")
        assertTrue(
            runDesktopShortcut(DesktopShortcut.FindInTerminal, state, sessions) {},
            "find must not reach the guest either",
        )
        scope.cancel()
    }

    private val auth = SshAuth.Password("pw")

    /**
     * Both button-nudging request flows, in the order they fire. `replay = 0`, so the collectors
     * have to be live before the chord runs — hence the unconfined dispatcher.
     */
    private fun TestScope.recordRequests(state: DesktopDesignState): List<String> {
        val asked = mutableListOf<String>()
        val live = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(live) { state.snippetPaletteRequests.collect { asked += "snippets" } }
        backgroundScope.launch(live) { state.recordingToggleRequests.collect { asked += "record" } }
        return asked
    }

    private fun TestScope.sessions(vnc: FakeVncTransport? = null): Pair<SessionsController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var n = 0
        val controller = SessionsController(
            newId = { "s${n++}" },
            controllerFactory = {
                ConnectionController(
                    transport = FakeSshTransport(FakeSshConnection(FakeShellChannel())),
                    scope = scope,
                    newSessionScope = { CoroutineScope(UnconfinedTestDispatcher(testScheduler)) },
                )
            },
            vncControllerFactory = vnc?.let { { RemoteDesktopController(scope) } },
            openVncSession = vnc?.let { t -> { target, auth -> VncRemoteDesktop(t.connect(target, auth)) } },
        )
        return controller to scope
    }
}
