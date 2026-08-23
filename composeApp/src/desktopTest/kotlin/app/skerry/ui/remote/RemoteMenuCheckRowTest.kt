package app.skerry.ui.remote

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The remote-desktop session menu's tick rows (issue #228).
 *
 * Same shape as the broadcast and import rows covered in
 * [app.skerry.ui.session.BroadcastFormTest] and [app.skerry.ui.mobile.MobileCheckRowTest]: the tick
 * is a Material Symbol ligature and `Sym` clears its own semantics, so the row is the only node
 * that can say it is a checkbox and which way it is set. This one is reached from a menu the shell
 * tests never open, which is how it stayed the uncovered member of the set.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteMenuCheckRowTest {

    @Test
    fun `a menu check row reads as a checkbox and flips`() {
        var toggles = 0
        val checked = mutableStateOf(false)
        runForm({
            CheckRow(LABEL, checked.value) { toggles++; checked.value = !checked.value }
        }) {
            onNodeWithText(LABEL).assertIsToggleable().assertIsOff().performClick()
            waitForIdle()
            onNodeWithText(LABEL).assertIsOn()
        }
        assertTrue(toggles == 1, "the row's toggle reached the caller $toggles times, not once")
    }
}

private const val LABEL = "View only"
