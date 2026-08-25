package app.skerry.ui.sftp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

private const val DIR = "/var/www"

/**
 * What the remote listing does with a directory that names one entry twice.
 *
 * `LazyColumn` keys every row through `SaveableStateHolder`, which refuses a duplicate key with an
 * `IllegalArgumentException` — thrown during composition, so it takes the window (desktop) or the
 * activity (Android) with it. The rows are keyed by the path the server reported, and a repeated
 * name in one directory is one packet away: an overlay filesystem, a buggy server, or one that
 * simply says so.
 *
 * The test goes through [SftpFileBrowser] rather than handing [LivePaneList] its entries, because
 * the join is the thing under test: what the browser hands out has to be usable as a row key.
 */
@OptIn(ExperimentalTestApi::class)
class SftpListingKeyTest {

    @Test
    fun `a listing that names one entry twice draws one row instead of taking the panel down`() {
        val entries = listing(
            SftpEntry("dup.txt", "$DIR/dup.txt", SftpEntryType.File, 42, 0, 0b110_100_100),
            SftpEntry("dup.txt", "$DIR/dup.txt", SftpEntryType.File, 42, 0, 0b110_100_100),
        )

        renderPaneList(entries) { onAllNodesWithText("dup.txt").assertCountEquals(1) }
    }

    @Test
    fun `entries the user can tell apart are all drawn`() {
        val entries = listing(
            SftpEntry("one.txt", "$DIR/one.txt", SftpEntryType.File, 1, 0, 0b110_100_100),
            SftpEntry("two.txt", "$DIR/two.txt", SftpEntryType.File, 2, 0, 0b110_100_100),
        )

        renderPaneList(entries) {
            onAllNodesWithText("one.txt").assertCountEquals(1)
            onAllNodesWithText("two.txt").assertCountEquals(1)
        }
    }

    /** The listing [DIR] answers with, as the panel would receive it — through the SFTP browser. */
    private fun listing(vararg answer: SftpEntry): List<FileItem> {
        val client = FakeSftpClient(startDir = DIR).apply {
            listAnswer = { path -> if (path == DIR) answer.toList() else null }
        }
        return runBlocking { SftpFileBrowser(client, "prod-web-01").list(DIR) }
    }
}
