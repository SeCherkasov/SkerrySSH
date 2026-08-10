package app.skerry.ui.tunnel

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.DesktopShell
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_autostart
import app.skerry.ui.generated.resources.ports_autostart_switch
import app.skerry.ui.generated.resources.ports_field_name
import app.skerry.ui.generated.resources.ports_remove
import app.skerry.ui.generated.resources.ports_remove_confirm_title
import app.skerry.ui.generated.resources.ports_tunnel_detail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tunnel table as a list of actions rather than a picture: the switch that raises a forward, the
 * row that opens the editor on its own tunnel, deletion behind a confirmation, and the autostart
 * panel — a second switch per tunnel, which arms the next unlock instead of dialling now.
 *
 * [TunnelManager] is covered as state elsewhere; nothing covered the wiring, and a switch wired to
 * the wrong row (or to the wrong one of the two meanings) reads as working right up to the moment a
 * forward comes up unattended that nobody asked for.
 *
 * The tunnels dial a fake transport ([app.skerry.ui.desktop.seededTunnels]), so a raise here opens
 * no socket. "web tunnel" is already up when the shell appears.
 */
@OptIn(ExperimentalTestApi::class)
class TunnelListTest {

    @Test
    fun `the row switch raises a tunnel`() = runDesktopShell { shell ->
        openTunnels()
        val entry = shell.tunnel(INACTIVE_TUNNEL)
        assertFalse(entry.status is TunnelStatus.Active, "the seed must leave this one down")

        onNodeWithContentDescription(INACTIVE_TUNNEL).assertIsOff().performClick()
        waitUntil { shell.tunnel(INACTIVE_TUNNEL).status is TunnelStatus.Active }
        waitForIdle()
        onNodeWithContentDescription(INACTIVE_TUNNEL).assertIsOn()
    }

    @Test
    fun `the switch takes an active tunnel down`() = runDesktopShell { shell ->
        openTunnels()
        assertTrue(shell.tunnel(ACTIVE_TUNNEL).status is TunnelStatus.Active, "the seed raises this one")

        onNodeWithContentDescription(ACTIVE_TUNNEL).assertIsOn().performClick()
        waitForIdle()
        assertEquals(TunnelStatus.Inactive, shell.tunnel(ACTIVE_TUNNEL).status)
        onNodeWithContentDescription(ACTIVE_TUNNEL).assertIsOff()
    }

    /** The editor is one panel for every row: opening it on the wrong tunnel saves over that one. */
    @Test
    fun `a row opens the editor on its own tunnel`() = runDesktopShell {
        openTunnels()
        onNodeWithText(SOCKS_TUNNEL).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.ports_tunnel_detail)).assertIsDisplayed()
        onField(Res.string.ports_field_name).assertTextContains(SOCKS_TUNNEL)
    }

    @Test
    fun `removing a tunnel asks first`() = runDesktopShell { shell ->
        openTunnels()
        onNodeWithText(SOCKS_TUNNEL).performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.ports_remove)).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.ports_remove_confirm_title, SOCKS_TUNNEL)).assertIsDisplayed()
        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()
        assertTrue(shell.tunnels.tunnels.any { it.tunnel.label == SOCKS_TUNNEL }, "a refused delete keeps the tunnel")

        onNodeWithText(string(Res.string.ports_remove)).performClick()
        waitForIdle()
        // The editor's Save carries the same tag and is still behind the dialog, so the confirm is
        // the one that also says "Remove".
        onNode(hasTestTag(UiTags.FORM_SAVE) and hasText(string(Res.string.ports_remove))).performClick()
        waitForIdle()
        assertNull(shell.tunnels.tunnels.firstOrNull { it.tunnel.label == SOCKS_TUNNEL })
        onNodeWithText(SOCKS_TUNNEL).assertDoesNotExist()
    }

    /**
     * The autostart switch and the table's switch sit on screen together and mean different things.
     * Arming a tunnel must not dial it, or "starts on unlock" would quietly be "starts now".
     */
    @Test
    fun `the autostart panel arms a tunnel without dialling it`() = runDesktopShell { shell ->
        openTunnels()
        // The dashboard names its autostart column the same; the button is the one that acts.
        onNode(hasText(string(Res.string.ports_autostart)) and hasClickAction()).performClick()
        waitForIdle()

        val armed = string(Res.string.ports_autostart_switch, INACTIVE_TUNNEL)
        onNodeWithContentDescription(armed).assertIsOff().performClick()
        waitForIdle()

        assertTrue(shell.tunnel(INACTIVE_TUNNEL).tunnel.autostart, "the switch must reach the saved tunnel")
        assertFalse(
            shell.tunnel(INACTIVE_TUNNEL).status is TunnelStatus.Active,
            "arming for the next unlock is not raising the forward now",
        )
        onNodeWithContentDescription(INACTIVE_TUNNEL).assertIsOff()
    }

    private fun ComposeUiTest.openTunnels() {
        onNodeWithTag(UiTags.railView(DesktopView.Ports)).performClick()
        waitForIdle()
    }

    private fun DesktopShell.tunnel(label: String): TunnelEntry =
        tunnels.tunnels.first { it.tunnel.label == label }
}

// Seeded tunnels ([app.skerry.ui.desktop.seededTunnels]): one raised at seed time, two down.
private const val ACTIVE_TUNNEL = "web tunnel"
private const val INACTIVE_TUNNEL = "app callback"
private const val SOCKS_TUNNEL = "socks"
