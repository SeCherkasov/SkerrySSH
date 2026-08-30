package app.skerry.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_key_armed
import app.skerry.ui.generated.resources.term_key_assistant
import app.skerry.ui.generated.resources.term_key_cycle_suggestion
import app.skerry.ui.generated.resources.term_key_find_output
import app.skerry.ui.generated.resources.term_key_hide_keyboard
import app.skerry.ui.generated.resources.term_key_insert
import app.skerry.ui.generated.resources.term_key_open
import app.skerry.ui.generated.resources.term_key_right
import app.skerry.ui.generated.resources.term_key_search_history
import app.skerry.ui.generated.resources.term_key_search_newer
import app.skerry.ui.generated.resources.term_key_search_older
import app.skerry.ui.generated.resources.term_key_search_remove
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.terminal.eagerPublishClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The key panel on screen: both rows are reachable without a swipe, the function layer replaces them
 * in place, an armed modifier reaches the PTY inside the sequence, and a printable key still goes
 * through the path the production guard watches. The encoding itself is [MobileKeybarLayoutTest]'s
 * subject — what is checked here is that a tap on a drawn cap produces it.
 */
@OptIn(ExperimentalTestApi::class)
class MobileKeybarUiTest {

    private val esc = 27.toChar()

    /**
     * The panel over a terminal whose PTY writes are recorded. The scope is Unconfined so
     * `session.send` runs inline on the test thread — a pool dispatcher would append to [sent] while
     * the assertions read it — and is cancelled at the end so the emulator loops do not outlive the
     * test.
     */
    private fun panel(
        history: List<String> = emptyList(),
        aiOpen: Boolean = false,
        onToggleAi: (() -> Unit)? = null,
        onMeasure: ((Int) -> Unit)? = null,
        body: ComposeUiTest.(TerminalScreenState, StringBuilder) -> Unit,
    ) {
        val sent = StringBuilder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = object : TerminalSession {
            override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
            override val output: Flow<ByteArray> = emptyFlow()
            override suspend fun send(data: ByteArray) {
                sent.append(data.decodeToString())
            }

            override suspend fun resize(size: PtySize) {}
            override suspend fun close() {}
        }
        val terminal = TerminalScreenState(session, scope, initialHistory = history, nowMillis = eagerPublishClock())
        try {
            // A narrow phone width, so a cap that cannot fit nine to a row shows up here.
            runForm({
                val modifiers = remember { StickyModifiers() }
                // The Box wraps the panel, so its measured height is the panel's own.
                val measured = if (onMeasure != null) Modifier.onGloballyPositioned { onMeasure(it.size.height) } else Modifier
                Box(Modifier.width(360.dp).then(measured)) {
                    MobileKeybar(terminal, modifiers, aiOpen = aiOpen, onToggleAi = onToggleAi)
                }
            }) { body(terminal, sent) }
        } finally {
            scope.cancel()
        }
    }

    /** Writes from the test thread reach the composition only inside a mutable snapshot. */
    private fun edit(block: () -> Unit) = Snapshot.withMutableSnapshot(block)

    @Test
    fun `both rows are on screen and navigation keys send their sequences`() {
        panel { _, sent ->
            // Second row keys are drawn, not parked behind a horizontal scroll.
            for (label in listOf("esc", "tab", "ctrl", "alt", "fn", "home", "end", "pgup", "pgdn", "~")) {
                onNodeWithText(label).assertExists()
            }
            onNodeWithContentDescription(string(Res.string.term_key_hide_keyboard)).assertExists()
            onNodeWithText("home").performClick()
            assertEquals("$esc[H", sent.toString())
        }
    }

    @Test
    fun `an armed modifier is carried into the sequence and spent by the key it modifies`() {
        panel { _, sent ->
            onNodeWithText("ctrl").performClick()
            onNodeWithContentDescription(string(Res.string.term_key_right)).performClick()
            assertEquals("$esc[1;5C", sent.toString()) // ctrl+→ — a word jump, not a bare arrow
            // Sticky: one keystroke. The next arrow is plain.
            onNodeWithContentDescription(string(Res.string.term_key_right)).performClick()
            assertEquals("$esc[1;5C$esc[C", sent.toString())
        }
    }

    @Test
    fun `the fn key swaps the layer in place`() {
        panel { _, sent ->
            onNodeWithText("f5").assertDoesNotExist()
            onNodeWithText("fn").performClick()
            onNodeWithText("f5").assertExists()
            // The base layer is gone rather than pushed off screen — the panel keeps its height.
            onNodeWithText("home").assertDoesNotExist()
            onNodeWithText("f5").performClick()
            assertEquals("$esc[15~", sent.toString())
            onNodeWithText("fn").performClick()
            onNodeWithText("home").assertExists()
        }
    }

    @Test
    fun `a symbol key goes through the typed path the production guard watches`() {
        panel { terminal, sent ->
            edit {
                terminal.guardPolicy = ProductionGuardPolicy(production = true)
                terminal.typeInput("reboot")
            }
            // ctrl+"-" is CR: it runs the line. Only the typed path reaches the guard — sent as a raw
            // control sequence it would run `reboot` on a production host with no confirmation.
            onNodeWithText("ctrl").performClick()
            onNodeWithText("-").performClick()
            waitForIdle()
            assertNotNull(terminal.pendingGuarded, "the guard did not see the line the panel ran")
            assertEquals("reboot", sent.toString(), "the held CR must not reach the PTY")
        }
    }

    @Test
    fun `tab accepts a pending suggestion instead of sending a tab`() {
        panel(history = listOf("reboot")) { terminal, sent ->
            // Typing the head of a remembered command is what raises the ghost suggestion; the panel
            // reads `hasSuggestion`, which nothing but the engine may set.
            edit { terminal.typeInput("re") }
            waitUntil("the suggestion is raised") { terminal.hasSuggestion }
            onNodeWithText("tab").performClick()
            waitForIdle()
            // The accepted tail, not a tab byte: HT here would have opened shell completion instead.
            assertEquals("reboot", sent.toString())
        }
    }

    @Test
    fun `the reverse-search layer replaces the grid and its keys drive the search`() {
        panel { terminal, _ ->
            edit { terminal.reverseSearch.open() }
            waitForIdle()
            onNodeWithText("home").assertDoesNotExist()
            onNodeWithContentDescription(string(Res.string.term_key_insert)).assertExists()
            onNodeWithText("esc").performClick()
            waitForIdle()
            assertNull(terminal.reverseSearch.query, "esc must close the search")
            onNodeWithText("home").assertExists()
        }
    }

    @Test
    fun `the history key opens the search and leaves the fn layer behind`() {
        panel { terminal, _ ->
            onNodeWithText("fn").performClick()
            onNodeWithContentDescription(string(Res.string.term_key_search_history)).performClick()
            waitForIdle()
            assertNotNull(terminal.reverseSearch.query)
            edit { terminal.reverseSearch.close() }
            waitForIdle()
            // Back on the base layer: the search was opened from fn, but it is not where the user
            // returns to.
            onNodeWithText("home").assertExists()
            onNodeWithText("f5").assertDoesNotExist()
        }
    }

    @Test
    fun `caps announce themselves as buttons, and an armed modifier as a state`() {
        panel { _, _ ->
            val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            onNodeWithText("home").assert(button)
            onNodeWithContentDescription(string(Res.string.term_key_right)).assert(button)
            onNodeWithText("ctrl").performClick()
            onNodeWithText("ctrl")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(Res.string.term_key_armed)))
        }
    }

    @Test
    fun `a key whose dependency is missing is disabled, not dead`() {
        panel { _, _ ->
            onNodeWithText("fn").performClick()
            // No assistant controller was passed and nothing is being suggested: both caps refuse the
            // tap instead of looking live and swallowing it.
            onNodeWithContentDescription(string(Res.string.term_key_assistant)).assertIsNotEnabled()
            onNodeWithContentDescription(string(Res.string.term_key_cycle_suggestion)).assertIsNotEnabled()
        }
    }

    /**
     * The sparkle key is the assistant's only entry point inside a session, and this change moved it
     * one layer down — a branch that no longer reached the controller would leave it unreachable on a
     * phone. The open state is spoken, not left to the cyan tint alone.
     */
    @Test
    fun `the assistant key reaches its controller and announces whether the bar is open`() {
        var toggles = 0
        panel(aiOpen = true, onToggleAi = { toggles++ }) { _, _ ->
            onNodeWithText("fn").performClick()
            onNodeWithContentDescription(string(Res.string.term_key_assistant))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(Res.string.term_key_open)))
            onNodeWithContentDescription(string(Res.string.term_key_assistant)).performClick()
            assertEquals(1, toggles, "the sparkle key did not reach the assistant controller")
        }
        panel(aiOpen = false, onToggleAi = { toggles++ }) { _, _ ->
            onNodeWithText("fn").performClick()
            // Closed: the same cap must not claim the bar is up.
            onNodeWithContentDescription(string(Res.string.term_key_assistant))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        }
    }

    @Test
    fun `the find key opens the output search and leaves the fn layer behind`() {
        panel { terminal, _ ->
            onNodeWithText("fn").performClick()
            onNodeWithContentDescription(string(Res.string.term_key_find_output)).performClick()
            waitForIdle()
            assertNotNull(terminal.search.query, "the find key did not open the output search")
            edit { terminal.search.close() }
            waitForIdle()
            onNodeWithText("home").assertExists()
            onNodeWithText("f5").assertDoesNotExist()
        }
    }

    /**
     * The cycled candidate is observed through what Tab then accepts, not through `suggestionTail`:
     * the ghost is only drawn once the shell has echoed the line, and this fake PTY echoes nothing.
     */
    @Test
    fun `the cycle key walks to the other suggestion`() {
        val history = listOf("restart", "reboot")
        var withoutCycling = ""
        panel(history = history) { terminal, sent ->
            edit { terminal.typeInput("re") }
            waitUntil("the suggestion is raised") { terminal.hasSuggestion }
            onNodeWithText("tab").performClick()
            waitForIdle()
            withoutCycling = sent.toString()
        }
        panel(history = history) { terminal, sent ->
            edit { terminal.typeInput("re") }
            waitUntil("the suggestion is raised") { terminal.hasSuggestion }
            onNodeWithText("fn").performClick()
            onNodeWithContentDescription(string(Res.string.term_key_cycle_suggestion)).performClick()
            onNodeWithText("fn").performClick()
            onNodeWithText("tab").performClick()
            waitForIdle()
            assertNotEquals(withoutCycling, sent.toString(), "the cycle key did not move to the other match")
        }
    }

    /**
     * Each cap of the search layer against its own step. The picker's methods have their own tests;
     * what is unverified without this is the wiring — swapping older and newer is a one-character
     * diff that every other test survives.
     */
    @Test
    fun `each reverse-search key drives its own step`() {
        panel(history = listOf("deploy", "df -h", "docker ps")) { terminal, sent ->
            // A blank query matches nothing by design, so the layer is driven with a query typed
            // on the soft keyboard, as it would be in a session.
            edit { terminal.reverseSearch.open(); terminal.reverseSearch.append("d") }
            waitForIdle()
            val newest = assertNotNull(terminal.reverseSearch.selection)

            onNodeWithContentDescription(string(Res.string.term_key_search_older)).performClick()
            waitForIdle()
            assertNotEquals(newest, terminal.reverseSearch.selection, "older must step down the matches")

            onNodeWithContentDescription(string(Res.string.term_key_search_newer)).performClick()
            waitForIdle()
            assertEquals(newest, terminal.reverseSearch.selection, "newer must step back to where it started")

            onNodeWithContentDescription(string(Res.string.term_key_search_remove)).performClick()
            waitForIdle()
            assertFalse(newest in terminal.reverseSearch.results, "delete must drop the match from history")
            assertNotNull(terminal.reverseSearch.query, "delete leaves the search open to pick again")

            val picked = assertNotNull(terminal.reverseSearch.selection)
            onNodeWithContentDescription(string(Res.string.term_key_insert)).performClick()
            waitForIdle()
            assertNull(terminal.reverseSearch.query, "insert must close the search")
            assertTrue(sent.toString().endsWith(picked), "insert must put the match on the shell line")
        }
    }

    /**
     * The terminal is this panel's sibling under a weight, and it resizes the PTY when its viewport
     * settles. A search layer shorter than the grid would hand the host a SIGWINCH and make bash
     * redraw the very prompt being read.
     */
    @Test
    fun `the search layer keeps the panel height so the terminal is not resized`() {
        val heights = mutableListOf<Int>()
        panel(onMeasure = { heights += it }) { terminal, _ ->
            waitForIdle()
            val grid = heights.last()
            edit { terminal.reverseSearch.open() }
            waitForIdle()
            assertEquals(grid, heights.last(), "opening the search resized the panel, and the PTY under it")
        }
    }

    @Test
    fun `the keyboard key is disabled where no soft keyboard can be hidden`() {
        val sent = StringBuilder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = object : TerminalSession {
            override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
            override val output: Flow<ByteArray> = emptyFlow()
            override suspend fun send(data: ByteArray) {
                sent.append(data.decodeToString())
            }

            override suspend fun resize(size: PtySize) {}
            override suspend fun close() {}
        }
        val terminal = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            runForm({
                val modifiers = remember { StickyModifiers() }
                // A composition with no keyboard controller — the platform state the key depends on.
                CompositionLocalProvider(LocalSoftwareKeyboardController provides null) {
                    Box(Modifier.width(360.dp)) { MobileKeybar(terminal, modifiers) }
                }
            }) {
                onNodeWithContentDescription(string(Res.string.term_key_hide_keyboard)).assertIsNotEnabled()
            }
        } finally {
            scope.cancel()
        }
    }
}
