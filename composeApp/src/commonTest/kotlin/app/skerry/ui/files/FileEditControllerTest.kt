package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileContentBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PATH = "/etc/nginx/nginx.conf"

/**
 * Editor controller tests over an in-memory [FakeContentBrowser]. [UnconfinedTestDispatcher] runs
 * the controller's launches immediately, so state is settled after [advanceUntilIdle].
 */
class FileEditControllerTest {

    private val browser = FakeContentBrowser()
    private var savedCount = 0

    private fun TestScope.opened(
        content: ByteArray = "server {\n}\n".encodeToByteArray(),
        readOnly: Boolean = false,
        size: Long = content.size.toLong(),
        modified: Long = 100,
    ): FileEditController {
        browser.contents[PATH] = content
        browser.stats[PATH] = FileItem("nginx.conf", PATH, FileItemType.File, size, modified)
        val c = FileEditController(
            source = browser,
            item = FileItem("nginx.conf", PATH, FileItemType.File, size, modified),
            readOnly = readOnly,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            onSaved = { savedCount++ },
        )
        c.open()
        advanceUntilIdle()
        return c
    }

    @Test
    fun `open loads the file into a clean buffer`() = runTest {
        val c = opened()

        assertEquals("server {\n}\n", assertIs<FileEditState.Ready>(c.state).text)
        assertFalse(c.dirty)
        assertEquals("nginx.conf", c.name)
    }

    @Test
    fun `editing marks the buffer dirty and returning to the original clears it`() = runTest {
        val c = opened()

        c.edit("server { }\n")
        assertTrue(c.dirty)

        c.edit("server {\n}\n")
        assertFalse(c.dirty)
    }

    @Test
    fun `save writes the buffer and clears dirty`() = runTest {
        val c = opened()

        c.edit("worker_processes 4;\n")
        c.save()
        advanceUntilIdle()

        assertEquals("worker_processes 4;\n", browser.contents.getValue(PATH).decodeToString())
        assertFalse(c.dirty)
        assertEquals(1, savedCount)
        assertNull(c.saveFailure)
    }

    @Test
    fun `save is a no-op with an unchanged buffer`() = runTest {
        val c = opened()

        c.save()
        advanceUntilIdle()

        assertEquals(0, browser.writes)
        assertEquals(0, savedCount)
    }

    @Test
    fun `a read-only editor never writes`() = runTest {
        val c = opened(readOnly = true)

        c.edit("tampered")
        c.save()
        advanceUntilIdle()

        assertEquals(0, browser.writes)
        assertEquals("server {\n}\n", browser.contents.getValue(PATH).decodeToString())
    }

    @Test
    fun `CRLF line endings are normalized for editing and restored on save`() = runTest {
        val c = opened(content = "a\r\nb\r\n".encodeToByteArray())

        // The buffer the user sees has no stray \r — Compose text fields don't handle them.
        assertEquals("a\nb\n", assertIs<FileEditState.Ready>(c.state).text)

        c.edit("a\nb\nc\n")
        c.save()
        advanceUntilIdle()

        assertEquals("a\r\nb\r\nc\r\n", browser.contents.getValue(PATH).decodeToString())
    }

    @Test
    fun `LF line endings are kept as-is`() = runTest {
        val c = opened(content = "a\nb\n".encodeToByteArray())

        c.edit("a\nb\nc\n")
        c.save()
        advanceUntilIdle()

        assertEquals("a\nb\nc\n", browser.contents.getValue(PATH).decodeToString())
    }

    @Test
    fun `a file with NUL bytes is refused as binary`() = runTest {
        val c = opened(content = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01))

        assertEquals(FileEditFailure.Binary, assertIs<FileEditState.Failed>(c.state).failure)
    }

    @Test
    fun `a file that is not valid UTF-8 is refused as binary`() = runTest {
        // Lone 0xFF: decoding it leniently would silently replace bytes and corrupt the file on save.
        val c = opened(content = byteArrayOf(0x61, 0xFF.toByte(), 0x62))

        assertEquals(FileEditFailure.Binary, assertIs<FileEditState.Failed>(c.state).failure)
    }

    @Test
    fun `a file over the cap is refused`() = runTest {
        browser.failure = FileBrowserFailure.TooLarge
        val c = opened()

        assertEquals(FileEditFailure.TooLarge, assertIs<FileEditState.Failed>(c.state).failure)
    }

    @Test
    fun `a read error is surfaced as a failed state`() = runTest {
        browser.failure = FileBrowserFailure.Sftp
        val c = opened()

        assertEquals(FileEditFailure.Read, assertIs<FileEditState.Failed>(c.state).failure)
    }

    @Test
    fun `a write error keeps the buffer and reports the failure`() = runTest {
        val c = opened()
        c.edit("changed\n")
        browser.failure = FileBrowserFailure.Sftp

        c.save()
        advanceUntilIdle()

        assertEquals(FileEditFailure.Write, c.saveFailure)
        assertEquals("changed\n", assertIs<FileEditState.Ready>(c.state).text)
        assertTrue(c.dirty)
        assertEquals(0, savedCount)
    }

    @Test
    fun `a file changed underneath the editor raises a conflict instead of overwriting`() = runTest {
        val c = opened()
        c.edit("changed\n")
        // Someone else wrote the file while it was open.
        browser.stats[PATH] = FileItem("nginx.conf", PATH, FileItemType.File, 999, 500)

        c.save()
        advanceUntilIdle()

        assertTrue(c.conflict)
        assertEquals(0, browser.writes)
        assertEquals("server {\n}\n", browser.contents.getValue(PATH).decodeToString())
    }

    @Test
    fun `confirming a conflict overwrites the file`() = runTest {
        val c = opened()
        c.edit("changed\n")
        browser.stats[PATH] = FileItem("nginx.conf", PATH, FileItemType.File, 999, 500)
        c.save()
        advanceUntilIdle()

        c.confirmOverwrite()
        advanceUntilIdle()

        assertFalse(c.conflict)
        assertEquals("changed\n", browser.contents.getValue(PATH).decodeToString())
        assertFalse(c.dirty)
    }

    @Test
    fun `dismissing a conflict leaves the file and the buffer untouched`() = runTest {
        val c = opened()
        c.edit("changed\n")
        browser.stats[PATH] = FileItem("nginx.conf", PATH, FileItemType.File, 999, 500)
        c.save()
        advanceUntilIdle()

        c.dismissConflict()

        assertFalse(c.conflict)
        assertEquals(0, browser.writes)
        assertEquals("changed\n", assertIs<FileEditState.Ready>(c.state).text)
        assertTrue(c.dirty)
    }

    @Test
    fun `a second save after a successful one sees the new baseline and does not conflict`() = runTest {
        val c = opened()

        c.edit("one\n")
        c.save()
        advanceUntilIdle()
        c.edit("two\n")
        c.save()
        advanceUntilIdle()

        assertFalse(c.conflict)
        assertEquals("two\n", browser.contents.getValue(PATH).decodeToString())
        assertEquals(2, savedCount)
    }

    @Test
    fun `classic Mac CR line endings are normalized for editing and restored on save`() = runTest {
        val c = opened(content = "a\rb\r".encodeToByteArray())

        assertEquals("a\nb\n", assertIs<FileEditState.Ready>(c.state).text)

        c.edit("a\nb\nc\n")
        c.save()
        advanceUntilIdle()

        assertEquals("a\rb\rc\r", browser.contents.getValue(PATH).decodeToString())
    }

    @Test
    fun `a buffer with mixed endings is not left with stray carriage returns`() = runTest {
        val c = opened(content = "a\r\nb\nc\r".encodeToByteArray())

        assertEquals("a\nb\nc\n", assertIs<FileEditState.Ready>(c.state).text)
    }

    @Test
    fun `editing is ignored while a conflict is awaiting confirmation`() = runTest {
        val c = opened()
        c.edit("mine\n")
        browser.stats[PATH] = FileItem("nginx.conf", PATH, FileItemType.File, 999, 500)
        c.save()
        advanceUntilIdle()

        c.edit("typed into the buffer behind the dialog\n")

        // The pending write is of the buffer as it was when the conflict was raised.
        assertEquals("mine\n", assertIs<FileEditState.Ready>(c.state).text)
        c.confirmOverwrite()
        advanceUntilIdle()
        assertEquals("mine\n", browser.contents.getValue(PATH).decodeToString())
    }

    @Test
    fun `a failed stat after saving keeps conflict detection armed`() = runTest {
        val c = opened()
        c.edit("one\n")
        browser.statFails = true
        c.save()
        advanceUntilIdle()
        browser.statFails = false

        // The file changed on the source afterwards: the next save must still stop and ask.
        browser.stats[PATH] = FileItem("nginx.conf", PATH, FileItemType.File, 4242, 777)
        c.edit("two\n")
        c.save()
        advanceUntilIdle()

        assertTrue(c.conflict)
        assertEquals("one\n", browser.contents.getValue(PATH).decodeToString())
    }
}

/** In-memory [FileContentBrowser]; navigation isn't exercised by the editor. */
private class FakeContentBrowser : FileContentBrowser, FileBrowser {
    override val label = "prod-web-01"
    val contents = mutableMapOf<String, ByteArray>()
    val stats = mutableMapOf<String, FileItem>()
    var writes = 0

    /** When set, the next read/write fails with this reason. */
    var failure: FileBrowserFailure? = null

    /** When set, [stat] fails — the editor must not lose its conflict baseline over it. */
    var statFails = false

    override suspend fun realpath(path: String) = path
    override suspend fun list(path: String): List<FileItem> = emptyList()
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit

    override suspend fun stat(path: String): FileItem? {
        if (statFails) throw FileBrowserException(FileBrowserFailure.Sftp)
        return stats[path]
    }

    override suspend fun readFile(path: String, maxBytes: Long): ByteArray {
        failure?.let { throw FileBrowserException(it) }
        return contents[path] ?: throw FileBrowserException(FileBrowserFailure.Sftp)
    }

    override suspend fun writeFile(path: String, data: ByteArray) {
        failure?.let { throw FileBrowserException(it) }
        writes++
        contents[path] = data
        // A real source bumps mtime/size on write; the editor must re-baseline on it.
        stats[path] = FileItem(path.substringAfterLast('/'), path, FileItemType.File, data.size.toLong(), 900)
    }
}
