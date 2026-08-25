package app.skerry.ui.files

import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.files.MAX_TREE_DEPTH
import app.skerry.shared.files.MAX_TREE_ENTRIES
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
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
    fun `a nested listing that repeats a name is planned once`() = runTest {
        // The walk reads the SFTP client directly, not the de-duplicated browser, and it builds every
        // local path from the entry's name — so a repeat inside a directory being downloaded plans two
        // tasks writing the same local file, and the second silently overwrites the first while the
        // progress bar counts two.
        val entry = SftpEntry("dup.txt", "$REMOTE/proj/dup.txt", SftpEntryType.File, 5, 0, 0b110_100_100)
        val remote = remote().apply {
            listAnswer = { path -> if (path == "$REMOTE/proj") listOf(entry, entry) else null }
        }

        val plan = buildDownloadPlan(
            remote,
            listOf(item("proj", FileItemType.Directory, "$REMOTE/proj")),
            localDir = LOCAL,
            remoteDir = REMOTE,
        )

        assertEquals(listOf("$LOCAL/proj/dup.txt"), plan.files.map { it.localPath })
    }

    @Test
    fun `a directory that lists itself as its own child stops the walk`() = runTest {
        // A bind mount that contains itself, a FUSE mount, or a server that simply says so: the tree
        // has no bottom, and the walk runs until the stack or the heap gives out — before a byte has
        // moved, and outside the transfer's own error handling (#306).
        val remote = remote().apply {
            seedDir("$REMOTE/loop")
            listAnswer = { path ->
                if (path.endsWith("/loop")) {
                    listOf(SftpEntry("loop", "$path/loop", SftpEntryType.Directory, 0, 0, 0b111_101_101))
                } else {
                    null
                }
            }
        }

        val failure = assertFailsWith<FileBrowserException> {
            buildDownloadPlan(
                remote,
                listOf(item("loop", FileItemType.Directory, "$REMOTE/loop")),
                localDir = LOCAL,
                remoteDir = REMOTE,
            )
        }

        assertEquals(FileBrowserFailure.TreeTooLarge, failure.failure)
    }

    @Test
    fun `an upload walk over a local loop stops the same way`() = runTest {
        // The upload walk carries its own copy of the guard, like the name check above.
        val local = FakeSftpClient(startDir = LOCAL).apply {
            seedDir("$LOCAL/loop")
            listAnswer = { path ->
                if (path.endsWith("/loop")) {
                    listOf(SftpEntry("loop", "$path/loop", SftpEntryType.Directory, 0, 0, 0b111_101_101))
                } else {
                    null
                }
            }
        }

        val failure = assertFailsWith<FileBrowserException> {
            buildUploadPlan(
                SftpFileBrowser(local, "This Mac"),
                listOf(item("loop", FileItemType.Directory, "$LOCAL/loop")),
                remoteDir = REMOTE,
            )
        }

        assertEquals(FileBrowserFailure.TreeTooLarge, failure.failure)
    }

    @Test
    fun `a plan bigger than the cap stops before it is built`() = runTest {
        // Depth is not the only way out of a bounded plan: one directory whose listing never ends
        // is flat and still unbounded. The depth cap says nothing about it.
        val remote = remote().apply {
            seedDir("$REMOTE/wide")
            val wide = (0..MAX_TREE_ENTRIES).map {
                SftpEntry("f$it", "$REMOTE/wide/f$it", SftpEntryType.File, 1, 0, 0b110_100_100)
            }
            listAnswer = { path -> if (path == "$REMOTE/wide") wide else null }
        }

        val failure = assertFailsWith<FileBrowserException> {
            buildDownloadPlan(
                remote,
                listOf(item("wide", FileItemType.Directory, "$REMOTE/wide")),
                localDir = LOCAL,
                remoteDir = REMOTE,
            )
        }

        assertEquals(FileBrowserFailure.TreeTooLarge, failure.failure)
    }

    @Test
    fun `a tree exactly as wide as the cap allows is still planned whole`() = runTest {
        // The other half of the same boundary: the directory itself is one of the entries the walk
        // takes on, so the last file the cap allows is the one that makes the count exact.
        val remote = remote().apply {
            seedDir("$REMOTE/wide")
            val wide = (1 until MAX_TREE_ENTRIES).map {
                SftpEntry("f$it", "$REMOTE/wide/f$it", SftpEntryType.File, 1, 0, 0b110_100_100)
            }
            listAnswer = { path -> if (path == "$REMOTE/wide") wide else null }
        }

        val plan = buildDownloadPlan(
            remote,
            listOf(item("wide", FileItemType.Directory, "$REMOTE/wide")),
            localDir = LOCAL,
            remoteDir = REMOTE,
        )

        assertEquals(MAX_TREE_ENTRIES - 1, plan.files.size)
    }

    @Test
    fun `a tree exactly as deep as the cap allows is still planned whole`() = runTest {
        // The caps bound a tree that has no bottom, not a tree anyone keeps: the last level the cap
        // allows has to go through, or the bound is one level tighter than it says it is.
        val remote = remote()
        val deepest = (1..MAX_TREE_DEPTH).fold(REMOTE) { dir, level -> "$dir/d$level".also { remote.seedDir(it) } }
        remote.seedFile("$deepest/leaf.txt", size = 1)

        val plan = buildDownloadPlan(
            remote,
            listOf(item("d1", FileItemType.Directory, "$REMOTE/d1")),
            localDir = LOCAL,
            remoteDir = REMOTE,
        )

        assertEquals(MAX_TREE_DEPTH, plan.dirs.size)
        assertEquals(1, plan.files.size)
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
