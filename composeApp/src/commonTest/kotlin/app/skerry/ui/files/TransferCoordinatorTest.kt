package app.skerry.ui.files

import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.ui.sftp.DownloadTarget
import app.skerry.ui.sftp.FakeSftpClient
import app.skerry.ui.sftp.TransferDirection
import app.skerry.ui.sftp.UploadSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val LHOME = "/local/home"
private const val RHOME = "/remote/app"

/**
 * Transfer coordinator tests. The local pane runs over a "local" [FakeSftpClient] (an FS stand-in
 * that only sees transfers through its own tree); the remote pane and the transfer channel run
 * over a "remote" [FakeSftpClient]. An upload actually creates the file in the remote fake (its
 * `upload` seeds the file), and the re-listed remote pane shows it.
 */
class TransferCoordinatorTest {

    private fun TestScope.scope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun localFake() = FakeSftpClient(startDir = LHOME).apply {
        seedFile("$LHOME/a.txt", size = 10)
        seedFile("$LHOME/b.txt", size = 20)
        seedDir("$LHOME/sub")
        seedFile("$LHOME/sub/inner.txt", size = 7)
    }

    private fun remoteFake() = FakeSftpClient(startDir = RHOME).apply {
        seedFile("$RHOME/r.txt", size = 30)
    }

    private class Rig(
        val local: FilePaneController,
        val remote: FilePaneController,
        val localFake: FakeSftpClient,
        val remoteFake: FakeSftpClient,
        val coordinator: TransferCoordinator,
    )

    private fun TestScope.rig(
        local: FakeSftpClient = localFake(),
        remote: FakeSftpClient = remoteFake(),
    ): Rig {
        val localBrowser = SftpFileBrowser(local, "This Mac")
        val remoteBrowser = SftpFileBrowser(remote, "prod-web-01")
        val localCtl = FilePaneController(localBrowser, scope())
        val remoteCtl = FilePaneController(remoteBrowser, scope())
        localCtl.start(); remoteCtl.start(); advanceUntilIdle()
        val coordinator = TransferCoordinator(remote, localCtl, localBrowser, remoteCtl, remoteBrowser, scope())
        return Rig(localCtl, remoteCtl, local, remote, coordinator)
    }

    private fun FilePaneController.entry(name: String) =
        (state as FilePaneState.Loaded).entries.first { it.name == name }

    @Test
    fun `uploadSelection sends selected local files into the remote directory and refreshes it`() = runTest {
        val r = rig()
        r.local.toggle(r.local.entry("a.txt"))
        r.local.toggle(r.local.entry("b.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val remoteNames = (r.remote.state as FilePaneState.Loaded).entries.map { it.name }
        assertTrue("a.txt" in remoteNames && "b.txt" in remoteNames)
        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertTrue(r.local.selection.isEmpty())
    }

    @Test
    fun `uploadSelection uploads a directory recursively, recreating the remote tree`() = runTest {
        val r = rig()
        r.local.toggle(r.local.entry("sub"))

        r.coordinator.uploadSelection()
        advanceUntilIdle()

        // Remote directory is recreated along with the nested file.
        val remoteTop = r.remoteFake.list(RHOME).map { it.name }
        assertTrue("sub" in remoteTop, "expected remote directory sub, have: $remoteTop")
        val remoteSub = r.remoteFake.list("$RHOME/sub").map { it.name }
        assertTrue("inner.txt" in remoteSub, "expected remote sub/inner.txt, have: $remoteSub")
        assertEquals("$LHOME/sub/inner.txt" to "$RHOME/sub/inner.txt", r.remoteFake.lastUpload)
        assertTrue(r.local.selection.isEmpty())
    }

    @Test
    fun `downloadSelection downloads selected remote files into the local directory`() = runTest {
        val r = rig()
        r.remote.toggle(r.remote.entry("r.txt"))

        r.coordinator.downloadSelection()
        advanceUntilIdle()

        assertEquals("$RHOME/r.txt" to "$LHOME/r.txt", r.remoteFake.lastDownload)
        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertTrue(r.remote.selection.isEmpty())
    }

    @Test
    fun `downloadSelection downloads a directory recursively, recreating the local tree`() = runTest {
        val remote = remoteFake().apply {
            seedDir("$RHOME/proj")
            seedFile("$RHOME/proj/top.txt", size = 5)
            seedDir("$RHOME/proj/nested")
            seedFile("$RHOME/proj/nested/deep.txt", size = 7)
        }
        val r = rig(remote = remote)
        r.remote.toggle(r.remote.entry("proj"))

        r.coordinator.downloadSelection()
        advanceUntilIdle()

        // Local subdirectories are recreated.
        val localTop = r.localFake.list(LHOME).map { it.name }
        assertTrue("proj" in localTop, "expected local directory proj, have: $localTop")
        val localNested = r.localFake.list("$LHOME/proj").map { it.name }
        assertTrue("nested" in localNested, "expected local directory proj/nested, have: $localNested")

        // Both files in the tree are downloaded to their local paths.
        assertTrue("$RHOME/proj/top.txt" to "$LHOME/proj/top.txt" in r.remoteFake.downloads)
        assertTrue("$RHOME/proj/nested/deep.txt" to "$LHOME/proj/nested/deep.txt" in r.remoteFake.downloads)

        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertTrue(r.remote.selection.isEmpty())
    }

    @Test
    fun `downloadSelection recreates an empty remote directory locally with no file transfers`() = runTest {
        val remote = remoteFake().apply { seedDir("$RHOME/empty") }
        val r = rig(remote = remote)
        r.remote.toggle(r.remote.entry("empty"))

        r.coordinator.downloadSelection()
        advanceUntilIdle()

        assertTrue("empty" in r.localFake.list(LHOME).map { it.name })
        assertTrue(r.remoteFake.downloads.isEmpty())
        assertEquals(TransferState.Idle, r.coordinator.transfer)
    }

    @Test
    fun `downloadSelection rejects a malicious listing name that escapes the local directory`() = runTest {
        // Untrusted server returns a listing name containing a Windows path separator: path traversal attempt.
        val remote = remoteFake().apply {
            seedDir("$RHOME/proj")
            seedFile("$RHOME/proj/..\\evil.txt", size = 9)
        }
        val r = rig(remote = remote)
        r.remote.toggle(r.remote.entry("proj"))

        r.coordinator.downloadSelection()
        advanceUntilIdle()

        val failed = assertIs<TransferState.Failed>(r.coordinator.transfer)
        assertEquals(FileTransferFailure.IllegalName, failed.failure)
        assertTrue(r.remoteFake.downloads.none { it.first.endsWith("evil.txt") })
    }

    /** Test target for "Save to..." downloads: records the staging path and finalize/discard. */
    private class FakeDownloadTarget(
        override val displayName: String,
        override val stagingPath: String,
        private val finalizeError: String? = null,
    ) : DownloadTarget {
        var finalized = false
        var discarded = false
        override suspend fun finalize() {
            finalizeError?.let { throw RuntimeException(it) }
            finalized = true
        }
        override suspend fun discard() { discarded = true }
    }

    @Test
    fun `downloadToTarget streams a remote file into the picked target and finalizes it`() = runTest {
        val r = rig()
        val target = FakeDownloadTarget("r.txt", "/staging/r.txt")

        r.coordinator.downloadToTarget(r.remote.entry("r.txt"), target)
        advanceUntilIdle()

        assertEquals("$RHOME/r.txt" to "/staging/r.txt", r.remoteFake.lastDownload)
        assertTrue(target.finalized)
        assertEquals(TransferState.Idle, r.coordinator.transfer)
    }

    @Test
    fun `downloadToTarget discards the target and reports Failed when finalize fails`() = runTest {
        val r = rig()
        val target = FakeDownloadTarget("r.txt", "/staging/r.txt", finalizeError = "no space")

        r.coordinator.downloadToTarget(r.remote.entry("r.txt"), target)
        advanceUntilIdle()

        assertIs<TransferState.Failed>(r.coordinator.transfer)
        assertTrue(target.discarded)
    }

    @Test
    fun `downloadToTarget ignores directories`() = runTest {
        val r = rig()
        val dir = FileItem("sub", "$RHOME/sub", FileItemType.Directory, 0, 0)
        val target = FakeDownloadTarget("sub", "/staging/sub")

        r.coordinator.downloadToTarget(dir, target)
        advanceUntilIdle()

        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertTrue(!target.finalized && r.remoteFake.lastDownload == null)
    }

    @Test
    fun `transfer exposes active progress with file counts while running`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.local.toggle(r.local.entry("b.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle() // blocks on the gate at the first file

        val active = assertIs<TransferState.Active>(r.coordinator.transfer)
        assertEquals(TransferDirection.Upload, active.direction)
        assertEquals(1, active.fileIndex)
        assertEquals(2, active.fileCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TransferState.Idle, r.coordinator.transfer)
    }

    @Test
    fun `uploadSource uploads a picked file into the remote directory and refreshes it`() = runTest {
        val r = rig()
        val source = object : UploadSource {
            override val name = "picked.txt"
            override val stagingPath = "/tmp/picked.txt"
            var cleaned = false
            override suspend fun cleanup() { cleaned = true }
        }

        r.coordinator.uploadSource(source)
        advanceUntilIdle()

        val remoteNames = (r.remote.state as FilePaneState.Loaded).entries.map { it.name }
        assertTrue("picked.txt" in remoteNames)
        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertTrue(source.cleaned)
    }

    @Test
    fun `empty selection is a no-op`() = runTest {
        val r = rig()
        r.coordinator.uploadSelection()
        advanceUntilIdle()
        assertEquals(TransferState.Idle, r.coordinator.transfer)
    }

    @Test
    fun `moveSelection from local uploads the files then deletes the local sources`() = runTest {
        val r = rig()
        r.local.toggle(r.local.entry("a.txt"))
        r.local.toggle(r.local.entry("b.txt"))

        r.coordinator.moveSelection(fromLocal = true)
        advanceUntilIdle()

        val remoteNames = (r.remote.state as FilePaneState.Loaded).entries.map { it.name }
        assertTrue("a.txt" in remoteNames && "b.txt" in remoteNames, "expected on remote, have: $remoteNames")
        val localNames = (r.local.state as FilePaneState.Loaded).entries.map { it.name }
        assertTrue("a.txt" !in localNames && "b.txt" !in localNames, "expected deletion from local, have: $localNames")
        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertTrue(r.local.selection.isEmpty())
    }

    @Test
    fun `moveSelection from local moves a directory recursively then deletes it`() = runTest {
        val r = rig()
        r.local.toggle(r.local.entry("sub"))

        r.coordinator.moveSelection(fromLocal = true)
        advanceUntilIdle()

        assertTrue("sub" in r.remoteFake.list(RHOME).map { it.name })
        assertTrue("inner.txt" in r.remoteFake.list("$RHOME/sub").map { it.name })
        assertTrue("sub" !in (r.local.state as FilePaneState.Loaded).entries.map { it.name })
        assertEquals(TransferState.Idle, r.coordinator.transfer)
    }

    @Test
    fun `moveSelection from remote downloads then deletes the remote source`() = runTest {
        val r = rig()
        r.remote.toggle(r.remote.entry("r.txt"))

        r.coordinator.moveSelection(fromLocal = false)
        advanceUntilIdle()

        assertEquals("$RHOME/r.txt" to "$LHOME/r.txt", r.remoteFake.lastDownload)
        val remoteNames = (r.remote.state as FilePaneState.Loaded).entries.map { it.name }
        assertTrue("r.txt" !in remoteNames, "expected deletion from remote, have: $remoteNames")
        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertTrue(r.remote.selection.isEmpty())
    }

    @Test
    fun `moveSelection moves a remote directory then deletes it`() = runTest {
        val remote = remoteFake().apply {
            seedDir("$RHOME/proj")
            seedFile("$RHOME/proj/top.txt", size = 5)
        }
        val r = rig(remote = remote)
        r.remote.toggle(r.remote.entry("proj"))

        r.coordinator.moveSelection(fromLocal = false)
        advanceUntilIdle()

        assertTrue("proj" in r.localFake.list(LHOME).map { it.name })
        assertTrue("proj" !in (r.remote.state as FilePaneState.Loaded).entries.map { it.name })
        assertEquals(TransferState.Idle, r.coordinator.transfer)
    }

    @Test
    fun `moveSelection keeps the source when the transfer fails`() = runTest {
        val remote = remoteFake().apply { uploadError = "disk full" }
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))

        r.coordinator.moveSelection(fromLocal = true)
        advanceUntilIdle()

        assertIs<TransferState.Failed>(r.coordinator.transfer)
        assertTrue("a.txt" in (r.local.state as FilePaneState.Loaded).entries.map { it.name })
    }

    @Test
    fun `overwrite upload keeps the destination directory captured when the dialog was shown`() = runTest {
        // TOCTOU: the name conflict is computed when the dialog is shown; navigating the remote
        // pane while it's open must not redirect the overwrite to a different directory.
        val remote = remoteFake().apply {
            seedFile("$RHOME/a.txt", size = 3)
            seedDir("$RHOME/sub")
        }
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))

        r.coordinator.uploadSelection()
        assertNotNull(r.coordinator.overwrite, "expected an Overwrite dialog")

        // While the dialog is open, the user navigates the remote pane into a subdirectory.
        r.remote.open(r.remote.entry("sub"))
        advanceUntilIdle()
        assertEquals("$RHOME/sub", r.remote.path)

        r.coordinator.resolveOverwrite(true)
        advanceUntilIdle()

        assertEquals("$LHOME/a.txt" to "$RHOME/a.txt", r.remoteFake.lastUpload,
            "overwrite must target the directory the conflict was computed for")
    }

    @Test
    fun `overwrite download keeps the local destination captured when the dialog was shown`() = runTest {
        val local = localFake().apply { seedFile("$LHOME/r.txt", size = 3) }
        val r = rig(local = local)
        r.remote.toggle(r.remote.entry("r.txt"))

        r.coordinator.downloadSelection()
        assertNotNull(r.coordinator.overwrite, "expected an Overwrite dialog")

        // While the dialog is open, the user navigates the local pane into a subdirectory.
        r.local.open(r.local.entry("sub"))
        advanceUntilIdle()
        assertEquals("$LHOME/sub", r.local.path)

        r.coordinator.resolveOverwrite(true)
        advanceUntilIdle()

        assertEquals("$RHOME/r.txt" to "$LHOME/r.txt", r.remoteFake.lastDownload,
            "overwrite must target the directory the conflict was computed for")
    }

    @Test
    fun `a failed transfer surfaces as Failed`() = runTest {
        val remote = remoteFake().apply { uploadError = "disk full" }
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle()

        // The library text ("disk full") never reaches the bar — only the typed reason does.
        val failed = assertIs<TransferState.Failed>(r.coordinator.transfer)
        assertEquals(FileTransferFailure.Transfer, failed.failure)
    }
}
