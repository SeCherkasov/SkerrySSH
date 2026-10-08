package app.skerry.ui.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalEmulator
import app.skerry.shared.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.TerminalColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.yield

/** Hidden tabs keep a bounded render tail, while input modes and parsing retain frame cadence. */
internal const val BACKGROUND_PUBLISH_INTERVAL_MS = 250L

/**
 * The emulator owner loop: applies commands strictly in order and publishes snapshots at a
 * bounded rate. The first command after a quiet period publishes immediately; while commands keep
 * arriving, the mid-window wait absorbs the stream and a publish happens once per
 * the current publication interval (normally [PUBLISH_MIN_INTERVAL_MS]) — on the window edge if the stream pauses inside it (trailing
 * publish), so the last batch of a burst is never left undrawn. select (not
 * withTimeoutOrNull+receive) because select's clauses are atomic: a command cannot be lost to a
 * timeout racing an in-flight receive.
 *
 * Its own class rather than methods on [TerminalScreenState]: the loop owns pacing only — the
 * state object stays the sole owner of what applying and publishing mean.
 */
internal class EmulatorOwnerLoop(
    private val commands: Channel<TerminalCommand>,
    private val schedule: EmulatorPublicationSchedule,
    private val apply: suspend (TerminalCommand) -> Unit,
    private val publish: (force: Boolean) -> Unit,
    /** How long the application still holds the screen mid-frame (mode 2026); 0 when it does not. */
    private val heldFor: () -> Long,
) {
    private var lastPublishAt = Long.MIN_VALUE / 2
    private var dirty = false

    suspend fun run() {
        var pending: ChannelResult<TerminalCommand>? = null
        try {
            while (true) {
                val received = pending ?: receiveNext()
                val cmd = received.getOrNull() ?: break
                pending = step(cmd)
            }
        } finally {
            // Every exit — channel close, parser fault, cancellation — lands the tail applied
            // since the last publish: the coalescing window must never widen how much parsed
            // output a fault can erase from the screen.
            if (dirty || schedule.pendingPublishDelay() != null) flushTailBestEffort()
        }
    }

    private suspend fun receiveNext(): ChannelResult<TerminalCommand> {
        while (true) {
            val delay = schedule.pendingPublishDelay() ?: return commands.receiveCatching()
            val next = awaitCommand(delay)
            if (next != null) return next
            publishNow(schedule.nowMillis())
        }
    }

    /**
     * One paced iteration: apply [cmd], drain up to a window's worth of queued work, then either
     * publish (window elapsed) or wait for the window edge. Returns a command received while
     * waiting — the caller's next iteration consumes it — or null when this step published.
     */
    private suspend fun step(cmd: TerminalCommand): ChannelResult<TerminalCommand>? {
        val entered = schedule.nowMillis()
        apply(cmd)
        // Before the drain: a fault inside a drained command must still flush what this step
        // already applied - the finally's guarantee covers the whole batch, not just its tail.
        dirty = true
        val windowSpentParsing = drainWithinWindow(entered)
        val held = heldFor()
        if (held > 0) {
            if (windowSpentParsing) yield()
            // The frame closes with a later command; if none comes, the hold expiring draws it.
            val next = awaitCommand(held)
            if (next == null) publishNow(schedule.nowMillis())
            return next
        }
        val now = schedule.nowMillis()
        val interval = schedule.publishIntervalMillis()
        if (schedule.refreshRequested() || now - lastPublishAt >= interval) {
            publishNow(now)
            // Mid-flood fairness: this coroutine shares the Default pool with the writer and
            // other sessions; give them a slot before taking the next windowful.
            if (windowSpentParsing) yield()
            return null
        }
        val next = awaitCommand(interval - (now - lastPublishAt))
        if (next == null) publishNow(schedule.nowMillis())
        return next
    }

    /**
     * Applies queued commands until the queue empties or one window of parse time has passed
     * [since] the step began; true when the budget was spent parsing. Budgeted from step entry,
     * not from the last publish: after a quiet period the window is long expired, and a stale
     * budget would return "spent" before draining anything — splitting an already-queued batch
     * across two frames and mislabeling every interactive echo as a flood. A host that keeps the
     * queue non-empty (dense flood at parse rate) still cannot postpone publishes or monopolize
     * the dispatcher: at most one window of parse work per publish.
     */
    private suspend fun drainWithinWindow(since: Long): Boolean {
        while (true) {
            if (schedule.nowMillis() - since >= schedule.publishIntervalMillis()) return true
            val next = commands.tryReceive().getOrNull() ?: return false
            apply(next)
        }
    }

    /**
     * Waits for the next command or [timeoutMs], whichever comes first; null means the timeout —
     * the window edge, or the end of a synchronized-output hold. Runs once per drained batch during
     * a sub-window burst — its per-call allocation is bounded by burst cadence, not by the window.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitCommand(timeoutMs: Long): ChannelResult<TerminalCommand>? =
        select {
            commands.onReceiveCatching { it }
            onTimeout(timeoutMs) { null }
        }

    private fun publishNow(at: Long) {
        publish(false)
        lastPublishAt = at
        dirty = false
    }

    /**
     * Tail flush for [run]'s finally. The publish callback ([TerminalScreenState.publishSnapshot])
     * does not suspend, so no CancellationException can originate here; catching the rest keeps a
     * flush-only failure from replacing an in-flight fault, while the trace keeps it visible on
     * the clean-close path where this throw would otherwise be the only signal. No logging
     * framework exists in this codebase — stderr is the convention (see the owner-level fault
     * handler in [TerminalScreenState]).
     */
    @Suppress("TooGenericExceptionCaught", "PrintStackTrace")
    private fun flushTailBestEffort() {
        try {
            publish(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/** Publication deadlines belong to the state; the loop consumes them without reading the parser. */
internal class EmulatorPublicationSchedule(
    val nowMillis: () -> Long,
    val refreshRequested: () -> Boolean,
    val pendingPublishDelay: () -> Long?,
    val publishIntervalMillis: () -> Long = { PUBLISH_MIN_INTERVAL_MS },
)

internal suspend fun applyTerminalResize(
    session: TerminalSession,
    emulator: TerminalEmulator,
    size: PtySize,
    onFailure: () -> Unit,
    beforeEmulatorResize: () -> Unit,
) {
    // PTY is resized first, the emulator only on success: otherwise the grid would be
    // wider than the application knows and the tail of rows would stay undrawn. A PTY
    // resize failure must not kill this coroutine, or feed stops being processed and
    // the terminal freezes.
    val ptyResized = try {
        session.resize(size)
        true
    } catch (e: CancellationException) {
        throw e // do not swallow scope cancellation
    } catch (_: Exception) {
        // Only recoverable failures (e.g. PTY dropped); Error propagates. The dedup
        // memo is cleared so the next request at the same size is re-attempted rather
        // than silently dropped — auto-fit's settled-snapshot gate waits on exactly
        // that retry. Written off the UI thread; the worst a race costs is one
        // redundant resize command, and the dedup is best-effort anyway.
        onFailure()
        false
    }
    // Outside the catch: an emulator fault is a parser-class bug, not a PTY hiccup —
    // it propagates to the owner-level handler (trace + close) like a feed fault,
    // instead of leaving a silently stale grid.
    if (ptyResized) {
        beforeEmulatorResize()
        emulator.resize(size.cols, size.rows)
    }
}


/** Serial control queue with backpressure restricted to unparsed PTY chunks. */
internal class TerminalCommandQueue {
    private val feedPermits = Semaphore(FEED_BACKLOG_CHUNKS)
    // Controls use trySend from UI callbacks and must never be dropped on a full buffer.
    val channel = Channel<TerminalCommand>(Channel.UNLIMITED, onUndeliveredElement = { command ->
        // Prompt cancellation can receive a StopRecording without applying it or leaving it in
        // the queue drained below. A cancelled owner must release that caller as well.
        if (command is TerminalCommand.StopRecording) command.cast.complete(null)
    })

    suspend fun feed(chunk: ByteArray) {
        // A send failing after acquire loses its permit only on a terminal already torn down;
        // reconnect creates a new queue. The owner returns permits after parsing or discarding.
        feedPermits.acquire()
        channel.send(TerminalCommand.Feed(chunk))
    }

    fun feedApplied() { feedPermits.release() }

    fun closeAndDrain(finishRecording: () -> String?) {
        // The scope can be a SupervisorJob: an owner fault must unblock its still-live collector.
        channel.close()
        while (true) {
            val left = channel.tryReceive().getOrNull() ?: break
            // Exhaustive: a future completion/resource-carrying command needs a teardown decision.
            when (left) {
                is TerminalCommand.StopRecording -> left.cast.complete(finishRecording())
                is TerminalCommand.Feed -> feedApplied()
                is TerminalCommand.StartRecording,
                is TerminalCommand.Renderers,
                is TerminalCommand.SetCursorDefault,
                is TerminalCommand.SetMaxScrollback,
                is TerminalCommand.SetClipboardWriteEnabled,
                is TerminalCommand.SetColors,
                is TerminalCommand.ExpectStep,
                is TerminalCommand.Resize -> Unit
            }
        }
    }
}

/** Command to the sole emulator owner; the queue preserves feed/resize ordering. */
internal sealed interface TerminalCommand {
    /** Composed surfaces, rather than keyboard focus: all visible split panes count. */
    class Renderers(val delta: Int) : TerminalCommand
    /** Raw PTY output chunk to feed to the parser. */
    class Feed(val chunk: ByteArray) : TerminalCommand

    /** Begin recording. Carries the grid size and epoch stamp for the asciicast header. */
    class StartRecording(
        val title: String?,
        val startedAtMillis: Long,
        val columns: Int,
        val rows: Int,
    ) : TerminalCommand

    /** End recording; [cast] receives the asciicast (or `null` if nothing was being recorded). */
    class StopRecording(val cast: CompletableDeferred<String?>) : TerminalCommand

    /** New grid size: applied to the emulator and forwarded to the PTY. */
    class Resize(val size: PtySize) : TerminalCommand

    /** New user default cursor (setting changed while the session is open). */
    class SetCursorDefault(val shape: CursorShape, val blink: Boolean) : TerminalCommand

    /** New scrollback depth (setting changed while the session is open). */
    class SetMaxScrollback(val lines: Int) : TerminalCommand

    /** New OSC 52 clipboard-write gate state (setting changed while the session is open). */
    class SetClipboardWriteEnabled(val enabled: Boolean) : TerminalCommand

    /** Colors to answer color queries with (the theme the session is drawn in changed). */
    class SetColors(val colors: TerminalColors) : TerminalCommand

    /** The runbook step the terminal should report, and the echo of its probes to hide. */
    class ExpectStep(val token: String?, val hiddenEcho: List<String>) : TerminalCommand
}
