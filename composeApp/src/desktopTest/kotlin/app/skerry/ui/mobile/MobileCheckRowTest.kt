package app.skerry.ui.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.shared.ssh.SshConfigHost
import app.skerry.shared.ssh.SshConfigParseResult
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.seededHosts
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_import_select_all
import app.skerry.ui.session.BroadcastController
import app.skerry.ui.session.BroadcastTarget
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The phone's tick rows, as a screen reader meets them (issue #228).
 *
 * Both sheets draw their checkbox as a Material Symbol ligature, and `Sym` clears its own semantics
 * — a row that carries only a click announces a button whose name is a word, with no on/off state
 * behind it. Desktop parity of the same rows lives in
 * [app.skerry.ui.session.BroadcastFormTest] and [app.skerry.ui.host.ImportFormTest].
 */
@OptIn(ExperimentalTestApi::class)
class MobileCheckRowTest {

    @Test
    fun `a broadcast target row reads as a checkbox with a state`() {
        val controller = BroadcastController()
        val targets = listOf(BroadcastTarget(id = "a", label = "web-01", production = false) { true })

        runForm({ MobileBroadcastSheet(controller, targets, onDismiss = {}) }) {
            onNodeWithText("web-01").assertIsToggleable().assertIsOff().performClick()
            waitForIdle()
            onNodeWithText("web-01").assertIsOn()
        }
        assertTrue(controller.isSelected("a"), "the row's toggle did not reach the controller")
    }

    @Test
    fun `an ssh config import row reads as a checkbox with a state`() {
        val hosts = seededHosts()
        val state = MobileDesignState()
        runForm({
            CompositionLocalProvider(LocalHosts provides hosts) {
                MobileSshImportSheet(state, PARSED)
            }
        }) {
            onNodeWithText("bastion").assertIsToggleable().assertIsOn().performClick()
            waitForIdle()
            onNodeWithText("bastion").assertIsOff()
        }
    }

    @Test
    fun `the mobile select-all row reads as a checkbox with a state`() {
        val hosts = seededHosts()
        val state = MobileDesignState()
        runForm({
            CompositionLocalProvider(LocalHosts provides hosts) {
                MobileSshImportSheet(state, PARSED)
            }
        }) {
            val selectAll = string(Res.string.conn_import_select_all)
            onNodeWithText(selectAll).assertIsToggleable().assertIsOn().performClick()
            waitForIdle()
            onNodeWithText(selectAll).assertIsOff()
        }
    }
}

private val PARSED = SshConfigParseResult(
    hosts = listOf(
        SshConfigHost("bastion", "bastion.example.com", 2222, "deploy", null, null),
        SshConfigHost("db", "db.example.com", 22, "root", null, null),
    ),
    warnings = emptyList(),
)
