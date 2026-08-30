package app.skerry.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skerry.ui.AppDependencies
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_files
import app.skerry.ui.generated.resources.shell_tip_record
import app.skerry.ui.generated.resources.shell_tip_snippets
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalCastPicker
import app.skerry.ui.terminal.CastOpenResult
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.UiTags
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.mobile.MobileDesignApp
import app.skerry.ui.session.SessionsController
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetStore
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStore
import app.skerry.ui.runbook.RunbookManager
import app.skerry.ui.runbook.RunbookRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import app.skerry.ui.theme.SkerryTheme
import app.skerry.shared.vault.BouncyCastleSshKeyGenerator
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * The real app shells, put on screen over the demo graph the offscreen renders already use
 * ([seededHosts] and friends), so a test can click its way through them.
 *
 * Both shells run on the desktop JVM. That is not a desktop-only decision: the mobile shell is
 * common code, and driving it here costs an emulator boot less than the Android instrumentation it
 * would otherwise need. What is genuinely platform-specific about Android — insets, the back
 * gesture's own handler — is out of reach this way and stays out of scope.
 */

/**
 * The libraries the shell was seeded with, in one handle. Grouped rather than listed one by one:
 * the shell keeps growing sections, and each of them arrives as another manager.
 */
internal class ShellLibraries(
    val tunnels: TunnelManager,
    val snippets: SnippetManager,
    val runbooks: RunbookManager,
    /** The vault's keychain — what a host's `credentialId` points into. */
    val credentials: CredentialManagerController,
    /** The AI controller the settings tab writes into when its Save button is pressed. */
    val ai: AiAssistantController,
)

/** What a desktop test can reach behind the UI, to check what a click actually changed. */
internal class DesktopShell(
    val hosts: HostManagerController,
    val sessions: SessionsController?,
    val libraries: ShellLibraries,
    /** The one in-flight run, for the tests that press Run rather than call it. */
    val runner: RunbookRunner,
    /** The shell's own state, for opening what only a menu deep in the UI would otherwise reach. */
    val state: DesktopDesignState,
    /**
     * The composition's focus manager, for the tests that have to do to focus what the scene does
     * on a window focus loss (`clearFocus(force = true)`). Null until the first frame.
     */
    val focus: () -> FocusManager?,
) {
    val tunnels: TunnelManager get() = libraries.tunnels
    val snippets: SnippetManager get() = libraries.snippets
    val runbooks: RunbookManager get() = libraries.runbooks
    val credentials: CredentialManagerController get() = libraries.credentials
    val ai: AiAssistantController get() = libraries.ai
}

/** Snippet library over memory — the library screen needs a manager to save into. */
internal fun seededSnippets(): SnippetManager {
    val store = object : SnippetStore {
        private val entries = mutableListOf<Snippet>()
        override fun all(): List<Snippet> = entries.toList()
        override fun put(snippet: Snippet) {
            val i = entries.indexOfFirst { it.id == snippet.id }
            if (i >= 0) entries[i] = snippet else entries += snippet
        }
        override fun remove(id: String) { entries.removeAll { it.id == id } }
        override fun reorder(transform: (List<Snippet>) -> List<Snippet>) {
            val updated = transform(entries.toList())
            entries.clear()
            entries.addAll(updated)
        }
    }
    var seq = 0
    return SnippetManager(store) { "snip-${seq++}" }
}

/** Runbook library over memory, for the same reason as [seededSnippets]. */
internal fun seededRunbooks(): RunbookManager {
    val store = object : RunbookStore {
        private val entries = mutableListOf<Runbook>()
        override fun all(): List<Runbook> = entries.toList()
        override fun put(runbook: Runbook) {
            val i = entries.indexOfFirst { it.id == runbook.id }
            if (i >= 0) entries[i] = runbook else entries += runbook
        }
        override fun remove(id: String) { entries.removeAll { it.id == id } }
        override fun reorder(transform: (List<Runbook>) -> List<Runbook>) {
            val updated = transform(entries.toList())
            entries.clear()
            entries.addAll(updated)
        }
    }
    var seq = 0
    return RunbookManager(store) { "run-${seq++}" }
}

/** The window every shell test gets unless it asks for another — `runComposeUiTest`'s own default. */
internal const val DEFAULT_SHELL_WIDTH = 1024

/** Desktop shell over the seeded catalog. [withSessions] opens two demo tabs over a fake transport. */
@OptIn(ExperimentalTestApi::class)
internal fun runDesktopShell(
    withSessions: Boolean = true,
    // Width of the test window. The toolbar collapses its actions into an overflow menu once they
    // no longer fit beside the work bar's title, and that menu is a second rendering of the same
    // actions — reachable in a test only by making the window too small for the row.
    windowWidth: Int = DEFAULT_SHELL_WIDTH,
    // Custom window chrome, for the tests that drive the titlebar's window gestures. null (the
    // default) renders the decorated-window titlebar, like the offscreen previews.
    windowChrome: WindowChrome? = null,
    // The window's own focus, for the tests about who owns the keyboard when it comes and goes. The
    // test scene's own [WindowInfo] is hardcoded focused, so there is no other way to drive it.
    windowInfo: WindowInfo? = null,
    // What "Play a recording" answers with. null leaves the real file dialog in place, which no test
    // can drive — a test that exercises playback has to supply its own.
    castPicker: (suspend () -> CastOpenResult)? = null,
    body: ComposeUiTest.(DesktopShell) -> Unit,
) = runDesktopComposeUiTest(width = windowWidth) {
    val keyGenerator = BouncyCastleSshKeyGenerator()
    val credentials = seededVault(keyGenerator)
    val hosts = seededHosts(boundCredentialId = credentials.credentials.firstOrNull()?.id)
    // One scope for everything this shell starts, so the whole run ends with the test. The seeds
    // default to a scope of their own, which is right for a one-frame offscreen render and wrong
    // here: a suite builds a shell per test, and the tunnel manager's telemetry poll would keep
    // waking every second for a composition that is long gone.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessions = if (withSessions) seededSessions(hosts, scope) else null
    val tunnels = seededTunnels(hosts, scope)
    val ai = seededAi(scope)
    val snippets = seededSnippets()
    val runbooks = seededRunbooks()
    var runSeq = 0
    val runner = RunbookRunner(scope = scope, newId = { "run-exec-${runSeq++}" })
    val state = DesktopDesignState()
    var focusManager: FocusManager? = null
    FakeShellInput.clear()
    try {
        setContent {
            CaptureFocusManager { focusManager = it }
            WithWindowInfo(windowInfo) {
            WithCastPicker(castPicker) {
            SkerryTheme {
                // A live AI controller only so the settings panel offers its AI tab: without one the
                // tab is hidden, and a walk over SETTINGS_NAV would silently skip an entry.
                WithTestLifecycle {
                    DesktopDesignApp(
                        state = state,
                        hosts = hosts,
                        sessions = sessions,
                        credentials = credentials,
                        keyGenerator = keyGenerator,
                        tunnels = tunnels,
                        snippets = snippets,
                        runbooks = runbooks,
                        runbookRunner = runner,
                        ai = ai,
                        windowChrome = windowChrome,
                    )
                }
            }
            }
            }
        }
        waitForIdle()
        // The seeded session connects on the background scope, which waitForIdle does NOT wait
        // for. A shortcut sent before it lands falls through the root handler (no Connected
        // terminal to act on) and the test flakes only on a loaded runner — the CI shape of the
        // `escape closes the find bar` failure. Hand the body the shell every test assumes: an
        // active tab whose focused pane is connected.
        if (sessions != null) {
            waitUntil("seeded session reaches Connected", timeoutMillis = 10_000) {
                val s = sessions.active?.focusedPane?.controller?.uiState
                // A definitive failure must not burn the whole timeout and then report a generic
                // "condition not satisfied" — the state carries the actual diagnosis.
                if (s is ConnectionUiState.Error) error("seeded session failed to connect: ${s.message}")
                s is ConnectionUiState.Connected
            }
        }
        val libraries = ShellLibraries(tunnels, snippets, runbooks, credentials, ai)
        body(DesktopShell(hosts, sessions, libraries, runner, state) { focusManager })
    } finally {
        // Detaches the fake session's output collectors.
        sessions?.disconnectAll()
        // A run left in flight polls its terminal for marks that will never arrive.
        runner.close()
        scope.cancel()
        // A write already dispatched must not land in the next test's log.
        FakeShellInput.clear()
    }
}

/** Hands the composition's focus manager out to the test, once it exists. */
@Composable
private fun CaptureFocusManager(onManager: (FocusManager) -> Unit) {
    val manager = LocalFocusManager.current
    LaunchedEffect(manager) { onManager(manager) }
}

/** Runs [content] under [info], or under the scene's own window info when none is given. */
@Composable
internal fun WithWindowInfo(info: WindowInfo?, content: @Composable () -> Unit) {
    if (info == null) content() else CompositionLocalProvider(LocalWindowInfo provides info, content = content)
}

/** Runs [content] with [pick] answering "Play a recording", or with the real dialog when null. */
@Composable
private fun WithCastPicker(pick: (suspend () -> CastOpenResult)?, content: @Composable () -> Unit) {
    if (pick == null) content() else CompositionLocalProvider(LocalCastPicker provides pick, content = content)
}

/**
 * A window-lifecycle owner for the test composition, pinned to STARTED. The real shell gets one
 * from `Window`; `runComposeUiTest` provides none, and a connected remote desktop reads it
 * ([app.skerry.ui.remote.ReportOutputVisibility]) — without an owner the first VNC frame throws.
 * `createUnsafe` because there is no Android main thread to enforce here. Shared with the tests
 * that stand a single screen up rather than the whole shell ([app.skerry.ui.vault.VaultGate] reads
 * the owner too, for background auto-lock).
 */
@Composable
internal fun WithTestLifecycle(content: @Composable () -> Unit) {
    val owner = remember {
        object : LifecycleOwner {
            override val lifecycle: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        }.also { it.lifecycle.currentState = Lifecycle.State.STARTED }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

/** What a mobile test can reach behind the UI. */
internal class MobileShell(
    val hosts: HostManagerController,
    val state: MobileDesignState,
    val snippets: SnippetManager,
    val tunnels: TunnelManager,
    val runbooks: RunbookManager,
    val ai: AiAssistantController,
    /** Null unless the run asked for sessions — the sheets that outlive one are tested through it. */
    val sessions: SessionsController? = null,
)

/**
 * Mobile shell over the seeded catalog, in a phone-sized box (390x844dp, as the mobile screenshot
 * renders it) — clamped by the scene's own 1024x768, so the layout actually sees 390x768. The box
 * is only ever tighter than it asks for, which is the safe direction for a clipping test.
 */
@OptIn(ExperimentalTestApi::class)
internal fun runMobileShell(
    // Seeded sessions cost a fake transport per host, so screens that never look at a session keep
    // the cheap shell; the run paths (a snippet or a runbook needs a live terminal) ask for them.
    withSessions: Boolean = false,
    size: DpSize = DpSize(PHONE_WIDTH, PHONE_HEIGHT),
    /** System font scale, for the layouts that have to survive one: 1f is the phone's default. */
    fontScale: Float = 1f,
    body: ComposeUiTest.(MobileShell) -> Unit,
) = runComposeUiTest {
    val hosts = seededHosts()
    val state = MobileDesignState()
    val snippets = seededSnippets()
    // One scope for the shell's background work, cancelled below — see the note in [runDesktopShell].
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tunnels = seededTunnels(hosts, scope)
    val mobileRunbooks = seededRunbooks()
    val ai = seededAi(scope)
    val sessions = if (withSessions) seededSessions(hosts, scope) else null
    var runSeq = 0
    val runner = RunbookRunner(scope = scope, newId = { "run-exec-${runSeq++}" })
    FakeShellInput.clear()
    try {
        setContent {
            SkerryTheme {
                Box(Modifier.size(size)) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    ) {
                        // Same lifecycle owner as the desktop shell: the mobile VNC screen reads it
                        // too (MobileVncScreen -> ReportOutputVisibility).
                        WithTestLifecycle {
                            MobileDesignApp(
                                deps = AppDependencies(
                                    hosts = hosts, snippets = snippets, tunnels = tunnels,
                                    runbooks = mobileRunbooks, runbookRunner = runner,
                                ),
                                state = state,
                                sessions = sessions,
                                // The AI screen draws only its header without a controller behind it.
                                aiOverride = ai,
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        // Same wait as [runDesktopShell]: the seeded session connects on the background scope, and
        // a screen that reads Connected (the Run button of a runbook) would otherwise be measured
        // before the fake transport lands — a flake only a loaded runner sees.
        if (sessions != null) {
            waitUntil("seeded session reaches Connected", timeoutMillis = 10_000) {
                val s = sessions.active?.focusedPane?.controller?.uiState
                if (s is ConnectionUiState.Error) error("seeded session failed to connect: ${s.message}")
                s is ConnectionUiState.Connected
            }
        }
        body(MobileShell(hosts, state, snippets, tunnels, mobileRunbooks, ai, sessions))
    } finally {
        runner.close()
        sessions?.disconnectAll()
        scope.cancel()
        // A write already dispatched must not land in the next test's log.
        FakeShellInput.clear()
    }
}

/**
 * One form on its own, with the theme and fonts it needs and nothing else.
 *
 * For the forms a demo graph cannot reach: an SFTP dialog wants a live connection, a team dialog a
 * coordinator, a password dialog an unlocked vault. Rendering the composable directly still drives
 * the real thing with real clicks — only the way in is skipped.
 */
@OptIn(ExperimentalTestApi::class)
internal fun runForm(
    content: @Composable () -> Unit,
    body: ComposeUiTest.() -> Unit,
) = runComposeUiTest {
    setContent {
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
            ) {
                content()
            }
        }
    }
    waitForIdle()
    body()
}

/**
 * A screen's marker node ([app.skerry.ui.app.UiTags]).
 *
 * Unmerged on purpose: a screen tag rides on a plain container, and any clickable ancestor — the
 * settings card consumes clicks, for one — absorbs it into its own merged node, where a tag lookup
 * can no longer see it. Navigation buttons need no such thing: they carry the click themselves, so
 * they are nodes of the merged tree in their own right.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.onScreen(tag: String) = onNodeWithTag(tag, useUnmergedTree = true)

/**
 * A node of the host catalog sidebar, found by the text it draws.
 *
 * Scoped on purpose: the shell writes a host's name in several places at once — a session tab, the
 * work bar, the title bar's preview strip — so an unscoped lookup answers about the wrong one, and
 * "this row is gone" would be asserted against something the sidebar never drew.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.onCatalog(text: String): SemanticsNodeInteraction =
    onNode(hasText(text) and hasAnyAncestor(hasTestTag(UiTags.HOST_SIDEBAR)))

/** A session tab chip, found by the name it shows — scoped to the strip, like [onCatalog]. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.onTab(name: String): SemanticsNodeInteraction =
    onNode(hasText(name) and hasAnyAncestor(hasTestTag(UiTags.SESSION_TABS)))

/**
 * A form field, found by the caption drawn above it — which the input adopts as its accessible name
 * (see [app.skerry.ui.design.LocalFieldLabel]). The caption is read from the same resource the UI
 * renders, so the lookup holds in whatever locale the run happens to be in.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.onField(label: StringResource, unmerged: Boolean = false): SemanticsNodeInteraction =
    onNodeWithContentDescription(string(label), useUnmergedTree = unmerged)

/**
 * Same as [onField], for a caption a form uses more than once — the tunnel editor labels both its
 * bind port and its destination port "PORT". [index] counts them in composition order.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.onFieldAt(label: StringResource, index: Int = 0): SemanticsNodeInteraction =
    onAllNodesWithContentDescription(string(label))[index]

/**
 * A picker trigger — a row whose value is the text it draws, so its name is "caption, value" (see
 * [app.skerry.ui.design.fieldValueName]) and an exact-caption lookup would not find it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.onPickerAt(label: StringResource, index: Int = 0): SemanticsNodeInteraction =
    onAllNodesWithContentDescription(string(label), substring = true)[index]

/**
 * Presses a control by its semantics rather than by pointer.
 *
 * A phone sheet is taller than the 844dp scene it is laid out in, and the button at its foot ends
 * up past the bottom edge with no scrollable parent to bring it into view — a mouse click there
 * lands nowhere and reports nothing. Invoking the click action is the same press without the
 * geometry.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.press(tag: String) {
    // The click action is registered even on a disabled control, so pressing one by semantics would
    // drive a path the user cannot take: the state is asserted first.
    onNodeWithTag(tag).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}

/** The UI string behind [resource], in the run's locale. */
internal fun string(resource: StringResource): String = runBlocking { getString(resource) }

/** Same, for a string with placeholders — a control named after the thing it acts on. */
internal fun string(resource: StringResource, vararg args: Any): String = runBlocking { getString(resource, *args) }

/**
 * How long a wait on work that left the composition may take. `waitUntil`'s own default is a second,
 * and a hop through [kotlinx.coroutines.Dispatchers.Default] can miss that on a CI runner whose cores
 * are already carrying other Gradle workers — a timeout there fails a correct build.
 */
internal const val CROSS_THREAD_TIMEOUT_MS = 5_000L

/**
 * Clicks the icon button named [name] once it is enabled.
 *
 * The session-scoped toolbar actions are disabled until the composition has caught up with the
 * connection, and the seeded session connects on a background thread: the model says Connected
 * before the frame that redraws the toolbar has run. A press on a disabled button is refused with
 * nothing to show for it, so a test that clicks the instant the harness hands it the shell loses
 * the click and fails ten seconds later on whatever the click was supposed to open, with nothing in
 * the message pointing back here. Waiting on the button's own state rather than on a frame count is
 * what makes that impossible rather than unlikely.
 *
 * Enabled, not [androidx.compose.ui.test.hasClickAction]: Compose keeps the `OnClick` action on a
 * disabled clickable and marks it `Disabled` beside it, so matching on the action would go through
 * on the very frame this waits out.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickIconWhenEnabled(name: String, shell: DesktopShell? = null) {
    // `!isSelectable()` because the nav rail's own entries carry the same names as some of the
    // toolbar's buttons ("Snippets" is both), and only the rail's are selectable navigation targets.
    val button = hasContentDescription(name) and isEnabled() and !isSelectable()
    // Where the scene's own clock stood when the wait began. `waitUntil` spends wall clock and
    // advances this by one 60Hz tick per turn, so the two together say how many frames the wait
    // actually bought — the difference between a starved render loop and a settled composition
    // that simply says no.
    val startedAt = mainClock.currentTime
    try {
        waitUntil("\"$name\" to become clickable", timeoutMillis = 10_000) {
            onAllNodes(button).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (timeout: ComposeTimeoutException) {
        // "Condition still not satisfied after 10000ms" names neither the button nor the reason it
        // stayed disabled, and this one only ever fails on a loaded runner — where a re-run to look
        // closer costs the whole suite. The tree and the session already hold the answer.
        throw AssertionError(
            "\"$name\" never became enabled. ${diagnose(name, shell)} " +
                "sceneMsAdvanced=${mainClock.currentTime - startedAt}",
            timeout,
        )
    }
    onNode(button).performClick()
}

/** What the button and the session behind it looked like when the wait above ran out. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.diagnose(name: String, shell: DesktopShell?): String {
    val nodes = onAllNodes(hasContentDescription(name) and !isSelectable()).fetchSemanticsNodes()
    val drawn = nodes.joinToString(" | ") { node ->
        "bounds=${node.boundsInRoot} disabled=${node.config.contains(SemanticsProperties.Disabled)}"
    }
    val pane = shell?.sessions?.active?.focusedPane
    val session = pane?.let { "pane=${it.id} state=${it.controller.uiState::class.simpleName}" } ?: "no active pane"
    val runner = shell?.runner?.let { "runnerActive=${it.active} pending=${it.pending != null} phase=${it.phase}" } ?: ""
    // The rest of the row, which is what splits the answer in half. Every session action reads the
    // same connected pane; only the runbook one also reads the runner. So a row that is disabled
    // whole says the toolbar composed against a pane it still believes is down, and a row where
    // only this button is off says the runner. A row that is not there at all says the toolbar was
    // swapped out for another view.
    val row = TOOLBAR_NEIGHBOURS.joinToString(" ") { neighbour ->
        val label = string(neighbour)
        val all = onAllNodes(hasContentDescription(label) and !isSelectable()).fetchSemanticsNodes()
        val state = when {
            all.isEmpty() -> "absent"
            all.any { !it.config.contains(SemanticsProperties.Disabled) } -> "on"
            else -> "off"
        }
        "$label=$state"
    }
    // Whether one more settled frame flips it: a button still disabled after the tree goes idle is
    // a predicate that says no, not a frame the wait above failed to pump.
    waitForIdle()
    val afterIdle = enabledCount(name)
    // And whether a frame driven by hand flips it. `waitForIdle` renders nothing at all when the
    // tree already reads as idle, and a composition left behind by a write from another thread is
    // exactly that: settled, and one frame short.
    mainClock.advanceTimeByFrame()
    val afterFrame = enabledCount(name)
    return "${nodes.size} node(s) carry that name: $drawn. $session $runner " +
        "row[$row] enabledAfterIdle=$afterIdle enabledAfterFrame=$afterFrame"
}

/** How many nodes named [name] are enabled right now. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.enabledCount(name: String): Int =
    onAllNodes(hasContentDescription(name) and isEnabled() and !isSelectable()).fetchSemanticsNodes().size

/**
 * The session actions [diagnose] reads beside the button that timed out. Files is the control case:
 * it is enabled whenever the toolbar is drawn at all, so "absent" there means the work area is
 * showing something else entirely.
 */
private val TOOLBAR_NEIGHBOURS = listOf(
    Res.string.shell_tip_files,
    Res.string.shell_tip_snippets,
    Res.string.shell_tip_record,
)

/**
 * Every string the tree draws, in order. Unmerged: a text node absorbed into a clickable ancestor's
 * merged description is still text on the screen, and a filter test has to see it as drawn.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.drawnText(): List<String> =
    onRoot(useUnmergedTree = true).fetchSemanticsNode().allText()

/** [drawnText] for a subtree — the shape a test needs when there is more than one root. */
internal fun SemanticsNode.allText(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.allText() }

/** Same, for a counted string: the test asserts the wording the reader gets, not the plural rule. */
internal fun string(resource: PluralStringResource, quantity: Int, vararg args: Any): String =
    runBlocking { getPluralString(resource, quantity, *args) }

private val PHONE_WIDTH = 390.dp
private val PHONE_HEIGHT = 844.dp
