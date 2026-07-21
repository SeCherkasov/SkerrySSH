package app.skerry.shared.files

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpException
import app.skerry.shared.sftp.SftpProgress
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the SFTP→[FileBrowser] adapter: delegating navigation/mutations to [SftpClient], mapping
 * [SftpEntry]→[FileItem] and [SftpException]→[FileBrowserException]. Uses a recording SFTP fake.
 */
class SftpFileBrowserTest {

    private val client = RecordingSftp()
    private fun browser() = SftpFileBrowser(client, label = "prod-web-01")

    @Test
    fun `label is exposed`() {
        assertEquals("prod-web-01", browser().label)
    }

    @Test
    fun `realpath is delegated`() = runTest {
        assertEquals("/resolved/x", browser().realpath("/x"))
        assertTrue("realpath:/x" in client.calls)
    }

    @Test
    fun `list maps sftp entries to file items`() = runTest {
        client.listResult = listOf(
            SftpEntry("sub", "/d/sub", SftpEntryType.Directory, 0, 100, 0),
            SftpEntry("a.txt", "/d/a.txt", SftpEntryType.File, 42, 200, 0),
        )

        val items = browser().list("/d")

        assertEquals(2, items.size)
        assertEquals(FileItemType.Directory, items[0].type)
        val file = items[1]
        assertEquals("a.txt", file.name)
        assertEquals("/d/a.txt", file.path)
        assertEquals(FileItemType.File, file.type)
        assertEquals(42, file.size)
        assertEquals(200, file.modifiedEpochSeconds)
    }

    @Test
    fun `mkdir and rename are delegated`() = runTest {
        browser().mkdir("/d/new")
        browser().rename("/d/a", "/d/b")

        assertTrue("mkdir:/d/new" in client.calls)
        assertTrue("rename:/d/a->/d/b" in client.calls)
    }

    @Test
    fun `delete uses rmdir for empty directories and remove for files`() = runTest {
        client.listings["/d/sub"] = emptyList() // explicitly empty directory: rmdir directly, no content removal
        browser().delete(FileItem("sub", "/d/sub", FileItemType.Directory, 0, 0))
        browser().delete(FileItem("a.txt", "/d/a.txt", FileItemType.File, 1, 0))

        assertEquals(listOf("list:/d/sub", "rmdir:/d/sub", "remove:/d/a.txt"), client.calls)
    }

    @Test
    fun `delete rejects a listing entry whose path escapes the directory`() = runTest {
        // Server returned a listing entry outside the directory being deleted — recursion must not delete it.
        client.listings["/d/sub"] = listOf(
            SftpEntry("evil", "/etc/passwd", SftpEntryType.File, 0, 0, 0),
        )

        assertFailsWith<FileBrowserException> {
            browser().delete(FileItem("sub", "/d/sub", FileItemType.Directory, 0, 0))
        }
        assertFalse("remove:/etc/passwd" in client.calls)
    }

    @Test
    fun `delete of a non-empty directory clears contents recursively then rmdir`() = runTest {
        // /d/sub: a file, a symlink, and a nested non-empty directory.
        client.listings["/d/sub"] = listOf(
            SftpEntry("a.txt", "/d/sub/a.txt", SftpEntryType.File, 1, 0, 0),
            SftpEntry("link", "/d/sub/link", SftpEntryType.Symlink, 0, 0, 0),
            SftpEntry("inner", "/d/sub/inner", SftpEntryType.Directory, 0, 0, 0),
        )
        client.listings["/d/sub/inner"] = listOf(
            SftpEntry("b.txt", "/d/sub/inner/b.txt", SftpEntryType.File, 2, 0, 0),
        )

        browser().delete(FileItem("sub", "/d/sub", FileItemType.Directory, 0, 0))

        // Contents are cleared before the directory itself; a nested directory before its parent;
        // a symlink is removed as a link (remove), without following its target.
        assertEquals(
            listOf(
                "list:/d/sub",
                "remove:/d/sub/a.txt",
                "remove:/d/sub/link",
                "list:/d/sub/inner",
                "remove:/d/sub/inner/b.txt",
                "rmdir:/d/sub/inner",
                "rmdir:/d/sub",
            ),
            client.calls,
        )
    }

    @Test
    fun `stat maps an entry and reports a missing path as null`() = runTest {
        client.stats["/d/a.txt"] = SftpEntry("a.txt", "/d/a.txt", SftpEntryType.File, 42, 200, 0)

        val item = browser().stat("/d/a.txt")

        assertEquals("a.txt", item?.name)
        assertEquals(42, item?.size)
        assertEquals(200, item?.modifiedEpochSeconds)
        assertEquals(FileItemType.File, item?.type)
        assertNull(browser().stat("/d/missing"))
    }

    @Test
    fun `readFile returns the file bytes`() = runTest {
        client.contents["/d/a.txt"] = "hello".encodeToByteArray()

        assertEquals("hello", browser().readFile("/d/a.txt", maxBytes = 1024).decodeToString())
    }

    @Test
    fun `readFile refuses a file larger than the cap without reading it`() = runTest {
        client.stats["/d/big.log"] = SftpEntry("big.log", "/d/big.log", SftpEntryType.File, 5_000, 0, 0)
        client.contents["/d/big.log"] = ByteArray(5_000)

        val e = assertFailsWith<FileBrowserException> { browser().readFile("/d/big.log", maxBytes = 1024) }

        assertEquals(FileBrowserFailure.TooLarge, e.failure)
        assertFalse("read:/d/big.log" in client.calls)
    }

    @Test
    fun `readFile refuses oversized content even when the server understated the size`() = runTest {
        // A server may report any size it likes: the cap must also hold against the bytes actually read.
        client.stats["/d/liar"] = SftpEntry("liar", "/d/liar", SftpEntryType.File, 1, 0, 0)
        client.contents["/d/liar"] = ByteArray(5_000)

        val e = assertFailsWith<FileBrowserException> { browser().readFile("/d/liar", maxBytes = 1024) }

        assertEquals(FileBrowserFailure.TooLarge, e.failure)
    }

    @Test
    fun `writeFile is delegated`() = runTest {
        browser().writeFile("/d/a.txt", "new".encodeToByteArray())

        assertTrue("write:/d/a.txt" in client.calls)
        assertEquals("new", client.contents["/d/a.txt"]?.decodeToString())
    }

    @Test
    fun `sftp errors are wrapped in FileBrowserException`() = runTest {
        client.failList = true

        // The sshj text is diagnostic detail only; the user-facing reason is the typed failure.
        val e = assertFailsWith<FileBrowserException> { browser().list("/d") }
        assertEquals(FileBrowserFailure.Sftp, e.failure)
    }

    @Test
    fun `cancellation is not wrapped`() = runTest {
        client.cancelList = true

        // The guard only catches SftpException — cancellation must pass through unwrapped.
        assertFailsWith<CancellationException> { browser().list("/d") }
    }
}

/** Recording fake [SftpClient]: tracks calls in [calls], returns a configured listing. */
private class RecordingSftp : SftpClient {
    val calls = mutableListOf<String>()
    var listResult: List<SftpEntry> = emptyList()
    val listings = mutableMapOf<String, List<SftpEntry>>()
    val stats = mutableMapOf<String, SftpEntry>()
    val contents = mutableMapOf<String, ByteArray>()
    var failList = false
    var cancelList = false

    override suspend fun list(path: String): List<SftpEntry> {
        if (cancelList) throw CancellationException("cancelled")
        if (failList) throw SftpException("boom")
        calls += "list:$path"
        return listings[path] ?: listResult
    }

    override suspend fun stat(path: String): SftpEntry? {
        calls += "stat:$path"
        return stats[path] ?: contents[path]?.let { SftpEntry(path.substringAfterLast('/'), path, SftpEntryType.File, it.size.toLong(), 0, 0) }
    }

    override suspend fun realpath(path: String): String {
        calls += "realpath:$path"
        return "/resolved$path"
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        calls += "read:$path"
        return contents[path] ?: throw SftpException("No file $path")
    }

    override suspend fun write(path: String, data: ByteArray) {
        calls += "write:$path"
        contents[path] = data
    }
    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) {}
    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) {}
    override suspend fun mkdir(path: String) { calls += "mkdir:$path" }
    override suspend fun remove(path: String) { calls += "remove:$path" }
    override suspend fun rmdir(path: String) { calls += "rmdir:$path" }
    override suspend fun rename(from: String, to: String) { calls += "rename:$from->$to" }
    override suspend fun close() {}
}
