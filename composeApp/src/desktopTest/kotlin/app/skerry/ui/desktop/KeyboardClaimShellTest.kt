package app.skerry.ui.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import app.skerry.shared.ssh.isVnc
import app.skerry.ui.app.HostClickConnectMode
import app.skerry.ui.app.UiTags
import app.skerry.ui.host.HostSection
import app.skerry.shared.vnc.VncAuth
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_hide_hosts
import app.skerry.ui.generated.resources.term_search_hosts_placeholder
import app.skerry.ui.generated.resources.shell_password_host_placeholder
import app.skerry.ui.generated.resources.shell_tip_disconnect
import app.skerry.ui.generated.resources.shtail_group_collapse
import app.skerry.ui.remote.RemoteDesktopUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The keyboard claim through the real chrome, rather than a stand-in for it: the controls beside a
 * session take focus on a mouse press like any `clickable`, and it is the wiring in those controls
 * ([app.skerry.ui.design.handsKeyboardBack]) that hands the keyboard back. Delete it from the
 * sidebar handle or the work bar and these two go red; the surface-level tests would not.
 *
 * The third case is the rule's other half — a caret in a field beside the session survives the
 * window going away and coming back, which is what keeps a connect password out of a live shell.
 */
@OptIn(ExperimentalTestApi::class)
class KeyboardClaimShellTest {

    /** A folder in the seeded catalog — see `seededHosts`. */
    private val seededGroup = "Production"


    @Test
    fun `collapsing the desktops sidebar leaves the keyboard on the framebuffer`() = runDesktopShell { shell ->
        val sessions = checkNotNull(shell.sessions)
        val desktop = shell.hosts.hosts.first { it.connectionType.isVnc }
        checkNotNull(
            sessions.openVnc(desktop.id, desktop.label, desktop.connectionSubtitle(), desktop.toTarget(), VncAuth.None),
        ) { "fake VNC transport not wired" }
        waitUntil("desktop session reaches Connected", timeoutMillis = 10_000) {
            sessions.activeDesktop?.focusedPane?.vncController?.uiState is RemoteDesktopUiState.Connected
        }
        waitForIdle()
        FakeRemoteInput.clear()
        onRoot().performKeyInput { pressKey(Key.A) }
        waitUntil("a fresh desktop session takes the keyboard") { FakeRemoteInput.keys() > 0 }

        // A mouse click, not performClick's tap: focus-on-press is a mouse-mode behaviour, and the
        // hand-back is wired for exactly that.
        onNodeWithContentDescription(string(Res.string.shell_tip_hide_hosts)).performMouseInput { click() }
        waitForIdle()
        FakeRemoteInput.clear()
        onRoot().performKeyInput { pressKey(Key.A) }
        waitUntil("the collapse handle kept the keyboard") { FakeRemoteInput.keys() > 0 }
    }

    /** The terminal side of the same rule, through a control of its own sidebar. */
    @Test
    fun `collapsing a sidebar group leaves the keyboard on the shell`() = runDesktopShell {
        onNodeWithContentDescription(string(Res.string.shtail_group_collapse, seededGroup))
            .performMouseInput { click() }
        waitForIdle()
        FakeShellInput.clear()
        onRoot().performKeyInput { pressKey(Key.L) }
        waitUntil("the group toggle kept the keyboard") { FakeShellInput.all().contains("l") }
    }

    /**
     * The terminal's own half of the sidebar-handle case above: one strip, both sections.
     */
    @Test
    fun `collapsing the terminal sidebar leaves the keyboard on the shell`() = runDesktopShell {
        onNodeWithContentDescription(string(Res.string.shell_tip_hide_hosts)).performMouseInput { click() }
        waitForIdle()
        FakeShellInput.clear()
        onRoot().performKeyInput { pressKey(Key.L) }
        waitUntil("the sidebar strip kept the keyboard") { FakeShellInput.all().contains("l") }
    }

    /**
     * The hardest shape of the same bug: a button that takes the keyboard on its press and only then
     * opens a modal. The press must count as a hand-back, or the session is left unowned while the
     * dialog is up and gets nothing back when it closes.
     */
    @Test
    fun `a cancelled confirmation leaves the keyboard on the shell`() = runDesktopShell {
        onNodeWithContentDescription(string(Res.string.shell_tip_disconnect)).performMouseInput { click() }
        waitForIdle()
        onScreen(UiTags.FORM_CANCEL).performMouseInput { click() }
        waitForIdle()

        FakeShellInput.clear()
        onRoot().performKeyInput { pressKey(Key.L) }
        waitUntil("the cancelled dialog left the keyboard nowhere") { FakeShellInput.all().contains("l") }
    }

    /**
     * The rail is where a keyboard user lands first, and its buttons take the keyboard on a mouse
     * press like any other chrome.
     */
    @Test
    fun `switching rail sections leaves the keyboard on the shell`() = runDesktopShell {
        // The section already open: nothing changes but the focus, so nothing else would claim back.
        onScreen(UiTags.railSection(HostSection.Terminal)).performMouseInput { click() }
        waitForIdle()
        FakeShellInput.clear()
        // The pointer is left hovering the button, so its tooltip popup is a second root; keys go
        // to whoever holds focus either way, and the window's own root is the first.
        onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.L) }
        waitUntil("the rail button kept the keyboard") { FakeShellInput.all().contains("l") }
    }

    /**
     * A modal owns the keyboard, and the root shortcut handler runs above the focus its field takes
     * — so a chord typed into a password (a capital letter, an AltGr character) must not reach the
     * shell's own hotkeys and act on the session waiting underneath.
     */
    @Test
    fun `a chord typed into the password dialog does not reach the shell shortcuts`() = runDesktopShell { shell ->
        onCatalog("vps-edge").performMouseInput { click() }
        waitForIdle()
        onNodeWithContentDescription(string(Res.string.shell_password_host_placeholder)).assertIsFocused()

        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.K) } } }
        waitForIdle()
        assertTrue(!shell.state.commandPaletteOpen, "a shell shortcut fired from inside the password dialog")
    }

    /**
     * The find bar owns the keyboard while it is up, and closing it disposes the focused field —
     * Compose then clears focus to no one. The terminal saying "I am the one to type into" is its
     * own signal, not a claim over anybody: it must not be blocked by the ownership rule that keeps
     * the session off a field beside it.
     */
    @Test
    fun `closing the find bar hands the keyboard back to the shell`() = runDesktopShell {
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } } }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        FakeShellInput.clear()
        onRoot().performKeyInput { pressKey(Key.L) }
        waitUntil("the find bar closed and the keyboard went nowhere") { FakeShellInput.all().contains("l") }
    }

    /**
     * Double-click mode is where a host row is chrome rather than a way in: a single click only
     * selects, so nothing opens that would claim the keyboard back.
     */
    @Test
    fun `selecting a host row in double-click mode leaves the keyboard on the shell`() = runDesktopShell { shell ->
        shell.state.settings.chooseHostClickConnectMode(HostClickConnectMode.DoubleClick)
        waitForIdle()
        onCatalog("homelab-pi").performMouseInput { click() }
        waitForIdle()

        FakeShellInput.clear()
        onRoot().performKeyInput { pressKey(Key.L) }
        waitUntil("selecting a row kept the keyboard") { FakeShellInput.all().contains("l") }
    }

    /**
     * The dialog that asks for a connect password opens over a live session, draws its own scrim and
     * is the reason the claim has to know who owns the keyboard. Typing into it must reach the field,
     * never the shell underneath — which also means the field has to take the caret on its own.
     */
    @Test
    fun `a connect password is not typed into the session underneath`() = runDesktopShell {
        // A seeded host with no bound secret: connecting to it asks for a password.
        onCatalog("vps-edge").performMouseInput { click() }
        waitForIdle()
        onNodeWithContentDescription(string(Res.string.shell_password_host_placeholder)).assertExists()

        FakeShellInput.clear()
        onRoot().performKeyInput { pressKey(Key.L) }
        waitForIdle()
        assertTrue(
            FakeShellInput.all().none { it.contains("l") },
            "the password went into the live session under the dialog: ${FakeShellInput.all()}",
        )
        // The other half: the caret is in the field, rather than merely away from the shell — the
        // dialog draws its own scrim, so nothing else would put it there.
        onNodeWithContentDescription(string(Res.string.shell_password_host_placeholder)).assertIsFocused()
    }

    /**
     * The sidebar's filter is a field beside the session and no one's modal. A window round trip
     * clears focus app-wide (`ComposeSceneMediator.focusLost` → `releaseFocus`), and the session
     * must not read that as an invitation to take the keyboard the field was using.
     */
    @Test
    fun `a caret in the hosts filter is not taken by the terminal when the window returns`() {
        var windowFocused by mutableStateOf(true)
        val windowInfo = object : WindowInfo {
            override val isWindowFocused: Boolean get() = windowFocused
        }
        runDesktopShell(windowInfo = windowInfo) { shell ->
            onNodeWithContentDescription(string(Res.string.term_search_hosts_placeholder))
                .performMouseInput { click() }
            waitForIdle()
            // The click really did take the keyboard: without this the negative assertion below
            // would also pass on a run where nothing ever had it.
            onNodeWithContentDescription(string(Res.string.term_search_hosts_placeholder)).assertIsFocused()

            windowFocused = false
            waitForIdle()
            runOnIdle { shell.focus()?.clearFocus(force = true) }
            waitForIdle()
            windowFocused = true
            waitForIdle()

            FakeShellInput.clear()
            onRoot().performKeyInput { pressKey(Key.L) }
            waitForIdle()
            assertTrue(
                FakeShellInput.all().none { it.contains("l") },
                "the terminal took the keyboard off the filter field: ${FakeShellInput.all()}",
            )
        }
    }
}
