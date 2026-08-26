package app.skerry.ui.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.terminal.Asciicast
import app.skerry.ui.app.UiTags
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.session.SessionView
import app.skerry.ui.snippet.SnippetDraft
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_view_terminal
import app.skerry.ui.terminal.CastOpenResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test

/**
 * Keyboard shortcuts, pressed against the running shell.
 *
 * `matchDesktopShortcut` is a pure function with its own test — which chord means what. This is the
 * half above it: that the chord reaches the handler and the handler does the thing. A shortcut that
 * matches correctly and is wired to nothing looks exactly like a working one from the unit test's
 * side, and like a dead key from the user's.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutWiringTest {

    @Test
    fun `the new-connection chord opens the form`() = runDesktopShell {
        onNodeWithTag(UiTags.FORM_SAVE).assertDoesNotExist()
        chord(Key.N)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsDisplayed()
    }

    /** Ctrl+Shift+K raises the command palette over the active session. */
    @Test
    fun `the palette chord opens the command palette`() = runDesktopShell { shell ->
        chord(Key.K)
        kotlin.test.assertTrue(shell.state.commandPaletteOpen, "the chord did not reach the palette")
    }

    @Test
    fun `the broadcast chord opens the broadcast panel`() = runDesktopShell { shell ->
        chord(Key.B)
        kotlin.test.assertTrue(shell.state.broadcastOpen, "the chord did not reach the broadcast panel")
    }

    /**
     * The palette, the recorder and the player hang off toolbar buttons that only the terminal view
     * draws. Pressed with the tab showing the file panel, the chord used to be spent on a request
     * the parked toolbar never received; it has to bring that view forward and open the palette.
     */
    @Test
    fun `the snippet chord opens the palette over the file panel`() = runDesktopShell { shell ->
        shell.snippets.save(SnippetDraft(label = "Rollout", command = "uptime"))
        shell.sessions?.setActiveView(SessionView.Sftp)
        waitForIdle()

        chord(Key.S)

        // The palette opens on the frame after the view swap, and the request the chord raised is
        // taken by an effect rather than by the click handler — so the assertion has to wait for it
        // instead of reading the tree the key event landed on.
        waitUntil("the snippet palette to open", timeoutMillis = 10_000) {
            onAllNodesWithText("Rollout").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Rollout").assertIsDisplayed()
    }

    /** The same for the recorder: the toggle is the terminal toolbar's, the chord is the shell's. */
    @Test
    fun `the record chord starts recording over the file panel`() = runDesktopShell { shell ->
        shell.sessions?.setActiveView(SessionView.Sftp)
        waitForIdle()

        chord(Key.R)

        waitUntil("the recording to start", timeoutMillis = 10_000) { shell.recording() }
        kotlin.test.assertEquals(SessionView.Terminal, shell.sessions?.active?.view)
    }

    /**
     * Playback is the one chord of the set that answers without a toolbar at all — the picker lives
     * in the window chrome. Deleting that driver leaves every other test green, so this is the only
     * thing holding it in place.
     *
     * The file is named with a right-to-left override, because a recording arrives from whoever made
     * it: the name becomes the tab label and with it the accessible name of that tab's close button,
     * so an unfiltered one makes a chip read as a different chip.
     */
    @Test
    fun `the playback chord opens a player tab over the file panel`() {
        val cast = Asciicast(80, 24, "deploy", emptyList())
        runDesktopShell(castPicker = { CastOpenResult.Loaded(cast, "report\u202Egpj.cast") }) { shell ->
            shell.sessions?.setActiveView(SessionView.Sftp)
            waitForIdle()

            chord(Key.P)

            waitUntil("the player tab to open", timeoutMillis = 10_000) {
                shell.sessions?.active?.isPlayer == true
            }
            val tab = shell.sessions?.active
            kotlin.test.assertEquals(SessionView.Player, tab?.view)
            kotlin.test.assertEquals("reportgpj.cast", tab?.focusedPane?.title)
            kotlin.test.assertEquals(cast, tab?.focusedPane?.playback?.cast)
        }
    }

    /**
     * The chord swaps what the work area shows without moving focus, so nothing tells a screen-reader
     * user that the next keystroke goes to the terminal rather than the file list (WCAG 4.1.3).
     */
    @Test
    fun `the view the chord brings forward is announced`() = runDesktopShell { shell ->
        shell.snippets.save(SnippetDraft(label = "Rollout", command = "uptime"))
        shell.sessions?.setActiveView(SessionView.Sftp)
        waitForIdle()
        val files = spokenViews()

        chord(Key.S)

        val terminal = runBlocking { getString(Res.string.shell_view_terminal) }
        waitUntil("the work area to report the terminal", timeoutMillis = 10_000) {
            terminal in spokenViews()
        }
        // The name, not merely a non-blank one: a mis-mapped `when` arm announces "Monitoring" for the
        // terminal, and a live region carrying the wrong room is worse than one carrying none.
        kotlin.test.assertTrue(terminal !in files, "the terminal was already being announced")
    }

    /**
     * A recording is watched, not run, so the picker is up before any tab is involved — and a second
     * native file dialog on top of the first hangs the app. The chord has to refuse while one is open.
     */
    @Test
    fun `a second playback chord is refused while the picker is up`() {
        val held = CompletableDeferred<CastOpenResult>()
        var picks = 0
        runDesktopShell(castPicker = { picks++; held.await() }) { shell ->
            chord(Key.P)
            waitUntil("the picker to go up", timeoutMillis = 10_000) { shell.state.castOpening }

            chord(Key.P)
            waitForIdle()

            kotlin.test.assertEquals(1, picks, "a second file dialog was opened on top of the first")
            held.complete(CastOpenResult.Cancelled)
            waitUntil("the picker to close", timeoutMillis = 10_000) { !shell.state.castOpening }
        }
    }

    /**
     * The assistant panel is the only reader of the focus request, and it is not composed at all when
     * AI is off for this host. An ask left pending there would surface on some later tab whose host has
     * AI on, stealing the caret from the shell the user just switched to.
     */
    @Test
    fun `an assistant ask nobody can take is dropped rather than carried to the next tab`() =
        runDesktopShell { shell ->
            shell.ai.selectProvider(AiProviderKind.OFF)
            waitForIdle()

            chord(Key.Slash)

            waitUntil("the ask to be dropped", timeoutMillis = 10_000) { !shell.state.assistantFocusPending }
        }

    /** Every polite live region's text — the work area's is one of several the shell composes. */
    private fun androidx.compose.ui.test.ComposeUiTest.spokenViews(): List<String> =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    /** Whether the focused pane's terminal is recording — the state the record toggle flips. */
    private fun DesktopShell.recording(): Boolean {
        val state = sessions?.active?.focusedPane?.controller?.uiState
        return (state as? ConnectionUiState.Connected)?.terminal?.recording == true
    }

    /** Ctrl+Shift+<letter> — what `matchDesktopShortcut` accepts on every platform, macOS included. */
    private fun androidx.compose.ui.test.ComposeUiTest.chord(key: Key) {
        onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(key) } }
        }
        waitForIdle()
    }
}
