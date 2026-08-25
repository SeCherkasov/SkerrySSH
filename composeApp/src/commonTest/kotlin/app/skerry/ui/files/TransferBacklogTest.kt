package app.skerry.ui.files

import app.skerry.ui.sftp.TransferDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens to an operation the channel has no room for yet (issue #317). Both mobile entry
 * points hand the coordinator a handle the user has already committed to — the SAF document
 * "Save to…" created at the chosen location, the full copy of a picked upload sitting in the app's
 * cache — so an operation dropped on the floor leaves an empty file where the user asked for their
 * data, and a copy nothing ever deletes. It waits its turn instead.
 */
class TransferBacklogTest {

    @Test
    fun `a download to a picked target requested while a transfer runs waits its turn`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle() // held inside the first transfer

        val target = FakeDownloadTarget("r.txt", "/staging/r.txt")
        r.coordinator.downloadToTarget(r.remote.entry("r.txt"), target)
        advanceUntilIdle()

        assertFalse(target.finalized, "the channel is busy — nothing should have been written yet")
        assertFalse(target.discarded, "a waiting target is not a failed one")

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(target.finalized, "the picked target must be written once the channel frees up")
        assertFalse(target.discarded, "a document that was written is not one to remove")
        assertEquals("$RHOME/r.txt" to "/staging/r.txt", r.remoteFake.lastDownload)
    }

    @Test
    fun `a picked upload requested while a transfer runs waits its turn`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle() // held inside the first transfer

        val source = FakeUploadSource("picked.txt", "/tmp/picked.txt")
        r.coordinator.uploadSource(source)
        advanceUntilIdle()

        assertEquals(0, source.cleanups, "the staged copy is still the transfer's to read")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("/tmp/picked.txt" to "$RHOME/picked.txt", r.remoteFake.lastUpload)
        assertEquals(1, source.cleanups)
    }

    @Test
    fun `refusing the overwrite of a picked upload releases its staged copy`() = runTest {
        val remote = remoteFake().apply { seedFile("$RHOME/picked.txt", size = 4) }
        val r = rig(remote = remote)
        val source = FakeUploadSource("picked.txt", "/tmp/picked.txt")

        r.coordinator.uploadSource(source)
        assertNotNull(r.coordinator.overwrite, "expected an Overwrite dialog")

        r.coordinator.resolveOverwrite(false)
        advanceUntilIdle()

        assertNull(r.remoteFake.lastUpload, "the user said no")
        assertEquals(1, source.cleanups, "a refused upload still has a staged copy to release")
    }

    @Test
    fun `a waiting operation is a row of its own, behind the running one`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        r.coordinator.uploadSource(FakeUploadSource("picked.txt", "/tmp/picked.txt"))
        advanceUntilIdle()

        assertEquals(TransferStatus.Active, r.coordinator.queue.first().status)
        val waiting = r.coordinator.queue.last()
        assertEquals(TransferStatus.Waiting, waiting.status)
        assertEquals("picked.txt", waiting.name)
        val active = assertIs<TransferState.Active>(r.coordinator.transfer)
        assertEquals("a.txt", active.name, "the single-line view follows what is actually moving")
        assertTrue(r.coordinator.writeInFlight, "work the session still owes is work in flight")

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `dismissing a waiting operation releases the handle it was holding`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val target = FakeDownloadTarget("r.txt", "/staging/r.txt")
        r.coordinator.downloadToTarget(r.remote.entry("r.txt"), target)
        advanceUntilIdle()

        r.coordinator.dismissTransfer(r.coordinator.queue.last().id)
        advanceUntilIdle()

        assertTrue(target.discarded, "the document the picker created is the coordinator's to remove")
        assertEquals(1, r.coordinator.queue.size, "the cancelled row goes with it")

        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(r.remoteFake.lastDownload, "a cancelled download must not run later")
    }

    @Test
    fun `queued operations are released when the session closes the channel`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val source = FakeUploadSource("picked.txt", "/tmp/picked.txt")
        r.coordinator.uploadSource(source)
        advanceUntilIdle()

        r.coordinator.releaseQueued()
        advanceUntilIdle()

        assertEquals(1, source.cleanups, "the staged copy outlives nothing")
        val abandoned = r.coordinator.queue.last()
        val status = assertIs<TransferStatus.Failed>(abandoned.status, "the row says the upload never happened")
        assertEquals(FileTransferFailure.SessionClosed, status.failure)
        assertEquals("picked.txt", abandoned.name)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a second overwrite question waits for the first to be answered`() = runTest {
        // The native picker draws over the dialog, so a second picked upload can arrive while the
        // first is still being asked about. Replacing the pending conflict would strand its copy.
        val remote = remoteFake().apply {
            seedFile("$RHOME/one.txt", size = 4)
            seedFile("$RHOME/two.txt", size = 4)
        }
        val r = rig(remote = remote)
        val first = FakeUploadSource("one.txt", "/tmp/one.txt")
        val second = FakeUploadSource("two.txt", "/tmp/two.txt")

        r.coordinator.uploadSource(first)
        r.coordinator.uploadSource(second)

        assertEquals(listOf("one.txt"), r.coordinator.overwrite?.names)

        r.coordinator.resolveOverwrite(false)
        assertEquals(listOf("two.txt"), r.coordinator.overwrite?.names, "the second question takes its place")

        r.coordinator.resolveOverwrite(true)
        advanceUntilIdle()

        assertEquals(1, first.cleanups)
        assertEquals(1, second.cleanups)
        assertEquals("/tmp/two.txt" to "$RHOME/two.txt", r.remoteFake.lastUpload)
    }

    @Test
    fun `operations run in the order they were asked for`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val second = FakeUploadSource("second.txt", "/tmp/second.txt")
        val third = FakeUploadSource("third.txt", "/tmp/third.txt")
        r.coordinator.uploadSource(second)
        r.coordinator.uploadSource(third)
        advanceUntilIdle()

        assertEquals(listOf("a.txt", "second.txt", "third.txt"), r.coordinator.queue.map { it.name })

        // The user takes the middle one back; the one behind it keeps its place.
        r.coordinator.dismissTransfer(r.coordinator.queue[1].id)
        advanceUntilIdle()
        assertEquals(1, second.cleanups)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("/tmp/third.txt" to "$RHOME/third.txt", r.remoteFake.lastUpload)
        assertEquals(1, third.cleanups)
    }

    @Test
    fun `a waiting operation is released when the scope that would have run it dies`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val source = FakeUploadSource("picked.txt", "/tmp/picked.txt")
        r.coordinator.uploadSource(source)
        advanceUntilIdle()

        // Not releaseQueued(): the scope simply dies, which is what a cancelled session does to the
        // transfer that was running. Whatever was behind it must still be handed back.
        r.scope.cancel()
        advanceUntilIdle()

        assertEquals(1, source.cleanups, "the staged copy is the app's to release, cancelled or not")
        // Handing the copy back is not enough on its own: a dead scope still dispatches the next
        // operation, and the fake records the upload before it ever suspends. So the queued work
        // must not have been started at all.
        assertEquals("$LHOME/a.txt" to "$RHOME/a.txt", r.remoteFake.lastUpload, "nothing may go out on a dead channel")
        val abandoned = r.coordinator.queue.last()
        val status = assertIs<TransferStatus.Failed>(abandoned.status)
        assertEquals(FileTransferFailure.SessionClosed, status.failure)
        assertEquals("picked.txt", abandoned.name)
    }

    @Test
    fun `an unanswered overwrite question releases its source when the session closes`() = runTest {
        val remote = remoteFake().apply { seedFile("$RHOME/picked.txt", size = 4) }
        val r = rig(remote = remote)
        val source = FakeUploadSource("picked.txt", "/tmp/picked.txt")

        r.coordinator.uploadSource(source)
        assertNotNull(r.coordinator.overwrite)

        r.coordinator.releaseQueued()
        advanceUntilIdle()

        assertNull(r.coordinator.overwrite, "the question goes with the channel it was about")
        assertEquals(1, source.cleanups, "an upload nobody will ever answer for still holds a copy")
    }

    @Test
    fun `what a queued operation is going to write counts as already there`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.coordinator.uploadSelection()
        advanceUntilIdle()

        // Queued, not yet written: the remote listing knows nothing about it.
        r.coordinator.uploadSource(FakeUploadSource("picked.txt", "/tmp/picked.txt"))
        advanceUntilIdle()
        assertNull(r.coordinator.overwrite)

        r.coordinator.uploadSource(FakeUploadSource("picked.txt", "/tmp/other.txt"))

        assertEquals(
            listOf("picked.txt"),
            r.coordinator.overwrite?.names,
            "two queued uploads of the same name would overwrite each other with nothing asked",
        )

        r.coordinator.resolveOverwrite(false)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a picked upload the provider named unsafely is refused out loud`() = runTest {
        val r = rig()
        // Android hands over an OpenableColumns.DISPLAY_NAME from an app we do not control, and it
        // becomes a path component on the host.
        val source = FakeUploadSource("../escaped.txt", "/tmp/escaped.txt")

        r.coordinator.uploadSource(source)
        advanceUntilIdle()

        assertNull(r.remoteFake.lastUpload, "nothing may be written outside the destination")
        val row = r.coordinator.queue.single()
        assertEquals(TransferDirection.Upload, row.direction)
        val status = assertIs<TransferStatus.Failed>(row.status, "a refusal the user cannot see is the bug")
        assertEquals(FileTransferFailure.IllegalName, status.failure)
        assertEquals(1, source.cleanups)
    }
}
