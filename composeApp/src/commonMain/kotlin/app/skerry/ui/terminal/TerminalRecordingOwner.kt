package app.skerry.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.team.RecordingMode
import app.skerry.shared.team.TeamRecordingOutbox
import app.skerry.shared.terminal.SessionRecorder
import app.skerry.shared.terminal.epochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Recording state owned by the terminal command loop, including its cancellation tail. */
internal class TerminalRecordingOwner(
    private val mode: RecordingMode,
    private val requiredCapture: TeamRecordingOutbox.Capture?,
    private val requiredFinished: () -> Unit,
) {
    var recording by mutableStateOf(mode == RecordingMode.REQUIRED)
        private set
    var truncated by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var recordingToTeam by mutableStateOf(requiredCapture != null)
        private set
    private var recorder: SessionRecorder? = null
    private var optionalCapture: TeamRecordingOutbox.Capture? = null
    private var optionalFinished: () -> Unit = {}
    private var optionalStarted: TimeMark? = null
    private val requiredStarted = TimeSource.Monotonic.markNow()
    private var startedAtMillis = 0L
    private var requiredNotified = false

    val seconds: Long
        get() = if (startedAtMillis == 0L) 0 else (epochMillis() - startedAtMillis) / 1000

    fun markStarted(atMillis: Long) {
        recording = true
        truncated = false
        startedAtMillis = atMillis
    }

    fun beginStop(): Boolean {
        if (!recording || mode == RecordingMode.REQUIRED) return false
        recording = false
        return true
    }

    fun start(command: TerminalCommand.StartRecording) {
        val started = TimeSource.Monotonic.markNow()
        recorder = SessionRecorder(command.columns, command.rows, command.startedAtMillis / 1000,
            command.title, now = { started.elapsedNow().inWholeMilliseconds })
        optionalCapture = command.teamCapture
        recordingToTeam = command.teamCapture != null
        optionalFinished = command.onTeamFinished
        optionalStarted = started
    }

    /** Persist first; a required failure closes the shell before the emulator displays the bytes. */
    suspend fun record(chunk: ByteArray, draining: Boolean = false) {
        try {
            if (!draining || !failed) requiredCapture?.record(chunk)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            failed = true
            if (!draining) throw e
        }
        optionalCapture?.let { capture ->
            try { capture.record(chunk) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                failed = true
                capture.abandon()
                optionalCapture = null
                notifyOptional()
            }
        }
        recorder?.let { it.record(chunk); if (it.truncated) truncated = true }
    }

    suspend fun stop(): String? {
        finishOptional()
        return recorder?.finish().also { recorder = null }
    }

    /** Cancellation must drain already accepted bytes before finalizing either capture. */
    suspend fun finish(commands: Channel<TerminalCommand>, releaseFeed: () -> Unit) {
        try {
            withContext(NonCancellable) {
                drain(commands, releaseFeed)
                finishRequired()
                finishOptional()
            }
        } finally { recording = false }
    }

    private suspend fun drain(commands: Channel<TerminalCommand>, releaseFeed: () -> Unit) {
        while (true) {
            when (val command = commands.tryReceive().getOrNull() ?: break) {
                is TerminalCommand.Feed -> try { record(command.chunk, draining = true) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    failed = true
                    optionalCapture?.abandon()
                    optionalCapture = null
                    notifyOptional()
                } finally { releaseFeed() }
                is TerminalCommand.StopRecording -> command.cast.complete(stop())
                is TerminalCommand.StartRecording -> command.teamCapture?.abort()
                is TerminalCommand.SetCursorDefault,
                is TerminalCommand.SetMaxScrollback,
                is TerminalCommand.SetClipboardWriteEnabled,
                is TerminalCommand.SetColors,
                is TerminalCommand.ExpectStep,
                is TerminalCommand.Resize -> Unit
            }
        }
    }

    private suspend fun finishOptional() {
        val capture = optionalCapture ?: return
        optionalCapture = null
        try {
            capture.finish(optionalStarted?.elapsedNow()?.inWholeSeconds ?: 0)
        } catch (e: CancellationException) {
            capture.abandon()
            notifyOptional()
            throw e
        } catch (_: Exception) { failed = true; capture.abandon() }
        notifyOptional()
    }

    private suspend fun finishRequired() {
        val capture = requiredCapture ?: return
        if (requiredNotified) return
        try {
            if (failed) capture.abandon()
            else capture.finish(requiredStarted.elapsedNow().inWholeSeconds)
        } catch (e: CancellationException) {
            capture.abandon()
            notifyRequired()
            throw e
        } catch (_: Exception) { failed = true; capture.abandon() }
        notifyRequired()
    }

    private fun notifyRequired() {
        if (requiredNotified) return
        requiredNotified = true
        notifyFinished(requiredFinished)
    }

    private fun notifyOptional() {
        val notify = optionalFinished
        optionalFinished = {}
        optionalStarted = null
        notifyFinished(notify)
    }

    private fun notifyFinished(callback: () -> Unit) {
        try { callback() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
}
