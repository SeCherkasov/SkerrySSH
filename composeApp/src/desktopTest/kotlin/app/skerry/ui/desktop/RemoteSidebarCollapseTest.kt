package app.skerry.ui.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.skerry.shared.ssh.isVnc
import app.skerry.shared.vnc.VncAuth
import app.skerry.ui.app.UiTags
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.connection.toTarget
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_hide_hosts
import app.skerry.ui.generated.resources.shell_tip_show_hosts
import app.skerry.ui.host.HostSection
import app.skerry.ui.remote.RemoteDesktopUiState
import kotlin.test.Test

/**
 * Issue #178: the Remote Desktops section had a handle to *re*open the hosts panel but nothing to
 * collapse it — the only collapse control in the app was the terminal work bar's chevron, so giving
 * a desktop session the full width meant a round trip through the terminal section.
 *
 * The desktops section renders no work bar, so the collapse control lives on the sidebar's own
 * edge, symmetrical with the reopen handle that was already there.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteSidebarCollapseTest {

    /** No session open on purpose: the panel must collapse from the empty-state screen too. */
    @Test
    fun `the desktops section collapses and reopens its hosts panel in place`() = runDesktopShell(withSessions = false) { shell ->
        onNodeWithTag(UiTags.railSection(HostSection.RemoteDesktops)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertIsDisplayed()

        onNodeWithContentDescription(string(Res.string.shell_tip_hide_hosts)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertDoesNotExist()
        check(shell.state.sidebarHidden) { "the collapse handle must drive the shared sidebar preference" }

        onNodeWithContentDescription(string(Res.string.shell_tip_show_hosts)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertIsDisplayed()
        check(!shell.state.sidebarHidden) { "reopening must flip the shared preference back" }
    }

    /**
     * The other half of the handle's visibility rule: beside a live framebuffer it still toggles
     * the panel, and only full-window mode takes it off screen — together with the rest of the
     * chrome, whose way back is the desktop's floating bar, not this strip.
     */
    @Test
    fun `full-window mode takes the handle with the rest of the chrome`() = runDesktopShell { shell ->
        val sessions = checkNotNull(shell.sessions)
        // Dialing the seeded VNC host over the fake session, the way the offscreen render does:
        // a desktop lives in a tab of its own, so the section alone would show its empty state.
        val desktop = shell.hosts.hosts.first { it.connectionType.isVnc }
        checkNotNull(
            sessions.openVnc(desktop.id, desktop.label, desktop.connectionSubtitle(), desktop.toTarget(), VncAuth.None),
        ) { "fake VNC transport not wired" }
        // The connect lands on the background scope, which waitForIdle does NOT wait for (see the
        // seeded-session wait in ShellHarness). Without this the test can run entirely against the
        // Connecting placeholder — never composing the framebuffer whose LocalLifecycleOwner read
        // is the regression WithTestLifecycle exists to catch.
        waitUntil("desktop session reaches Connected", timeoutMillis = 10_000) {
            sessions.activeDesktop?.focusedPane?.vncController?.uiState is RemoteDesktopUiState.Connected
        }
        waitForIdle()

        onNodeWithContentDescription(string(Res.string.shell_tip_hide_hosts)).performClick()
        waitForIdle()
        onScreen(UiTags.HOST_SIDEBAR).assertDoesNotExist()
        check(shell.state.sidebarHidden) { "the handle must work beside a connected desktop too" }

        shell.state.toggleRemoteImmersive()
        waitForIdle()
        onNodeWithContentDescription(string(Res.string.shell_tip_show_hosts)).assertDoesNotExist()

        shell.state.exitRemoteImmersive()
        waitForIdle()
        onNodeWithContentDescription(string(Res.string.shell_tip_show_hosts)).assertIsDisplayed()
    }
}
