package app.skerry.ui.sync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import app.skerry.ui.desktop.runForm
import kotlin.test.Test

/**
 * The account id is not always this user's own typing: a device that joined by pairing learns it from the
 * server's answer to the pairing claim. It is drawn here in monospace next to a copy button, so a bidi
 * override in it draws as a different account — and this is the block a team owner is sent to identify
 * the person they are about to seal a team key to.
 *
 * Drawn filtered, copied raw: the copy is pasted into an invite and looked up verbatim, so a filtered copy
 * would be an id that names nobody. Only the drawn half is checkable here — nothing in this repo tests the
 * system clipboard.
 */
@OptIn(ExperimentalTestApi::class)
class AccountIdentityNameTest {

    @Test
    fun `a bidi override in the account id is flattened before it is drawn`() {
        runForm({ AccountIdentityBlock("ma\u202Eya@work.test") }) {
            onNodeWithText("maya@work.test").assertExists()
            onNodeWithText("ma\u202Eya@work.test").assertDoesNotExist()
        }
    }

    @Test
    fun `an ordinary account id is drawn as it is`() {
        runForm({ AccountIdentityBlock("maya@work.test") }) {
            onNodeWithText("maya@work.test").assertExists()
        }
    }
}
