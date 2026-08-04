package app.skerry.ui.files

import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.ui.sftp.FakeSftpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val LOCAL = "/local/home"
private const val REMOTE = "/remote/app"

/**
 * What a transfer will move, worked out before any bytes flow: directory order, which entries
 * become tasks, and the path-traversal guard against a hostile listing. The walk runs over
 * [FakeSftpClient] trees — no coordinator, no transfers.
 */
class TransferPlanTest {

    private fun remote() = FakeSftpClient(startDir = REMOTE).apply {
        seedDir("$REMOTE/proj")
        seedFile("$REMOTE/proj/top.txt", size = 5)
        seedDir("$REMOTE/proj/nested")
        seedFile("$REMOTE/proj/nested/deep.txt", size = 7)
        seedFile("$REMOTE/loose.txt", size = 3)
    }

    private fun item(name: String, type: FileItemType, path: String, size: Long = 0) =
        FileItem(name = name, path = path, type = type, size = size, modifiedEpochSeconds = 0)

    @Test
    fun `a file becomes one task under the destination directory`() = runTest {
        val plan = buildDownloadPlan(
            remote(),
            listOf(item("loose.txt", FileItemType.File, "$REMOTE/loose.txt", size = 3)),
            localDir = LOCAL,
            remoteDir = REMOTE,
        )

        assertTrue(plan.dirs.isEmpty())
        assertEquals(
            listOf(DownloadTask("loose.txt", "$REMOTE/loose.txt", "$LOCAL/loose.txt", 3)),
            plan.files,
        )
    }

    @Test
    fun `a directory is walked recursively, parents before children`() = runTest {
        val plan = buildDownloadPlan(
            remote(),
            listOf(item("proj", FileItemType.Directory, "$REMOTE/proj")),
            localDir = LOCAL,
            remoteDir = REMOTE,
        )

        // Creation order matters: the nested directory cannot be created before its parent.
        assertEquals(listOf("$LOCAL/proj", "$LOCAL/proj/nested"), plan.dirs)
        assertEquals(
            listOf("$LOCAL/proj/top.txt", "$LOCAL/proj/nested/deep.txt"),
            plan.files.map { it.localPath },
        )
    }

    @Test
    fun `a hostile listing name stops the walk instead of escaping the tree`() = runTest {
        val remote = remote().apply { seedFile("$REMOTE/proj/..\\evil.txt", size = 9) }

        val failure = assertFailsWith<FileBrowserException> {
            buildDownloadPlan(
                remote,
                listOf(item("proj", FileItemType.Directory, "$REMOTE/proj")),
                localDir = LOCAL,
                remoteDir = REMOTE,
            )
        }

        assertEquals(FileBrowserFailure.IllegalName, failure.failure)
    }

    @Test
    fun `symlinks are planned neither as files nor as directories`() = runTest {
        val plan = buildDownloadPlan(
            remote(),
            listOf(item("link", FileItemType.Symlink, "$REMOTE/link")),
            localDir = LOCAL,
            remoteDir = REMOTE,
        )

        assertTrue(plan.files.isEmpty() && plan.dirs.isEmpty())
    }

    @Test
    fun `an upload plan mirrors the download one, over the local browser`() = runTest {
        val local = FakeSftpClient(startDir = LOCAL).apply {
            seedDir("$LOCAL/sub")
            seedFile("$LOCAL/sub/inner.txt", size = 7)
        }
        val browser = SftpFileBrowser(local, "This Mac")

        val plan = buildUploadPlan(
            browser,
            listOf(item("sub", FileItemType.Directory, "$LOCAL/sub")),
            remoteDir = REMOTE,
        )

        assertEquals(listOf("$REMOTE/sub"), plan.dirs)
        assertEquals(
            listOf(UploadTask("inner.txt", "$LOCAL/sub/inner.txt", "$REMOTE/sub/inner.txt", 7)),
            plan.files,
        )
    }

    @Test
    fun `a hostile name in a local listing stops the upload walk too`() = runTest {
        // The upload walk carries its own copy of the guard; the download one being covered says
        // nothing about it.
        val local = FakeSftpClient(startDir = LOCAL).apply {
            seedDir("$LOCAL/sub")
            seedFile("$LOCAL/sub/..\\escape.txt", size = 4)
        }

        val failure = assertFailsWith<FileBrowserException> {
            buildUploadPlan(
                SftpFileBrowser(local, "This Mac"),
                listOf(item("sub", FileItemType.Directory, "$LOCAL/sub")),
                remoteDir = REMOTE,
            )
        }

        assertEquals(FileBrowserFailure.IllegalName, failure.failure)
    }

    @Test
    fun `a directory that is already there is not an error`() = runTest {
        // A repeat transfer walks into directories the previous run created; mkdir has no `-p`, so
        // the collision has to be tolerated or every second transfer of a tree fails.
        val remote = remote()

        ensureDir(SftpFileBrowser(remote, "prod"), "$REMOTE/proj")

        // Creating first and excusing the failure afterwards, not probing first: a listing that
        // says nothing is there is stale the moment it returns, and the ordering decides which of
        // the two errors the user is told about.
        assertEquals(1, remote.mkdirCalls)
    }

    @Test
    fun `a directory that cannot be created reports the mkdir failure, not the listing one`() = runTest {
        // The path is taken by a *file*: mkdir fails, the listing that would have excused it fails
        // too, and what the user hears has to be why the directory could not be made — the "no
        // directory" from the probe explains nothing.
        val browser = SftpFileBrowser(remote(), "prod")

        val failure = assertFailsWith<FileBrowserException> { ensureDir(browser, "$REMOTE/loose.txt") }

        assertEquals(FileBrowserFailure.Sftp, failure.failure)
        assertTrue(failure.detail.orEmpty().startsWith("Path taken"), "got: ${failure.detail}")
    }

    @Test
    fun `a delete path is rebuilt from the pane's directory, never from the listing`() {
        assertEquals("$REMOTE/a.txt", safeRemoteChild("a.txt", REMOTE))
        assertFailsWith<FileBrowserException> { safeRemoteChild("../a.txt", REMOTE) }
        assertFailsWith<FileBrowserException> { safeRemoteChild("..", REMOTE) }
    }
}
