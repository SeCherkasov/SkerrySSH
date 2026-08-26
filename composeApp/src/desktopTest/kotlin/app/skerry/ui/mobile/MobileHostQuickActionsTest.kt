package app.skerry.ui.mobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.shared.ssh.ConnectionType
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_quick_sftp
import app.skerry.ui.generated.resources.shell_quick_snippets
import app.skerry.ui.generated.resources.shell_quick_tunnels
import app.skerry.ui.host.HostDraft
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Quick actions on the phone's host detail. Issue #305: the Snippets card was drawn exactly like the
 * two live ones — same background, same cyan border, same icon colour — and did nothing when
 * pressed. The rule elsewhere in the app is that an action that can do nothing is not drawn at all,
 * so a card that is on screen has to lead somewhere.
 */
@OptIn(ExperimentalTestApi::class)
class MobileHostQuickActionsTest {

    /** h1 is a shell host in the seeded catalog; h5 is a VNC desktop. */
    private val shellHost = "h1"
    private val remoteDesktop = "h5"

    /**
     * The other half of the rule: an SSH host does carry a file channel, so the card is there and it
     * is pressable. Without this the two absence checks below would pass just as happily with the
     * card gone from every host.
     */
    @Test
    fun `an SSH host offers the SFTP card`() = runMobileShell { shell ->
        shell.state.openHost(shellHost)
        waitForIdle()

        onNodeWithText(string(Res.string.shell_quick_sftp)).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `the Snippets quick action opens the snippet library`() = runMobileShell { shell ->
        shell.state.openHost(shellHost)
        waitForIdle()

        onNodeWithText(string(Res.string.shell_quick_snippets)).performClick()
        waitForIdle()

        assertEquals(MobileRoute.Snippets, shell.state.route, "the Snippets card led nowhere")
    }

    /**
     * A remote desktop has no file channel, so SFTP cannot open on it. Drawn like a working card it
     * is the same trap as the Snippets one — it is left out instead.
     */
    @Test
    fun `a remote desktop offers no SFTP card at all`() = runMobileShell { shell ->
        shell.state.openHost(remoteDesktop)
        waitForIdle()

        onNodeWithText(string(Res.string.shell_quick_tunnels)).assertIsDisplayed()
        onNodeWithText(string(Res.string.shell_quick_sftp)).assertDoesNotExist()
    }

    /**
     * The section a profile is filed under is not the question — the file channel is. A Telnet
     * switch is a terminal host like any other and used to be offered SFTP, which opens a remote
     * browser that can never list anything. The card follows the SSH channel, as the terminal's own
     * path chip does.
     */
    @Test
    fun `a Telnet host offers no SFTP card either`() = runMobileShell { shell ->
        val id = shell.hosts.save(
            HostDraft(
                label = "switch-core",
                address = "10.0.0.9",
                port = 23,
                username = "admin",
                connectionType = ConnectionType.TELNET,
            ),
        )
        shell.state.openHost(id)
        waitForIdle()

        onNodeWithText(string(Res.string.shell_quick_tunnels)).assertIsDisplayed()
        onNodeWithText(string(Res.string.shell_quick_sftp)).assertDoesNotExist()
    }
}
