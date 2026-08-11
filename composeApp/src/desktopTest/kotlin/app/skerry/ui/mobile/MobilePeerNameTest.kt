package app.skerry.ui.mobile

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.host.Host
import app.skerry.ui.desktop.runForm
import app.skerry.ui.files.FilePaneController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test

/**
 * The Android half of the same fix: a name a server chose and a name a team member chose, drawn on
 * the phone.
 *
 * Parity is the point. The desktop listing and the desktop password dialog filter these strings;
 * if the phone does not, the same directory and the same profile spoof exactly as before on the
 * platform that has no hover, no status bar and less room to notice anything is off.
 *
 * Written as escapes, never as the characters themselves.
 */
@OptIn(ExperimentalTestApi::class)
class MobilePeerNameTest {

    @Test
    fun `the phone listing draws the server's name flattened`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val pane = FilePaneController(OneEntryBrowser, scope)
        pane.start()
        try {
            runForm({
                MobileLivePane(
                    pane = pane,
                    mono = FontFamily.Monospace,
                    onTransfer = {},
                    onDownloadHere = null,
                    onOpenEditor = { _, _ -> },
                    modifier = Modifier,
                )
            }) {
                onNodeWithText(FLAT_FILE).assertIsDisplayed()
                onNodeWithText(SPOOFED_FILE).assertDoesNotExist()
            }
        } finally {
            scope.cancel()
        }
    }

    /** The screen where the answer is a secret: it must name the machine it is about to be sent to. */
    @Test
    fun `the phone password sheet names the host flattened`() = runForm({
        MobilePasswordSheet(host = sharedHost(), onDismiss = {}, onConnect = {})
    }) {
        onNodeWithText(FLAT_HOST).assertIsDisplayed()
        onNodeWithText(SPOOFED_HOST).assertDoesNotExist()
        // The address line is the helper's, not a hand-rolled one — a container or local profile
        // would otherwise read wrong here and nowhere else.
        onNodeWithText("root@10.0.0.5:22").assertIsDisplayed()
    }
}

private fun sharedHost(): Host =
    Host(id = "h-shared", label = SPOOFED_HOST, address = "10.0.0.5", port = 22, username = "root")

/** One canned listing, the way the desktop test stubs its own. */
private object OneEntryBrowser : FileBrowser {
    override val label: String = "stub"
    override suspend fun realpath(path: String): String = "/"
    override suspend fun list(path: String): List<FileItem> = listOf(
        FileItem(SPOOFED_FILE, "/var/www/$SPOOFED_FILE", FileItemType.File, 12, 0),
    )
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit
}

private const val SPOOFED_FILE = "invoice\u202Egnp.exe"
private const val FLAT_FILE = "invoicegnp.exe"
private const val SPOOFED_HOST = "web\u202E10-"
private const val FLAT_HOST = "web10-"
