package app.skerry.ui.terminal

import app.skerry.shared.team.RecordingMode
import app.skerry.ui.vault.ExportOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.currentCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecordingControllerTest {
    private class Port(override val mode: RecordingMode) : SessionRecordingPort {
        override var recording = false
        var localStarts = 0
        var teamStarts = 0
        var cancellations = 0
        var beforeArmReturns: suspend () -> Unit = {}
        var take = RecordedTake("{\"version\":2}\n[0,\"o\",\"output\"]", 3, false)
        override fun startLocal() { localStarts++; recording = true }
        override suspend fun armTeam(): RecordingStart {
            beforeArmReturns()
            return RecordingStart(commit = { teamStarts++; recording = true }, cancel = { cancellations++ })
        }
        override suspend fun stop(): RecordedTake { recording = false; return take }
    }

    @Test
    fun `optional destination must be chosen before any recorder is armed`() = runTest {
        val port = Port(RecordingMode.OPTIONAL)
        val controller = SessionRecordingController(port, "host", "host-1", { _, _ -> ExportOutcome.Saved })
        assertEquals(RecordingAction.ChooseDestination, controller.toggle())
        assertFalse(port.recording)
        assertEquals(0, port.teamStarts)
        assertEquals(RecordingAction.Started, controller.toggle(RecordingDestination.LOCAL))
        assertEquals(1, port.localStarts)
        assertEquals(0, port.teamStarts)
    }

    @Test
    fun `team selection arms capture while local export reports only personal destination`() = runTest {
        val port = Port(RecordingMode.OPTIONAL)
        var reports = 0
        var exports = 0
        val controller = SessionRecordingController(port, "host", "host-1",
            export = { _, _ -> exports++; ExportOutcome.Saved }, reportSaved = { _, _ -> reports++ })
        controller.toggle(RecordingDestination.TEAM_AND_LOCAL)
        assertEquals(1, port.teamStarts)
        assertEquals(RecordingAction.Finished(RecordingOutcome.Saved), controller.toggle())
        assertEquals(1, exports)
        assertEquals(0, reports)
        controller.toggle(RecordingDestination.LOCAL)
        controller.toggle()
        assertEquals(1, reports)
    }

    @Test
    fun `remounted controller exports existing team take without reporting personal activity`() = runTest {
        val port = Port(RecordingMode.OPTIONAL)
        port.recording = true
        port.take = port.take.copy(teamDestination = true)
        var exports = 0
        var reports = 0
        val remounted = SessionRecordingController(port, "host", "host-1",
            export = { _, _ -> exports++; ExportOutcome.Saved },
            reportSaved = { _, _ -> reports++ })

        assertEquals(RecordingAction.Finished(RecordingOutcome.Saved), remounted.toggle())
        assertEquals(1, exports)
        assertEquals(0, reports)
        assertFalse(port.recording)
    }

    @Test
    fun `required recording cannot be toggled and off starts local without a chooser`() = runTest {
        val required = Port(RecordingMode.REQUIRED)
        required.recording = true
        val requiredController = SessionRecordingController(required, "host", null, { _, _ -> error("must not export") })
        assertEquals(RecordingAction.Ignored, requiredController.toggle(RecordingDestination.LOCAL))
        assertTrue(required.recording)
        val off = Port(RecordingMode.OFF)
        assertEquals(RecordingAction.Started,
            SessionRecordingController(off, "host", null, { _, _ -> ExportOutcome.Saved }).toggle())
        assertEquals(1, off.localStarts)
    }

    @Test
    fun `cancelled arm releases its uncommitted capture and permits another toggle`() = runTest {
        val port = Port(RecordingMode.OPTIONAL)
        port.beforeArmReturns = { currentCoroutineContext()[Job]!!.cancel() }
        val controller = SessionRecordingController(port, "host", null, { _, _ -> ExportOutcome.Saved })
        val job = launch { controller.toggle(RecordingDestination.TEAM_AND_LOCAL) }
        job.join()
        assertEquals(1, port.cancellations)
        assertEquals(0, port.teamStarts)
        port.beforeArmReturns = {}
        assertEquals(RecordingAction.Started, controller.toggle(RecordingDestination.LOCAL))
    }

    @Test
    fun `duplicate toggle during export is ignored and cancellation releases guard`() = runTest {
        val port = Port(RecordingMode.OFF)
        port.recording = true
        val exporting = CompletableDeferred<Unit>()
        val controller = SessionRecordingController(port, "host", null,
            export = { _, _ -> exporting.complete(Unit); CompletableDeferred<ExportOutcome>().await() })
        val first = launch { controller.toggle() }
        runCurrent()
        exporting.await()
        assertEquals(RecordingAction.Ignored, controller.toggle())
        first.cancelAndJoin()
        assertEquals(RecordingAction.Started, controller.toggle())
    }

    @Test
    fun `header only cast is empty and cancelled export is never reported`() = runTest {
        val port = Port(RecordingMode.OFF)
        port.recording = true
        port.take = RecordedTake("{\"version\":2}\n", 0, false)
        var exports = 0
        var reports = 0
        val controller = SessionRecordingController(port, "host", "host-1",
            export = { _, _ -> exports++; ExportOutcome.Cancelled }, reportSaved = { _, _ -> reports++ })
        assertEquals(RecordingAction.Finished(RecordingOutcome.Empty), controller.toggle())
        assertEquals(0, exports)
        port.recording = true
        port.take = RecordedTake("{\"version\":2}\n[0,\"o\",\"output\"]", 3, false)
        assertEquals(RecordingAction.Finished(RecordingOutcome.Cancelled), controller.toggle())
        assertEquals(0, reports)
    }
}
