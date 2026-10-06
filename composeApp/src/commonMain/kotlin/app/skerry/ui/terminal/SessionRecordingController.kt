package app.skerry.ui.terminal

import app.skerry.shared.team.RecordingMode
import app.skerry.shared.terminal.castFileName
import app.skerry.shared.terminal.recordingStamp
import app.skerry.ui.vault.ExportOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.coroutineContext

enum class RecordingDestination { LOCAL, TEAM_AND_LOCAL }

data class RecordedTake(
    val cast: String,
    val seconds: Long,
    val truncated: Boolean,
    val teamDestination: Boolean = false,
)

/** An armed team capture remains the caller's responsibility until [commit] succeeds. */
class RecordingStart(val commit: () -> Unit, val cancel: () -> Unit)

interface SessionRecordingPort {
    val mode: RecordingMode
    val recording: Boolean
    fun startLocal()
    suspend fun armTeam(): RecordingStart
    suspend fun stop(): RecordedTake?
}

sealed interface RecordingAction {
    data object Ignored : RecordingAction
    data object ChooseDestination : RecordingAction
    data object Started : RecordingAction
    data class Finished(val outcome: RecordingOutcome) : RecordingAction
}

/** Shared start/stop/export flow; the terminal remains the ordered owner of PTY output. */
class SessionRecordingController(
    private val terminal: SessionRecordingPort,
    private val title: String,
    private val hostId: String?,
    private val export: suspend (String, String) -> ExportOutcome,
    private val reportSaved: (String, Long) -> Unit = { _, _ -> },
    private val stamp: () -> String = ::recordingStamp,
) {
    private val mutex = Mutex()
    private var teamDestination = false

    suspend fun toggle(destination: RecordingDestination? = null): RecordingAction {
        if (terminal.mode == RecordingMode.REQUIRED || !mutex.tryLock()) return RecordingAction.Ignored
        try {
            return if (terminal.recording) finishRecording() else startRecording(destination)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return RecordingAction.Finished(RecordingOutcome.Failed) }
        finally { mutex.unlock() }
    }

    private suspend fun startRecording(destination: RecordingDestination?): RecordingAction {
        if (terminal.mode == RecordingMode.OPTIONAL && destination == null) return RecordingAction.ChooseDestination
        val uploadToTeam = destination == RecordingDestination.TEAM_AND_LOCAL && terminal.mode == RecordingMode.OPTIONAL
        if (uploadToTeam) commitTeamRecording() else terminal.startLocal()
        teamDestination = uploadToTeam
        return RecordingAction.Started
    }

    private suspend fun commitTeamRecording() {
        val plan = terminal.armTeam()
        var committed = false
        try {
            coroutineContext.ensureActive()
            plan.commit()
            committed = true
        } finally { if (!committed) plan.cancel() }
    }

    private suspend fun finishRecording(): RecordingAction {
        val take = terminal.stop()
        if (take == null || take.cast.lineSequence().drop(1).none { it.isNotBlank() }) {
            return RecordingAction.Finished(RecordingOutcome.Empty)
        }
        val result = export(castFileName(title, stamp()), take.cast)
        if (result == ExportOutcome.Saved && !teamDestination && !take.teamDestination) hostId?.let { reportSaved(it, take.seconds) }
        val outcome = when (result) {
            ExportOutcome.Cancelled -> RecordingOutcome.Cancelled
            ExportOutcome.Failed -> RecordingOutcome.Failed
            ExportOutcome.Saved -> if (take.truncated) RecordingOutcome.SavedTruncated else RecordingOutcome.Saved
        }
        return RecordingAction.Finished(outcome)
    }
}
