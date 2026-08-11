package app.skerry.ui.connection

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import app.skerry.shared.host.Host
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_connect_to
import kotlin.test.Test

/**
 * The host a password prompt says it is about to authenticate to.
 *
 * This is the screen where the answer is a secret, so the name on it decides which machine gets the
 * password. A shared profile was named by a team member and the sync server never validated it; the
 * phone sheet is pinned by [app.skerry.ui.mobile.MobilePeerNameTest], this is its desktop twin.
 *
 * Written as escapes, never as the characters themselves.
 */
@OptIn(ExperimentalTestApi::class)
class PasswordDialogNameTest {

    @Test
    fun `the prompt names the host flattened`() = runForm({
        DesktopPasswordDialog(host = sharedHost(), onDismiss = {}, onConnect = {})
    }) {
        onNodeWithText(string(Res.string.shell_connect_to, FLATTENED)).assertIsDisplayed()
        onNodeWithText(string(Res.string.shell_connect_to, SPOOFED)).assertDoesNotExist()
        // The address line is the shared helper's, so a container or local profile reads right too.
        onNodeWithText("root@10.0.0.5:22").assertIsDisplayed()
    }
}

private fun sharedHost(): Host =
    Host(id = "h-shared", label = SPOOFED, address = "10.0.0.5", port = 22, username = "root")

private const val SPOOFED = "web\u202E10-"
private const val FLATTENED = "web10-"
