package app.skerry.ui.tunnel

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onFieldAt
import app.skerry.ui.desktop.onPickerAt
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ports_field_bind_address
import app.skerry.ui.generated.resources.ports_field_destination
import app.skerry.ui.generated.resources.ports_field_type
import app.skerry.ui.generated.resources.ports_socks_hint
import app.skerry.ui.generated.resources.ports_type_socks_display
import app.skerry.ui.generated.resources.ports_field_name
import app.skerry.ui.generated.resources.ports_field_port
import app.skerry.ui.generated.resources.ports_field_via_host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import app.skerry.ui.desktop.string

/**
 * The tunnel editor. Two things make it the awkward one: the host it rides through is picked from a
 * dropdown rather than typed, and "PORT" labels two different fields — the bind port and the
 * destination port — so a lookup by caption has to say which.
 *
 * [buildTunnelDraft] is already covered as a function; here the question is whether the form's
 * inputs and its Save reach it.
 */
@OptIn(ExperimentalTestApi::class)
class TunnelEditorFormTest {

    @Test
    fun `a local forward filled in from the form lands in the list`() = runDesktopShell { shell ->
        openEditor()
        fillLocalForward()
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        val saved = shell.tunnels.tunnels.map { it.tunnel }.singleOrNull { it.label == NAME }
        assertNotNull(saved, "the editor saved nothing")
        assertEquals(TunnelDirection.Local, saved.direction)
        assertEquals(BIND_PORT.toInt(), saved.bindPort)
        assertEquals(DEST_HOST, saved.destHost)
        assertEquals(DEST_PORT.toInt(), saved.destPort)
        assertEquals(FIRST_HOST_LABEL, shell.hosts.find(saved.hostId)?.label)
    }

    /**
     * A SOCKS forward has no destination: picking Dynamic must both reach the draft and take the
     * destination fields off the form, or the user fills in boxes the tunnel will never use.
     */
    @Test
    fun `picking a dynamic forward drops the destination fields`() = runDesktopShell { shell ->
        openEditor()
        onFieldAt(Res.string.ports_field_name).performTextReplacement(NAME)
        onFieldAt(Res.string.ports_field_bind_address).performTextReplacement(BIND_HOST)
        onFieldAt(Res.string.ports_field_port, index = 0).performTextReplacement(BIND_PORT)
        pickFirstHost()
        onFieldAt(Res.string.ports_field_destination).assertExists()

        pickDynamic()
        onNodeWithText(string(Res.string.ports_socks_hint)).assertIsDisplayed()
        onFieldAt(Res.string.ports_field_destination).assertDoesNotExist()

        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()
        val saved = shell.tunnels.tunnels.map { it.tunnel }.singleOrNull { it.label == NAME }
        assertNotNull(saved, "the editor saved nothing")
        assertEquals(TunnelDirection.Dynamic, saved.direction)
    }

    /** Without a host to ride through there is no forward to save, however complete the rest is. */
    @Test
    fun `save is refused until a host is picked`() = runDesktopShell {
        openEditor()
        onFieldAt(Res.string.ports_field_name).performTextReplacement(NAME)
        onFieldAt(Res.string.ports_field_bind_address).performTextReplacement(BIND_HOST)
        onFieldAt(Res.string.ports_field_port, index = 0).performTextReplacement(BIND_PORT)
        onFieldAt(Res.string.ports_field_destination).performTextReplacement(DEST_HOST)
        onFieldAt(Res.string.ports_field_port, index = 1).performTextReplacement(DEST_PORT)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

        pickFirstHost()
        onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()
    }

    /** A port outside 1..65535 is not a port — the draft must refuse it rather than clamp it. */
    @Test
    fun `an out-of-range destination port keeps save shut`() = runDesktopShell {
        openEditor()
        fillLocalForward()
        onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()

        onFieldAt(Res.string.ports_field_port, index = 1).performTextReplacement("70000")
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
    }

    private fun ComposeUiTest.fillLocalForward() {
        onFieldAt(Res.string.ports_field_name).performTextReplacement(NAME)
        pickFirstHost()
        onFieldAt(Res.string.ports_field_bind_address).performTextReplacement(BIND_HOST)
        onFieldAt(Res.string.ports_field_port, index = 0).performTextReplacement(BIND_PORT)
        onFieldAt(Res.string.ports_field_destination).performTextReplacement(DEST_HOST)
        onFieldAt(Res.string.ports_field_port, index = 1).performTextReplacement(DEST_PORT)
    }

    /**
     * The catalog in the sidebar lists the same host names as the open menu, so the match is taken
     * from the end: the popup composes after the sidebar and is the last node with that text.
     */
    /** The type picker: its trigger is named "caption, value", and the menu row is the last match. */
    private fun ComposeUiTest.pickDynamic() {
        onPickerAt(Res.string.ports_field_type).performClick()
        waitForIdle()
        onAllNodesWithText(string(Res.string.ports_type_socks_display)).onLast().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.pickFirstHost() {
        onPickerAt(Res.string.ports_field_via_host).performClick()
        waitForIdle()
        onAllNodesWithText(FIRST_HOST_LABEL).onLast().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openEditor() {
        onNodeWithTag(UiTags.railView(DesktopView.Ports)).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.NEW_TUNNEL).performClick()
        waitForIdle()
        // The caption is proof the editor is up: it is the form's first field.
        onFieldAt(Res.string.ports_field_name).assertExists()
    }
}

private const val NAME = "grafana"
private const val BIND_HOST = "127.0.0.1"
private const val BIND_PORT = "3000"
private const val DEST_HOST = "10.0.0.5"
private const val DEST_PORT = "3000"

/** First profile of the seeded catalog — what the host dropdown offers at the top. */
private const val FIRST_HOST_LABEL = "prod-web-01"
