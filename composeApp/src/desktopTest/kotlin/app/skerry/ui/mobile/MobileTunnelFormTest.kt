package app.skerry.ui.mobile

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onFieldAt
import app.skerry.ui.desktop.onPickerAt
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_field_bind_address
import app.skerry.ui.generated.resources.ports_field_destination
import app.skerry.ui.generated.resources.ports_field_name
import app.skerry.ui.generated.resources.ports_field_port
import app.skerry.ui.generated.resources.ports_field_via_host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The phone's tunnel editor. It builds the same [app.skerry.ui.tunnel.TunnelFormState] the desktop
 * one does, so the draft rules are shared — what is phone-specific is the sheet around them, and a
 * forward saved with the wrong port opens a hole to somewhere nobody asked for.
 */
@OptIn(ExperimentalTestApi::class)
class MobileTunnelFormTest {

    @Test
    fun `a local forward filled in on the phone lands in the list`() = runMobileShell { shell ->
        val before = shell.tunnels.tunnels.size
        shell.state.push(MobileRoute.Ports)
        waitForIdle()
        openEditor()
        onFieldAt(Res.string.ports_field_name).performTextReplacement(NAME)
        pickFirstHost()
        onFieldAt(Res.string.ports_field_bind_address).performTextReplacement(BIND_HOST)
        onFieldAt(Res.string.ports_field_port, index = 0).performTextReplacement(BIND_PORT)
        onFieldAt(Res.string.ports_field_destination).performTextReplacement(DEST_HOST)
        onFieldAt(Res.string.ports_field_port, index = 1).performTextReplacement(DEST_PORT)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        assertEquals(before + 1, shell.tunnels.tunnels.size, "the phone editor saved nothing")
        val saved = shell.tunnels.tunnels.map { it.tunnel }.single { it.label == NAME }
        assertEquals(TunnelDirection.Local, saved.direction)
        assertEquals(BIND_PORT.toInt(), saved.bindPort)
        assertEquals(DEST_PORT.toInt(), saved.destPort)
        assertNotNull(shell.hosts.find(saved.hostId), "the forward was saved against an unknown host")
    }

    /** Same rule as the desktop editor: no host to ride through, nothing to save. */
    @Test
    fun `saving does nothing until a host is picked`() = runMobileShell { shell ->
        val before = shell.tunnels.tunnels.map { it.id }
        shell.state.push(MobileRoute.Ports)
        waitForIdle()
        openEditor()
        onFieldAt(Res.string.ports_field_name).performTextReplacement(NAME)
        onFieldAt(Res.string.ports_field_port, index = 0).performTextReplacement(BIND_PORT)
        onFieldAt(Res.string.ports_field_destination).performTextReplacement(DEST_HOST)
        onFieldAt(Res.string.ports_field_port, index = 1).performTextReplacement(DEST_PORT)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        assertEquals(before, shell.tunnels.tunnels.map { it.id })
        assertTrue(shell.tunnels.tunnels.none { it.tunnel.label == NAME })
    }

    /** The sheet composes after the list behind it, so its menu row is the last match for the name. */
    private fun ComposeUiTest.pickFirstHost() {
        onPickerAt(Res.string.ports_field_via_host).performClick()
        waitForIdle()
        onAllNodesWithText(FIRST_HOST_LABEL).onLast().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openEditor() {
        onNodeWithTag(UiTags.NEW_TUNNEL).performClick()
        waitForIdle()
    }
}

private const val NAME = "phone-forward"
private const val BIND_HOST = "127.0.0.1"
private const val BIND_PORT = "8081"
private const val DEST_HOST = "10.0.0.5"
private const val DEST_PORT = "80"
private const val FIRST_HOST_LABEL = "prod-web-01"
