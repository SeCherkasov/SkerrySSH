package app.skerry.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.skerry.ui.AppDependencies
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.UiTags
import app.skerry.ui.ai.AiAssistantController
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
import org.jetbrains.compose.resources.StringResource
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
    }
    var seq = 0
    return RunbookManager(store) { "run-${seq++}" }
}

/** Desktop shell over the seeded catalog. [withSessions] opens two demo tabs over a fake transport. */
@OptIn(ExperimentalTestApi::class)
internal fun runDesktopShell(
    withSessions: Boolean = true,
    body: ComposeUiTest.(DesktopShell) -> Unit,
) = runComposeUiTest {
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
    FakeShellInput.clear()
    try {
        setContent {
            SkerryTheme {
                // A live AI controller only so the settings panel offers its AI tab: without one the
                // tab is hidden, and a walk over SETTINGS_NAV would silently skip an entry.
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
                )
            }
        }
        waitForIdle()
        val libraries = ShellLibraries(tunnels, snippets, runbooks, credentials, ai)
        body(DesktopShell(hosts, sessions, libraries, runner, state))
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

/** What a mobile test can reach behind the UI. */
internal class MobileShell(
    val hosts: HostManagerController,
    val state: MobileDesignState,
    val snippets: SnippetManager,
    val tunnels: TunnelManager,
    val runbooks: RunbookManager,
    val ai: AiAssistantController,
)

/**
 * Mobile shell over the seeded catalog, in a phone-sized box (390x844dp, as the mobile screenshot
 * renders it). The scene itself is the desktop default; the box is what the layout sees.
 */
@OptIn(ExperimentalTestApi::class)
internal fun runMobileShell(
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
    try {
        setContent {
            SkerryTheme {
                Box(Modifier.size(width = PHONE_WIDTH, height = PHONE_HEIGHT)) {
                    MobileDesignApp(
                        deps = AppDependencies(hosts = hosts, snippets = snippets, tunnels = tunnels, runbooks = mobileRunbooks),
                        state = state,
                        sessions = null,
                        // The AI screen draws only its header without a controller behind it.
                        aiOverride = ai,
                    )
                }
            }
        }
        waitForIdle()
        body(MobileShell(hosts, state, snippets, tunnels, mobileRunbooks, ai))
    } finally {
        scope.cancel()
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
    onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}

/** The UI string behind [resource], in the run's locale. */
internal fun string(resource: StringResource): String = runBlocking { getString(resource) }

/** Same, for a string with placeholders — a control named after the thing it acts on. */
internal fun string(resource: StringResource, vararg args: Any): String = runBlocking { getString(resource, *args) }

private val PHONE_WIDTH = 390.dp
private val PHONE_HEIGHT = 844.dp
