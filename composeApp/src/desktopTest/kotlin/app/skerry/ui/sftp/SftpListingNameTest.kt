package app.skerry.ui.sftp

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_unprintable_name
import app.skerry.ui.files.FilePaneController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test

/**
 * What the remote listing draws for a name the server chose.
 *
 * A directory listing is the one screen made entirely of strings the other side wrote, and it is
 * also the screen the user clicks to download and to open. A bidi override inside a name reverses
 * the tail of it in every layout: the fixture below is drawn as `invoiceexe.png` while the file it
 * opens ends in `.exe`, so the extension the eye reads is not the extension on disk. Zero-width
 * characters make two different files draw as one — `archive.\u200Bzip` and `archive.zip` are the
 * same row to the eye and a different file to the server.
 *
 * Written as escapes, never as the characters themselves: they are invisible in a diff, and a
 * reviewer could not tell the fixture from the expectation.
 */
@OptIn(ExperimentalTestApi::class)
class SftpListingNameTest {

    @Test
    fun `a bidi override in a remote file name never reaches the row`() = listing(SPOOFED) {
        onNodeWithText(FLATTENED).assertIsDisplayed()
        onNodeWithText(SPOOFED).assertDoesNotExist()
    }

    @Test
    fun `the zero-width formatters go too`() = listing("re\u200Bport\u200D.log") {
        onNodeWithText("report.log").assertIsDisplayed()
    }

    /** The extension is what the icon is picked from, so a formatter inside it must not survive. */
    @Test
    fun `a formatter inside the extension is dropped`() = listing("archive.\u200Bzip") {
        onNodeWithText("archive.zip").assertIsDisplayed()
    }

    /** A name that is nothing but such characters would otherwise leave the row nameless. */
    @Test
    fun `a name that filters away leaves the row named`() = listing("\u202E\u200B\uFEFF") {
        onNodeWithText(string(Res.string.sftp_unprintable_name)).assertIsDisplayed()
    }

    @Test
    fun `an ordinary name is drawn as the server sent it`() = listing("nginx.conf") {
        onNodeWithText("nginx.conf").assertIsDisplayed()
    }

    /** One remote entry on screen, drawn by the live listing the session panel uses. */
    private fun listing(name: String, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val pane = FilePaneController(StubBrowser, scope)
        val entries = listOf(
            FileItem(name = name, path = "/var/www/$name", type = FileItemType.File, size = 12, modifiedEpochSeconds = 0),
        )
        try {
            runForm({
                LivePaneList(
                    pane = pane,
                    entries = entries,
                    mono = FontFamily.Monospace,
                    listState = rememberLazyListState(),
                    active = true,
                    onActivate = {},
                )
            }, body)
        } finally {
            scope.cancel()
        }
    }
}

/** The listing under test is handed its entries directly; the browser is only what the pane holds. */
private object StubBrowser : FileBrowser {
    override val label: String = "stub"
    override suspend fun realpath(path: String): String = "/"
    override suspend fun list(path: String): List<FileItem> = emptyList()
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit
}

/** U+202E before the tail: the row draws as `invoiceexe.png`, the file it opens is `invoicegnp.exe`. */
private const val SPOOFED = "invoice\u202Egnp.exe"

private const val FLATTENED = "invoicegnp.exe"
