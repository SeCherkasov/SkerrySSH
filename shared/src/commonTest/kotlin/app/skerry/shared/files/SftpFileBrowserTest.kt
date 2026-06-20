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
import kotlin.test.assertTrue

/**
 * Тесты адаптера SFTP→[FileBrowser]: проброс навигации/мутаций к [SftpClient], маппинг
 * [SftpEntry]→[FileItem] и [SftpException]→[FileBrowserException]. SFTP-клиент — записывающий фейк.
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
    fun `delete uses rmdir for directories and remove for files`() = runTest {
        browser().delete(FileItem("sub", "/d/sub", FileItemType.Directory, 0, 0))
        browser().delete(FileItem("a.txt", "/d/a.txt", FileItemType.File, 1, 0))

        assertTrue("rmdir:/d/sub" in client.calls)
        assertTrue("remove:/d/a.txt" in client.calls)
    }

    @Test
    fun `sftp errors are wrapped in FileBrowserException`() = runTest {
        client.failList = true

        assertFailsWith<FileBrowserException> { browser().list("/d") }
    }

    @Test
    fun `cancellation is not wrapped`() = runTest {
        client.cancelList = true

        // guard ловит только SftpException — отмена должна пролететь как есть, не став FileBrowserException.
        assertFailsWith<CancellationException> { browser().list("/d") }
    }
}

/** Записывающий фейк [SftpClient]: помнит вызовы в [calls], отдаёт настроенный листинг. */
private class RecordingSftp : SftpClient {
    val calls = mutableListOf<String>()
    var listResult: List<SftpEntry> = emptyList()
    var failList = false
    var cancelList = false

    override suspend fun list(path: String): List<SftpEntry> {
        if (cancelList) throw CancellationException("cancelled")
        if (failList) throw SftpException("boom")
        calls += "list:$path"
        return listResult
    }

    override suspend fun stat(path: String): SftpEntry? = null
    override suspend fun realpath(path: String): String {
        calls += "realpath:$path"
        return "/resolved$path"
    }

    override suspend fun read(path: String): ByteArray = ByteArray(0)
    override suspend fun write(path: String, data: ByteArray) {}
    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) {}
    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) {}
    override suspend fun mkdir(path: String) { calls += "mkdir:$path" }
    override suspend fun remove(path: String) { calls += "remove:$path" }
    override suspend fun rmdir(path: String) { calls += "rmdir:$path" }
    override suspend fun rename(from: String, to: String) { calls += "rename:$from->$to" }
    override suspend fun close() {}
}
