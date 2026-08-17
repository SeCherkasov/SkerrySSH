package app.skerry.ui.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.shared.rdp.RdpFileHost
import app.skerry.shared.rdp.RdpFileImportResult
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshConfigHost
import app.skerry.shared.ssh.SshConfigParseResult
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.seededHosts
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_import_select_all
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two import previews. Neither is reachable from a test through the UI — both open on the
 * result of a file the user picked — but both write straight into the catalog, so what they do with
 * a press is worth pinning.
 *
 * The `ssh_config` one imports a selection, not a file: everything is ticked by default and a host
 * unticked here must not appear. The `.rdp` one carries a single profile and has to land it in the
 * remote-desktops section, not among the shells.
 */
@OptIn(ExperimentalTestApi::class)
class ImportFormTest {

    @Test
    fun `importing an ssh config brings its hosts into the catalog`() {
        val hosts = seededHosts()
        val before = hosts.hosts.size
        val state = DesktopDesignState()
        runForm({
            CompositionLocalProvider(LocalHosts provides hosts) {
                SshConfigImportModal(state, PARSED)
            }
        }) {
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(before + PARSED.hosts.size, hosts.hosts.size)
        val imported = hosts.hosts.single { it.label == "bastion" }
        assertEquals("bastion.example.com", imported.address)
        assertEquals(2222, imported.port)
        assertEquals("deploy", imported.username)
    }

    /** Unticking a host is the whole point of the preview: it must not be imported. */
    @Test
    fun `a host unticked in the preview is left out`() {
        val hosts = seededHosts()
        val state = DesktopDesignState()
        runForm({
            CompositionLocalProvider(LocalHosts provides hosts) {
                SshConfigImportModal(state, PARSED)
            }
        }) {
            onNodeWithText("bastion").performClick()
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertTrue(hosts.hosts.none { it.label == "bastion" }, "an unticked host was imported anyway")
        assertNotNull(hosts.hosts.firstOrNull { it.label == "db" }, "the ticked host was not imported")
    }

    /**
     * Issue #228: the tick is a Material Symbol ligature, and [Sym] clears its own semantics — so a
     * row that only carries a click tells a screen reader nothing about being on or off. The row is
     * the checkbox; its state has to live there.
     */
    @Test
    fun `an import row reads as a checkbox with a state`() {
        val hosts = seededHosts()
        val state = DesktopDesignState()
        runForm({
            CompositionLocalProvider(LocalHosts provides hosts) {
                SshConfigImportModal(state, PARSED)
            }
        }) {
            onNodeWithText("bastion").assertIsToggleable().assertIsOn().performClick()
            waitForIdle()
            onNodeWithText("bastion").assertIsOff()
        }
    }

    /** The select-all row is the same control for every row at once, and answers the same way. */
    @Test
    fun `the select-all row reads as a checkbox with a state`() {
        val hosts = seededHosts()
        val state = DesktopDesignState()
        runForm({
            CompositionLocalProvider(LocalHosts provides hosts) {
                SshConfigImportModal(state, PARSED)
            }
        }) {
            onNodeWithText(string(Res.string.conn_import_select_all)).assertIsToggleable().assertIsOn().performClick()
            waitForIdle()
            onNodeWithText(string(Res.string.conn_import_select_all)).assertIsOff()
        }
    }

    @Test
    fun `cancelling an ssh config import writes nothing`() {
        val hosts = seededHosts()
        val before = hosts.hosts.map { it.id }
        val state = DesktopDesignState()
        runForm({
            CompositionLocalProvider(LocalHosts provides hosts) {
                SshConfigImportModal(state, PARSED)
            }
        }) {
            onNodeWithTag(UiTags.FORM_CANCEL).performClick()
            waitForIdle()
        }
        assertEquals(before, hosts.hosts.map { it.id })
    }

    @Test
    fun `an rdp file lands as a remote desktop`() {
        val hosts = seededHosts()
        val state = DesktopDesignState()
        runForm({
            CompositionLocalProvider(LocalHosts provides hosts) {
                RdpFileImportModal(state, RdpFileImportResult(RDP_HOST, warnings = emptyList()))
            }
        }) {
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        val imported = hosts.hosts.single { it.label == "win-bench-2" }
        assertEquals(ConnectionType.RDP, imported.connectionType, "an .rdp profile was imported as a shell")
        assertEquals(3390, imported.port)
    }
}

private val PARSED = SshConfigParseResult(
    hosts = listOf(
        SshConfigHost("bastion", "bastion.example.com", 2222, "deploy", null, null),
        SshConfigHost("db", "db.example.com", 22, "root", null, null),
    ),
    warnings = emptyList(),
)

private val RDP_HOST = RdpFileHost(
    label = "win-bench-2",
    address = "10.0.0.40",
    port = 3390,
    username = "administrator",
    loadBalanceInfo = "",
)
