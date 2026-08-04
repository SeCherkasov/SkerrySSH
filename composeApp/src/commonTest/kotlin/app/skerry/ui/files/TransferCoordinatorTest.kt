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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel

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
        now: () -> Long = { 0L },
    ): Rig {
        val localBrowser = SftpFileBrowser(local, "This Mac")
        val remoteBrowser = SftpFileBrowser(remote, "prod-web-01")
        val localCtl = FilePaneController(localBrowser, scope())
        val remoteCtl = FilePaneController(remoteBrowser, scope())
        localCtl.start(); remoteCtl.start(); advanceUntilIdle()
        val coordinator = TransferCoordinator(remote, localCtl, localBrowser, remoteCtl, remoteBrowser, scope(), now)
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

    @Test
    fun `openEditor loads the cursored remote file through the pane's source`() = runTest {
        val remote = remoteFake().apply { seedContent("$RHOME/nginx.conf", "server {}\n") }
        val r = rig(remote = remote)
        r.remote.refresh(); advanceUntilIdle()

        val editor = r.coordinator.openEditor(fromLocal = false, item = r.remote.entry("nginx.conf"), readOnly = false)
        advanceUntilIdle()

        assertNotNull(editor)
        assertEquals("server {}\n", (editor.state as FileEditState.Ready).text)
    }

    @Test
    fun `openEditor rebuilds the path from the pane directory instead of the listing path`() = runTest {
        // A hostile server can put any path in a listing entry; the editor must read (and later
        // write) the file under the directory actually being browsed.
        val remote = remoteFake().apply {
            seedContent("$RHOME/nginx.conf", "real\n")
            seedDir("/etc")
            seedContent("/etc/shadow", "secret\n")
        }
        val r = rig(remote = remote)
        r.remote.refresh(); advanceUntilIdle()
        val forged = r.remote.entry("nginx.conf").copy(path = "/etc/shadow")
        remote.contentCalls.clear()

        val editor = r.coordinator.openEditor(fromLocal = false, item = forged, readOnly = false)
        advanceUntilIdle()

        assertEquals("real\n", (editor!!.state as FileEditState.Ready).text)
        assertTrue(remote.contentCalls.none { it.endsWith("/etc/shadow") }, "must not touch the forged path")

        editor.edit("changed\n")
        editor.save()
        advanceUntilIdle()

        assertEquals("changed\n", remote.contentOf("$RHOME/nginx.conf"))
        assertEquals("secret\n", remote.contentOf("/etc/shadow"))
    }

    @Test
    fun `openEditor refuses a listing entry whose name is not a plain path component`() = runTest {
        val r = rig()
        val hostile = r.remote.entry("r.txt").copy(name = "../../etc/shadow")

        assertNull(r.coordinator.openEditor(fromLocal = false, item = hostile, readOnly = false))
    }

    @Test
    fun `awaitEditorWrites suspends until the editor's save has finished`() = runTest {
        // Session teardown closes the SFTP channel; an editor save runs on the session scope and
        // outlives the editor UI, so the close must wait — an interrupted write truncates the file.
        val remote = remoteFake().apply { seedContent("$RHOME/nginx.conf", "before\n") }
        val r = rig(remote = remote)
        r.remote.refresh(); advanceUntilIdle()
        val editor = r.coordinator.openEditor(fromLocal = false, item = r.remote.entry("nginx.conf"), readOnly = false)!!
        advanceUntilIdle()
        editor.edit("after\n")
        remote.writeGate = CompletableDeferred()

        editor.save()
        advanceUntilIdle()

        var released = false
        val waiter = scope().launch { r.coordinator.awaitEditorWrites(); released = true }
        advanceUntilIdle()
        assertFalse(released, "teardown must not proceed while a save is in flight")

        remote.writeGate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(released)
        assertEquals("after\n", remote.contentOf("$RHOME/nginx.conf"))
        waiter.cancel()
    }

    // Transfer queue: what the bottom strip lists. One entry per operation, kept after it ends so
    // the result of a finished transfer is still on screen.

    @Test
    fun `a finished upload stays in the queue as a completed entry`() = runTest {
        val r = rig()
        r.local.toggle(r.local.entry("a.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val entry = r.coordinator.queue.single()
        assertEquals(TransferDirection.Upload, entry.direction)
        assertEquals("a.txt", entry.name)
        assertEquals(TransferStatus.Done, entry.status)
        assertEquals(TransferState.Idle, r.coordinator.transfer)
    }

    @Test
    fun `a running transfer is the queue's active entry, with its byte counts`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.local.toggle(r.local.entry("b.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle() // blocks on the gate at the first file

        val entry = r.coordinator.queue.single()
        assertEquals(TransferStatus.Active, entry.status)
        assertEquals(1, entry.fileIndex)
        assertEquals(2, entry.fileCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TransferStatus.Done, r.coordinator.queue.single().status)
    }

    @Test
    fun `a failed transfer names its reason in the queue`() = runTest {
        val remote = remoteFake().apply { uploadError = "disk full" }
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val status = assertIs<TransferStatus.Failed>(r.coordinator.queue.single().status)
        assertEquals(FileTransferFailure.Transfer, status.failure)
    }

    @Test
    fun `the queue counts the whole operation's bytes and the time it took`() = runTest {
        // Speed is read off these two numbers, so they are the coordinator's job: the clock is
        // injected, the total is the sum over the operation's files, not the last one's. The gate
        // holds the transfer mid-flight so the clock can move while it runs.
        var clock = 1_000L
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote, now = { clock })
        r.local.toggle(r.local.entry("a.txt")) // 10 bytes
        r.local.toggle(r.local.entry("b.txt")) // 20 bytes

        r.coordinator.uploadSelection()
        advanceUntilIdle() // waiting on the gate, inside the first file
        clock = 3_000L
        gate.complete(Unit)
        advanceUntilIdle()

        val entry = r.coordinator.queue.single()
        assertEquals(30, entry.bytesDone)
        assertEquals(2_000, entry.elapsedMillis)
    }

    @Test
    fun `the queue keeps only the last few finished entries, dropping the oldest`() = runTest {
        val r = rig()
        val seen = mutableListOf<Long>()
        repeat(MAX_COMPLETED_TRANSFERS + 2) { index ->
            r.local.toggle(r.local.entry("a.txt"))
            r.coordinator.uploadSelection()
            advanceUntilIdle()
            // Each round overwrites the same remote name — confirm the conflict so it runs.
            r.coordinator.overwrite?.let { r.coordinator.resolveOverwrite(true) }
            advanceUntilIdle()
            assertTrue(r.coordinator.queue.size <= MAX_COMPLETED_TRANSFERS, "round $index")
            seen += r.coordinator.queue.last().id
        }
        // What survives is the tail of the history, in order — eviction takes the oldest, never
        // the entry the user just watched finish.
        assertEquals(seen.takeLast(MAX_COMPLETED_TRANSFERS), r.coordinator.queue.map { it.id })
    }

    @Test
    fun `a successful transfer clears the error left by the one before it`() = runTest {
        // The single-line view (mobile card) reads `transfer`. A failure followed by a success must
        // read as "nothing wrong": showing the old error after a working retry sends the user
        // retrying something that already went through.
        val remote = remoteFake().apply { uploadError = "disk full" }
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()
        assertIs<TransferState.Failed>(r.coordinator.transfer)

        remote.uploadError = null
        r.local.toggle(r.local.entry("b.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertEquals(2, r.coordinator.queue.size, "the failed entry stays in the queue's history")
    }

    @Test
    fun `the single-line state and the queue describe the same running transfer`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.local.toggle(r.local.entry("b.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val entry = r.coordinator.queue.single { it.status == TransferStatus.Active }
        val active = assertIs<TransferState.Active>(r.coordinator.transfer)
        assertEquals(entry.name, active.name)
        assertEquals(entry.direction, active.direction)
        assertEquals(entry.fileIndex, active.fileIndex)
        assertEquals(entry.fileCount, active.fileCount)
        assertEquals(entry.transferred, active.transferred)
        assertEquals(entry.total, active.total)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a move that transferred but could not delete the source ends as a failure`() = runTest {
        // The block closes its own entry (failOperation) and then returns normally, so the
        // operation-wide "done" that follows must not flip it back to success.
        val local = localFake().apply { removeError = "permission denied" }
        val r = rig(local = local)
        r.local.toggle(r.local.entry("a.txt"))

        r.coordinator.moveSelection(fromLocal = true)
        advanceUntilIdle()

        val status = assertIs<TransferStatus.Failed>(r.coordinator.queue.single().status)
        assertEquals(FileTransferFailure.DeleteSource, status.failure)
    }

    @Test
    fun `a cancelled transfer does not leave its entry running forever`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        remote.transferGate = CompletableDeferred()
        val scope = scope()
        val localBrowser = SftpFileBrowser(localFake(), "This Mac")
        val remoteBrowser = SftpFileBrowser(remote, "prod-web-01")
        val localCtl = FilePaneController(localBrowser, scope)
        val remoteCtl = FilePaneController(remoteBrowser, scope)
        localCtl.start(); remoteCtl.start(); advanceUntilIdle()
        val coordinator = TransferCoordinator(remote, localCtl, localBrowser, remoteCtl, remoteBrowser, scope)
        localCtl.toggle((localCtl.state as FilePaneState.Loaded).entries.first { it.name == "a.txt" })

        coordinator.uploadSelection()
        advanceUntilIdle() // waiting on the gate
        assertEquals(TransferStatus.Active, coordinator.queue.single().status)

        scope.cancel() // the session goes away mid-transfer
        advanceUntilIdle()

        assertIs<TransferStatus.Failed>(coordinator.queue.single().status)
    }

    @Test
    fun `a second transfer requested while one runs does not open a second entry`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        remote.transferGate = CompletableDeferred()
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        r.local.toggle(r.local.entry("b.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        assertEquals(1, r.coordinator.queue.size)
        remote.transferGate!!.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a download counts its bytes on the queue entry too`() = runTest {
        val remote = remoteFake()
        val r = rig(remote = remote)
        r.remote.toggle(r.remote.entry("r.txt")) // 30 bytes

        r.coordinator.downloadSelection()
        advanceUntilIdle()

        val entry = r.coordinator.queue.single()
        assertEquals(TransferDirection.Download, entry.direction)
        assertEquals(30, entry.bytesDone)
    }

    @Test
    fun `a picked upload and a download to a target each get their own queue entry`() = runTest {
        val r = rig()
        val source = object : UploadSource {
            override val name = "picked.txt"
            override val stagingPath = "/tmp/picked.txt"
            override suspend fun cleanup() = Unit
        }

        r.coordinator.uploadSource(source)
        advanceUntilIdle()

        val uploaded = r.coordinator.queue.single()
        assertEquals("picked.txt", uploaded.name)
        assertEquals(TransferDirection.Upload, uploaded.direction)
        assertEquals(TransferStatus.Done, uploaded.status)

        val target = object : DownloadTarget {
            override val displayName = "r.txt"
            override val stagingPath = "/tmp/r.txt"
            override suspend fun finalize() = Unit
            override suspend fun discard() = Unit
        }
        r.coordinator.downloadToTarget(r.remote.entry("r.txt"), target)
        advanceUntilIdle()

        val downloaded = r.coordinator.queue.last()
        assertEquals("r.txt", downloaded.name)
        assertEquals(TransferDirection.Download, downloaded.direction)
        assertEquals(TransferStatus.Done, downloaded.status)
        assertEquals(2, r.coordinator.queue.size)
    }

    @Test
    fun `dismissing the finished entries clears them all at once`() = runTest {
        val r = rig()
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()
        r.local.toggle(r.local.entry("b.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()
        assertEquals(2, r.coordinator.queue.size)

        r.coordinator.dismissCompleted()

        assertTrue(r.coordinator.queue.isEmpty())
    }

    @Test
    fun `dismissing the finished entries leaves a running one alone`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val r = rig(remote = remote)
        // One transfer all the way through, then a second held open: the bulk dismiss has to tell
        // them apart, not simply clear or spare the whole list.
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()
        remote.transferGate = CompletableDeferred()
        r.local.toggle(r.local.entry("b.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()
        assertEquals(2, r.coordinator.queue.size)

        r.coordinator.dismissCompleted()

        val left = r.coordinator.queue.single()
        assertEquals(TransferStatus.Active, left.status)
        assertEquals("b.txt", left.name)
        remote.transferGate!!.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a picked upload that fails still lands on the queue`() = runTest {
        // The fallback upload path (native picker, nothing selected in the local pane) had only
        // success coverage: its failure has to reach the queue like any other.
        val remote = remoteFake().apply { uploadError = "disk full" }
        val r = rig(remote = remote)
        val source = object : UploadSource {
            override val name = "picked.txt"
            override val stagingPath = "/tmp/picked.txt"
            var cleaned = false
            override suspend fun cleanup() { cleaned = true }
        }

        r.coordinator.uploadSource(source)
        advanceUntilIdle()

        val status = assertIs<TransferStatus.Failed>(r.coordinator.queue.single().status)
        assertEquals(FileTransferFailure.Transfer, status.failure)
        // The staged copy the picker made is the coordinator's to delete on the way out — a failed
        // upload leaves it behind otherwise, and nothing else ever comes back for it.
        assertTrue(source.cleaned)
    }

    @Test
    fun `dismissing a finished entry drops it from the queue`() = runTest {
        val r = rig()
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        r.coordinator.dismissTransfer(r.coordinator.queue.single().id)

        assertTrue(r.coordinator.queue.isEmpty())
    }

    @Test
    fun `dismissing does not touch a transfer still running`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        remote.transferGate = CompletableDeferred()
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        r.coordinator.dismissTransfer(r.coordinator.queue.single().id)

        assertEquals(1, r.coordinator.queue.size)
        remote.transferGate!!.complete(Unit)
        advanceUntilIdle()
    }
}
