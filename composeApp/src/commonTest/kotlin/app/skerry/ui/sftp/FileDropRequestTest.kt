package app.skerry.ui.sftp

import app.skerry.shared.files.SftpFileBrowser
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.FilePaneState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What a drop does to the source pane's marks before, and after, the question it raises. */
class FileDropRequestTest {

    @Test
    fun `an unmarked row is marked alone for the question, and dismissing it puts the marks back`() = runTest {
        val c = started()
        c.selectOnly(c.entry("readme.txt"))
        c.toggle(c.entry("build.log"))

        val request = assertNotNull(c.transferRequestFor(FileDrop(ActivePane.Remote, listOf(c.entry("alpha")), move = true)))
        assertEquals(setOf(c.entry("alpha").path), c.selection)
        assertEquals(true, request.move)

        request.onAbandon()
        assertEquals(setOf(c.entry("readme.txt").path, c.entry("build.log").path), c.selection)
    }

    @Test
    fun `a marked set is carried as it is`() = runTest {
        val c = started()
        c.selectOnly(c.entry("readme.txt"))
        c.toggle(c.entry("build.log"))
        val marked = c.selection

        assertNotNull(c.transferRequestFor(FileDrop(ActivePane.Remote, c.selectedItems(), move = false)))
        assertEquals(marked, c.selection)
    }

    @Test
    fun `a row that left the listing mid-drag raises no question and keeps the marks`() = runTest {
        val c = started()
        val gone = c.entry("alpha")
        c.delete(gone)
        advanceUntilIdle()
        c.selectOnly(c.entry("readme.txt"))

        assertNull(c.transferRequestFor(FileDrop(ActivePane.Remote, listOf(gone), move = false)))
        assertEquals(setOf(c.entry("readme.txt").path), c.selection)
    }

    private fun TestScope.started(): FilePaneController {
        val fake = FakeSftpClient(startDir = HOME).apply {
            seedDir("$HOME/alpha")
            seedFile("$HOME/readme.txt", size = 11)
            seedFile("$HOME/build.log", size = 200)
        }
        return FilePaneController(SftpFileBrowser(fake, label = "prod"), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            .also { it.start(); advanceUntilIdle() }
    }

    private fun FilePaneController.entry(name: String) = (state as FilePaneState.Loaded).entries.first { it.name == name }
}

private const val HOME = "/home/skerry"
