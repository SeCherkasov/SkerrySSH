package app.skerry.ui.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the vault's idle auto-lock reads off a session's files: the lock closes the channel the write
 * runs on, and both a transfer and an editor save are open-truncate-write at the far end (issue
 * #291). A question put to the user — an "Overwrite?" dialog — is not work in flight.
 */
class TransferWriteInFlightTest {

    @Test
    fun `writeInFlight is true only while bytes are moving`() = runTest {
        val remote = remoteFake().apply {
            seedFile("$RHOME/a.txt", size = 3)
            uploadSize = 10
        }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))

        r.coordinator.uploadSelection()
        assertNotNull(r.coordinator.overwrite, "expected an Overwrite dialog")
        assertFalse(r.coordinator.writeInFlight, "a question for the user is not work in flight")

        r.coordinator.resolveOverwrite(true)
        advanceUntilIdle() // blocked on the gate, inside the file

        assertTrue(r.coordinator.writeInFlight, "a running transfer must defer the lock")

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(r.coordinator.writeInFlight, "a finished transfer still deferred the lock")
    }

    /** The buffer dies with the subtree the lock drops, so a cut save loses the file both ways. */
    @Test
    fun `writeInFlight covers an editor save`() = runTest {
        val remote = remoteFake().apply { seedContent("$RHOME/nginx.conf", "before\n") }
        val r = rig(remote = remote)
        r.remote.refresh(); advanceUntilIdle()
        val editor = r.coordinator.openEditor(fromLocal = false, item = r.remote.entry("nginx.conf"), readOnly = false)!!
        advanceUntilIdle()
        editor.edit("after\n")
        remote.writeGate = CompletableDeferred()

        editor.save()
        advanceUntilIdle() // blocked inside the write

        assertTrue(r.coordinator.writeInFlight, "a save in flight must defer the lock that would cut it")

        remote.writeGate!!.complete(Unit)
        advanceUntilIdle()

        assertFalse(r.coordinator.writeInFlight, "a finished save still deferred the lock")
    }
}
