package app.skerry.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The broadcast panel: one line typed once and sent to several live sessions at the same time.
 *
 * The blast radius is what makes it worth driving through the UI. A command must reach exactly the
 * selected sessions — no more — and a command aimed at a production host has to stop for a
 * confirmation first, which is the guard [BroadcastController.needsProductionConfirmation] exists
 * for and the panel is responsible for honouring.
 */
@OptIn(ExperimentalTestApi::class)
class BroadcastFormTest {

    @Test
    fun `the typed line reaches every selected session`() {
        val sent = mutableMapOf<String, MutableList<String>>()
        val targets = listOf(target("a", sent), target("b", sent))
        val controller = BroadcastController().apply { targets.forEach { toggle(it.id) } }

        runForm({ BroadcastPanel(controller, targets, onDismiss = {}) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput(COMMAND)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(listOf(COMMAND), sent["a"]?.map { it.trimEnd() })
        assertEquals(listOf(COMMAND), sent["b"]?.map { it.trimEnd() })
    }

    /** An unselected session is not a target: a broadcast must not reach a shell nobody ticked. */
    @Test
    fun `an unselected session receives nothing`() {
        val sent = mutableMapOf<String, MutableList<String>>()
        val targets = listOf(target("a", sent), target("b", sent))
        val controller = BroadcastController().apply { toggle("a") }

        runForm({ BroadcastPanel(controller, targets, onDismiss = {}) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput(COMMAND)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(listOf(COMMAND), sent["a"]?.map { it.trimEnd() })
        assertTrue(sent["b"].isNullOrEmpty(), "a session that was not selected received the broadcast")
    }

    /**
     * A destructive line aimed at production is held for a second question rather than fanned out
     * on the first press — the whole point of the guard, multiplied by the number of sessions.
     */
    @Test
    fun `a production target holds the send for a confirmation`() {
        val sent = mutableMapOf<String, MutableList<String>>()
        val targets = listOf(target("prod", sent, production = true))
        val controller = BroadcastController().apply { toggle("prod") }

        runForm({ BroadcastPanel(controller, targets, onDismiss = {}) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput(DESTRUCTIVE)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertTrue(sent["prod"].isNullOrEmpty(), "a destructive broadcast reached production unconfirmed")
    }

    /**
     * Issue #228: the tick beside a target is a Material Symbol ligature, and `Sym` clears its own
     * semantics — so a row carrying nothing but a click tells a screen reader neither that it is a
     * checkbox nor which sessions the next send will reach. The row is the checkbox.
     */
    @Test
    fun `a target row reads as a checkbox with a state`() {
        val targets = listOf(target("a", mutableMapOf()))
        val controller = BroadcastController()

        runForm({ BroadcastPanel(controller, targets, onDismiss = {}) }) {
            onNodeWithText("a").assertIsToggleable().assertIsOff().performClick()
            waitForIdle()
            onNodeWithText("a").assertIsOn()
        }
        assertTrue(controller.isSelected("a"), "the row's toggle did not reach the controller")
    }

    private fun target(id: String, log: MutableMap<String, MutableList<String>>, production: Boolean = false) =
        BroadcastTarget(id = id, label = id, production = production) { line ->
            log.getOrPut(id) { mutableListOf() } += line
            true
        }
}

/** Sent with the newline that runs it — trimmed in the assertions. */
private const val COMMAND = "uptime"

/** Recognized by the production guard, so the panel must stop and ask. */
private const val DESTRUCTIVE = "rm -rf /var/log"
