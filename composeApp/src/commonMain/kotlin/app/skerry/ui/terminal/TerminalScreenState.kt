package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.AutocompleteEngine
import app.skerry.shared.terminal.CommandHistory
import app.skerry.shared.terminal.highlight.CommandVocabulary
import app.skerry.shared.terminal.highlight.SessionVocabulary
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.DEFAULT_MAX_SCROLLBACK
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.SessionRecorder
import app.skerry.shared.terminal.ShellCommandMark
import app.skerry.shared.terminal.epochMillis
import app.skerry.shared.terminal.isPasswordPrompt
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TerminalColors
import app.skerry.shared.terminal.TerminalEmulator
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.terminal.TerminalStepMark
import app.skerry.shared.terminal.bracketedPasteWrap
import app.skerry.shared.terminal.encodeMouseReport
import app.skerry.shared.terminal.lineSelectionAt
import app.skerry.shared.terminal.wordSelectionAt
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import app.skerry.shared.guard.GuardedCommand
import app.skerry.shared.guard.ProductionGuard
import app.skerry.shared.guard.ProductionGuardPolicy

/**
 * Terminal screen state over [TerminalSession]. Raw PTY bytes go through [TerminalEmulator]
 * (ANSI/VT parser + screen model); the result is published as [screen] — a grid of cells with
 * color/weight — plus cursor position. Input and resize are proxied to the session.
 *
 * The emulator owns scrollback and parser state, so there is no raw byte buffer or manual UTF-8
 * decode here: each chunk is fed as-is, and the screen snapshot is written into Compose state
 * ([screen]/[cursorRow]/[cursorCol]) for redraw.
 */
@Stable
class TerminalScreenState(
    private val session: TerminalSession,
    private val scope: CoroutineScope,
    // Autocomplete command history preloaded for this host (newest to oldest), plus a persist
    // callback invoked on each committed command. Persisted only with echo (passwords filtered above).
    initialHistory: List<String> = emptyList(),
    private val onHistoryChanged: ((List<String>) -> Unit)? = null,
    // Terminal settings (Settings -> Terminal) applied to a new session: scrollback depth and
    // default cursor shape/blink.
    scrollback: Int = DEFAULT_MAX_SCROLLBACK,
    cursorShape: CursorShape = CursorShape.Block,
    cursorBlink: Boolean = true,
    // Whether OSC 52 clipboard writes from the server are honored. Default off (like xterm/kitty):
    // an untrusted host must not silently overwrite the system clipboard until the user opts in.
    // Snapshotted at connect; also pushed live into an open session via [applyClipboardWriteEnabled].
    clipboardWriteEnabled: Boolean = false,
    // The password this session authenticated with, offered back at a sudo prompt for the same
    // account (Terminal -> "Offer the saved password to sudo"). Null is the default and means the
    // feature is off for this session: nothing here can then send anything the user did not type.
    private val sudo: SudoPasswordOffer? = null,
    // Monotonic milliseconds, injectable for tests. Two readers: the search refresh throttle and
    // the publish-rate cap in the emulator owner loop. Must not step backwards (a wall clock
    // would) — a backwards step would skip search refreshes for minutes and stretch the publish
    // window past PUBLISH_MIN_INTERVAL_MS.
    private val nowMillis: () -> Long = { STARTED_AT.elapsedNow().inWholeMilliseconds },
    // Whether the emulator's replies (DA, DSR, color queries) reach the session. Off for a viewer
    // of someone else's session: the owner's terminal already answers, and a second answer would
    // arrive at the host as typed input.
    private val answersQueries: Boolean = true,
    // Live connections opt in; standalone/headless consumers retain their full snapshot cadence.
    private val backgroundWhenUnobserved: Boolean = false,
) {
    /** Register a composed terminal surface; paired with [detachRenderer] on disposal. */
    internal fun attachRenderer() { commands.trySend(TerminalCommand.Renderers(1)) }

    internal fun detachRenderer() { commands.trySend(TerminalCommand.Renderers(-1)) }

    // Owner-coroutine fields. Visibility travels in the same queue as output, never into parser
    // state from the composition thread. A second surface must keep a shared terminal visible.
    private var renderers = 0
    private var renderRefreshRequested = false
    private var lastFullPublishAt = Long.MIN_VALUE / 2
    private var unpublishedRender = false

    @Volatile
    private var inputLine: CursorLine? = null

    // OSC 52 requests to write to the system clipboard. extraBufferCapacity keeps tryEmit from the
    // owner coroutine from dropping when there's no subscriber yet; DROP_OLDEST on burst keeps the
    // latest entry (last-writer-wins), not a stale one.
    private val _clipboardCopies = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Text the application asks to place on the system clipboard (OSC 52). UI collects and writes it. */
    val clipboardCopies: SharedFlow<String> = _clipboardCopies

    // Last step mark the shell reported (OSC 8375, see TerminalStepMark) — a runbook step's exit
    // code and the output it printed. Written from the emulator's owner coroutine and read by the
    // run watcher's poll on another one, hence @Volatile rather than Compose state: the run must not
    // wait for a snapshot to be applied before it can advance. Declared above the emulator, which
    // captures it in a callback that can fire during the first feed.
    @Volatile
    private var stepMark: TerminalStepMark? = null

    // Batches of PTY output fed so far. The only thing the runbook watchdog needs from the buffer:
    // "did anything print since the last poll" — cheap, and unlike hashing the visible tail it also
    // counts output that a redraw overwrote in place.
    @Volatile
    private var feedCount: Long = 0L

    private val emulator = TerminalEmulator(
        maxScrollback = scrollback,
        initialCursorShape = cursorShape,
        initialCursorBlink = cursorBlink,
        // Terminal responses (DSR/DA) go back to the PTY, otherwise apps polling cursor/attributes
        // hang. Called synchronously from feed() (owner coroutine): must only write to the PTY
        // (send -> session.send) and never start a new feed/resize, or the emulator's single-thread
        // contract breaks.
        respond = { reply -> if (answersQueries) outbound.reply(reply.encodeToByteArray()) },
        // OSC 52 write is also called synchronously from feed(); publish to the flow, UI thread
        // writes to the system clipboard. Gated in the emulator by [clipboardWriteEnabled].
        onClipboardCopy = { text -> _clipboardCopies.tryEmit(text) },
        clipboardWriteEnabled = clipboardWriteEnabled,
        // Also called synchronously from feed(). Parked for the runbook watcher to pick up; only the
        // newest one is kept, because only the step the run is waiting on can still use it.
        onStepMark = { mark -> stepMark = mark },
    )

    /** Screen snapshot (rows top to bottom) for rendering. */
    var screen: List<List<TermCell>> by mutableStateOf(emptyList(), SCREEN_SNAPSHOT_POLICY)
        private set

    /**
     * The emulator's [TerminalEmulator.contentVersion] as of the last publish. Composition caches
     * keyed on the screen's content (search hits, highlights, links) key on this cheap Long
     * instead of the list itself — a structural list compare in a `remember` key walks the whole
     * scrollback on the Main thread when the instance changed.
     */
    var screenContentVersion: Long by mutableStateOf(0L)
        private set

    /**
     * Mobile auto-fit font scale (issue #180), advanced by [TerminalScreen] when it is composed
     * with `autoFitEnabled`. Lives here rather than in composition on purpose: it must survive
     * switching to another session's tab and back (composition state keyed on the active session
     * resets on every switch, re-converging each time) and reset with the session on reconnect.
     */
    val autoFit = TerminalAutoFitState()

    /**
     * Monotonic render snapshot counter, incremented on a full publish even if [screen] is
     * structurally unchanged. Hidden surfaces publish at a lower cadence. Auto-scroll-to-bottom
     * must key off this, not [screen]: Compose
     * compares the list structurally ([equals]), so two identical snapshots in a row would not
     * retrigger the effect.
     */
    var snapshotVersion: Int by mutableStateOf(0)
        private set

    /**
     * Monotonic counter of user-initiated input ([typeInput]/[paste]). The render layer snaps the
     * viewport back to the bottom when this changes — typing while scrolled up in history returns
     * to the live screen (xterm's scroll-on-keypress), while programmatic sends (mouse reports,
     * DSR/DA responses, focus reports) don't yank the viewport.
     */
    var inputVersion: Int by mutableStateOf(0)
        private set

    /** Grid size of the last full render snapshot. */
    var cols: Int by mutableStateOf(emulator.cols)
        private set

    var rows: Int by mutableStateOf(emulator.rows)
        private set

    var cursorRow: Int by mutableStateOf(0)
        private set

    var cursorCol: Int by mutableStateOf(0)
        private set

    /** Whether the cursor is visible (DEC ?25): TUIs hide it while redrawing. Render skips a hidden cursor. */
    var cursorVisible: Boolean by mutableStateOf(true)
        private set

    /** Cursor shape (DECSCUSR): block/underline/bar. Render picks geometry from it. Starts from settings. */
    var cursorShape: CursorShape by mutableStateOf(cursorShape)
        private set

    /** Whether the cursor should blink (DECSCUSR steady/blink). Render drives the blink timer from this. */
    var cursorBlink: Boolean by mutableStateOf(cursorBlink)
        private set

    /** Current mouse selection (or `null` if nothing is selected). Render highlights it. */
    var selection: TerminalSelection? by mutableStateOf(null)
        private set

    /**
     * Shell integration marks (OSC 133), oldest first, in [screen]'s row coordinates — one per
     * command the shell bracketed. Empty until the host's shell has the integration installed.
     */
    var commandMarks: List<ShellCommandMark> by mutableStateOf(emptyList())
        private set

    /** Working directory the shell last reported (OSC 7); `null` until it does. Feeds the SFTP panel. */
    var workingDirectory: String? by mutableStateOf<String?>(null)
        private set

    // Session recording (asciinema v2). Touched only by the command loop below, the same coroutine
    // that owns the emulator: start/stop arrive from the UI thread while PTY output is still
    // streaming in, and SessionRecorder is not thread-safe. The UI reads the two state flags instead
    // of the recorder. Held in memory until the user exports it — see [SessionRecorder] on why it is
    // bounded rather than streamed to disk.
    private var recorder: SessionRecorder? = null

    /** Whether this session is being recorded. */
    var recording: Boolean by mutableStateOf(false)
        private set

    /** Whether the running recording hit its size limit and stopped collecting. */
    var recordingTruncated: Boolean by mutableStateOf(false)
        private set

    /**
     * Start recording this session's output. [title] names the recording in the asciicast header
     * (the host label). Recording while already recording keeps the existing take.
     */
    fun startRecording(title: String?) {
        if (recording) return
        val startedAt = epochMillis()
        val queued = commands.trySend(TerminalCommand.StartRecording(title, startedAt, cols, rows))
        // The queue is closed once the session's output ends: there is nothing left to record.
        if (queued.isFailure) return
        recording = true
        recordingTruncated = false
        recordingStartedAtMillis = startedAt
    }

    private var recordingStartedAtMillis: Long = 0

    /**
     * Wall-clock length of the running (or last finished) recording in seconds; 0 when this session
     * was never recorded. Read right after [stopRecording] for the length to report to a team — the
     * clock keeps running for a recording that hit its size limit, so a truncated take reads as the
     * window it covered rather than as the bytes it kept.
     */
    val recordingSeconds: Long
        get() = if (recordingStartedAtMillis == 0L) 0 else (epochMillis() - recordingStartedAtMillis) / 1000

    /**
     * Stop recording and return the asciicast, or `null` if nothing was being recorded. The caller
     * exports it; nothing is written to disk here. Suspends until the command loop hands the
     * recording over, so every chunk queued before the stop is in the file.
     */
    suspend fun stopRecording(): String? {
        if (!recording) return null
        recording = false
        val cast = CompletableDeferred<String?>()
        // The queue is closed once the session's output ends; then no owner is left to answer, and
        // the take goes with it rather than hanging the caller.
        if (commands.trySend(TerminalCommand.StopRecording(cast)).isFailure) return null
        return cast.await()
    }

    /**
     * DECCKM (application-cursor-keys) mode from the emulator: apps like vim/less/htop enable it,
     * and arrow keys must then be sent as SS3 (`ESC O A`) instead of CSI. Read by the UI when
     * encoding arrows ([app.skerry.ui.terminal.arrowSequence]).
     */
    var applicationCursorKeys: Boolean by mutableStateOf(false)
        private set

    /**
     * Application-keypad mode (DECKPAM/DECKPNM) from the emulator: when enabled, numpad keys are
     * sent as SS3 (`ESC O p`..`ESC O y` etc.) instead of digits.
     */
    var applicationKeypad: Boolean by mutableStateOf(false)
        private set

    /**
     * Mouse reporting mode from the emulator (DEC 1000/1002/1003 + X10). When not
     * [MouseTracking.Off], the application handles the mouse itself: the UI sends it events
     * instead of local selection (unless Shift is held, which forces local selection per xterm convention).
     */
    var mouseTracking: MouseTracking by mutableStateOf(MouseTracking.Off)
        private set

    /** SGR mouse encoding (DEC 1006) — selects the report format in [reportMouse]. */
    var mouseSgr: Boolean by mutableStateOf(false)
        private set

    /** SGR-Pixels (DEC 1016): pixel coordinates instead of cells, see [reportMouse]. */
    var mousePixels: Boolean by mutableStateOf(false)
        private set

    /** Bracketed paste (DEC 2004): when enabled, [paste] wraps the pasted text in markers. */
    var bracketedPaste: Boolean by mutableStateOf(false)
        private set

    /** Focus reporting (DEC 1004): when enabled, [notifyFocus] sends ESC[I/ESC[O on focus change. */
    var focusReporting: Boolean by mutableStateOf(false)
        private set

    /** Whether the alternate screen buffer is active (fullscreen TUIs): no own scrollback, wheel != scroll. */
    var altScreen: Boolean by mutableStateOf(false)
        private set

    /** Window title from OSC 0/1/2 (empty until the application sets it). UI shows it on the tab. */
    var title: String by mutableStateOf("")
        private set

    /**
     * Palette overrides (OSC 4/104): index 0..255 -> Rgb. Empty until the application sets any.
     * Consulted by render when resolving [TermColor.Indexed] before falling back to theme defaults.
     */
    var palette: Map<Int, TermColor.Rgb> by mutableStateOf(emptyMap())
        private set

    /**
     * Flat screen text for tests and simple checks (render uses [screen]). The grid is always
     * `rows` fixed-width rows, so trailing spaces and empty lines are trimmed to read as visible content.
     */
    val output: String
        get() = screen
            .joinToString("\n") { row -> buildString { row.forEach { append(it.text) } }.trimEnd() }
            .trimEnd('\n')

    /**
     * Counts batches of output received from the host. Monotonic and meaningless in itself — what it
     * is for is telling "nothing has printed since I last looked" apart from "the screen looks the
     * same but the host is talking" ([app.skerry.ui.runbook.RunbookRunner]'s watchdog).
     */
    val outputVersion: Long get() = feedCount

    /**
     * Declares the step this terminal is running, so the emulator reports that step and hides the
     * echo of [hiddenEcho] — the probes framing it, which are protocol the user never typed.
     * `null` ends it: nothing is captured, nothing is hidden, and a parked report is dropped.
     *
     * Queued with the output it is about to meet, not applied on the spot: the emulator belongs to
     * the collector coroutine, and the echo of the step arrives strictly after this call.
     */
    fun expectStepMark(token: String?, hiddenEcho: List<String> = emptyList()) {
        commands.trySend(TerminalCommand.ExpectStep(token, hiddenEcho))
    }

    /**
     * Takes the step mark for [token], once the shell has reported that step. `null` while the step
     * is still running.
     *
     * Consuming, and a mark for another token is dropped rather than queued: it can only come from a
     * step the run has already abandoned, and its output — which may carry as much of a secret as
     * the command line did — has no reason to sit in memory afterwards.
     */
    fun takeStepMark(token: String): TerminalStepMark? {
        val mark = stepMark ?: return null
        stepMark = null
        return mark.takeIf { it.token == token }
    }

    val state: StateFlow<TerminalState> get() = session.state

    // The emulator is single-threaded: feed and resize must not be called from different coroutines.
    // The owner queue serializes output and controls, bounding only the PTY backlog.
    private val commandQueue = TerminalCommandQueue()
    private val commands = commandQueue.channel

    /**
     * Test seam: invoked before each command is applied. The emulator has no reachable throw site
     * through its public surface, so without this the owner loop's fault-recovery path (trace,
     * queue close, permit return, session close) could regress with the whole suite green.
     */
    internal var applyInterceptor: (() -> Unit)? = null

    /**
     * Second test seam, scoped to the emulator half of a Resize: the loop-level seam above fires
     * before the PTY call, so it cannot produce the ordering this one covers — PTY resize already
     * succeeded, then the deliberately unguarded [TerminalEmulator.resize] call faults.
     */
    internal var emulatorResizeInterceptor: (() -> Unit)? = null

    // Outbound byte queue to the PTY (input, mouse reports, DSR/DA responses). The single writer
    // in init serializes writes, preserving order across sends from different coroutines.
    private val outbound = TerminalOutbound()
    private val inputPublication = TerminalInputPublication(nowMillis)

    // Last size sent to the PTY: duplicates are suppressed to avoid spamming resize on relayout.
    // @Volatile because resize() can be called from different coroutines (LaunchedEffect/gestures).
    @Volatile
    private var lastRequestedSize: PtySize? = null

    /**
     * Find-in-scrollback panel state. Its own class: the panel owns a coroutine, a throttle and a
     * six-field publish of its own, none of which the buffer or the PTY care about.
     */
    // Each overlay's onOpen closes the other, so neither type can be inferred from the other and
    // both are spelled out — that is a compile-time need, not the safety argument. What makes the
    // forward reference safe is that neither constructor invokes its callbacks: they run only when
    // something calls open(), which cannot happen before this constructor returns.
    val search: TerminalOutputSearch = TerminalOutputSearch(
        scope = scope,
        nowMillis = nowMillis,
        buffer = { screen },
        // The two overlays cannot both hold the keyboard.
        onOpen = { reverseSearch.close() },
    )

    /**
     * Ctrl-R overlay over the shell line. Its own class: it is a picker over command history with
     * a query and a cursor, and shares nothing with the buffer or the PTY beyond the history it
     * reads and the command it hands back.
     */
    val reverseSearch: TerminalReverseSearch = TerminalReverseSearch(
        canOpen = { !altScreen },
        matches = { q -> autocomplete.commandHistory.search(q) },
        onOpen = { search.close() },
        onAccept = { applyHistoryCommand(it) },
        onForget = { forgetHistoryCommand(it) },
    )


    /** The paced owner loop, extracted to its own class — see [EmulatorOwnerLoop]. */
    private suspend fun runEmulatorOwner() {
        EmulatorOwnerLoop(
            commands = commands,
            schedule = EmulatorPublicationSchedule(
                nowMillis = nowMillis,
                refreshRequested = { renderRefreshRequested },
                publishIntervalMillis = { inputPublication.intervalMillis(!backgroundWhenUnobserved || renderers > 0) },
                pendingPublishDelay = {
                    if (unpublishedRender) (BACKGROUND_PUBLISH_INTERVAL_MS - (nowMillis() - lastFullPublishAt)).coerceAtLeast(0)
                    else null
                },
            ),
            apply = ::applyCommand,
            publish = ::publishSnapshot,
            heldFor = ::synchronizedHoldMillis,
        ).run()
    }

    /** Apply one command to the emulator (does not publish a snapshot; the caller batches that). */
    private suspend fun applyCommand(cmd: TerminalCommand) {
        applyInterceptor?.invoke()
        when (cmd) {
            is TerminalCommand.Renderers -> {
                renderers = (renderers + cmd.delta).coerceAtLeast(0)
                if (cmd.delta > 0) renderRefreshRequested = true
            }
            // The permit was acquired by the session collector when the chunk was queued; releasing
            // it only after the parse is what makes the cap measure *unapplied* work. In finally:
            // a parser exception kills this loop, and a permit that never returns would wedge the
            // still-live collector (SupervisorJob scope - siblings do not die together) on acquire,
            // stalling the socket read for every subscriber of the session flow.
            is TerminalCommand.Feed -> try {
                feed(cmd.chunk)
            } finally {
                commandQueue.feedApplied()
            }
            is TerminalCommand.StartRecording -> startRecorder(cmd)
            is TerminalCommand.StopRecording -> {
                cmd.cast.complete(recorder?.finish())
                recorder = null
            }
            is TerminalCommand.SetCursorDefault -> emulator.applyCursorDefault(cmd.shape, cmd.blink)
            is TerminalCommand.SetMaxScrollback -> emulator.applyMaxScrollback(cmd.lines)
            is TerminalCommand.SetClipboardWriteEnabled -> emulator.applyClipboardWrite(cmd.enabled)
            is TerminalCommand.SetColors -> emulator.applyColors(cmd.colors)
            is TerminalCommand.ExpectStep -> applyExpectStep(cmd.token, cmd.hiddenEcho)
            is TerminalCommand.Resize -> applyTerminalResize(
                session, emulator, cmd.size,
                onFailure = { lastRequestedSize = null },
                beforeEmulatorResize = { emulatorResizeInterceptor?.invoke() },
            )
        }
    }

    // Mode 2026 hold: when the current hold began (NOT_HOLDING since the last publish), and the
    // frame a publish already showed torn — that one is not held again.
    private var holdSince = NOT_HOLDING
    private var releasedFrame = -1

    /**
     * How much longer an open synchronized-output frame (mode 2026) holds publishing; 0 when none
     * is open or the hold ran out. The budget runs from the first held moment since the last
     * publish, not per frame: an application that closes one frame and opens the next in the same
     * write would otherwise restart it forever and never be drawn. Owner coroutine only.
     */
    private fun synchronizedHoldMillis(): Long {
        if (!emulator.synchronizedOutput || emulator.synchronizedFrame == releasedFrame) return 0
        val now = nowMillis()
        if (holdSince == NOT_HOLDING) holdSince = now
        return (SYNCHRONIZED_OUTPUT_TIMEOUT_MS - (now - holdSince)).coerceAtLeast(0)
    }

    /** Feeds one PTY chunk to the parser, recording it first when a recording is running. */
    private fun feed(chunk: ByteArray) {
        recorder?.let {
            it.record(chunk)
            if (it.truncated && !recordingTruncated) recordingTruncated = true
        }
        emulator.feed(chunk)
        feedCount++
    }

    /**
     * Starts the recorder on the emulator's own coroutine. Elapsed time comes off a monotonic
     * source: a wall clock can step backwards (NTP, suspend/resume) and take the event timeline with
     * it. The epoch stamp is only the header's "when was this recorded".
     */
    private fun startRecorder(cmd: TerminalCommand.StartRecording) {
        val started = TimeSource.Monotonic.markNow()
        recorder = SessionRecorder(
            columns = cmd.columns,
            rows = cmd.rows,
            startedAtEpochSeconds = cmd.startedAtMillis / 1000,
            title = cmd.title,
            now = { started.elapsedNow().inWholeMilliseconds },
        )
    }

    /**
     * Applies a step declaration on the emulator's own coroutine: which mark to report, and the echo
     * to hide. Ending a step also drops a report nobody collected — with no step expected, a
     * captured command's output (a resolved secret possibly among it) has no reason to stay resident.
     */
    private fun applyExpectStep(token: String?, hiddenEcho: List<String>) {
        emulator.expectStep(token, hiddenEcho)
        if (token == null) stepMark = null
    }

    /**
     * Publish the emulator snapshot into Compose state (after feed/resize). Must stay
     * non-suspend: the owner loop's final tail flush depends on no suspension point here.
     */
    private fun publishSnapshot(force: Boolean = false) {
        // A frame still open here is drawn torn (its hold ran out): holding it any longer gains
        // nothing, so what follows it is drawn at the usual pace until the next frame opens.
        holdSince = NOT_HOLDING
        if (emulator.synchronizedOutput) releasedFrame = emulator.synchronizedFrame
        val now = nowMillis()
        val full = force || !backgroundWhenUnobserved || renderers > 0 || renderRefreshRequested ||
            now - lastFullPublishAt >= BACKGROUND_PUBLISH_INTERVAL_MS
        // The grid and the cursor apply as one atomic group: auto-fit reads them as a tuple under
        // snapshotFlow, and individual writes from this (session) thread could pair a fresh screen
        // with a stale cursor there — counting the user's own wrapped command line as wide output.
        // Single writer, so the apply cannot conflict.
        if (full) Snapshot.withMutableSnapshot {
            screen = emulator.lines // the cached instance while nothing visible mutated
            screenContentVersion = emulator.contentVersion
            cols = emulator.cols
            rows = emulator.rows
            cursorRow = emulator.cursorRow
            cursorCol = emulator.cursorCol
            // Marks and cwd ride the same atomic group as the rows they address: a marker drawn
            // against a newer screen than its mark list would point one publish off.
            commandMarks = emulator.shellCommandMarks()
            workingDirectory = emulator.workingDirectory
        }
        // Input may arrive in a hidden pane through broadcast/runbooks/synchronized panes. Its
        // password/guard checks cannot wait for a slower render snapshot or read a mutable parser.
        inputLine = if (full) CursorLine(screen, cursorRow, cursorCol, rows) else emulator.inputLineSnapshot()
        cursorVisible = emulator.cursorVisible
        cursorShape = emulator.cursorShape
        cursorBlink = emulator.cursorBlink
        applicationCursorKeys = emulator.applicationCursorKeys
        applicationKeypad = emulator.applicationKeypad
        mouseTracking = emulator.mouseTracking
        mouseSgr = emulator.mouseSgr
        mousePixels = emulator.mousePixels
        bracketedPaste = emulator.bracketedPaste
        focusReporting = emulator.focusReporting
        // Crossing the alt-screen boundary resets the tracked line in both directions: entering,
        // because whatever was typed next goes into a file and must not complete a shell line it
        // never joined; leaving, because the TUI owned the keyboard and the shell's line is fresh.
        // Best-effort by design: the engine's fields lose but never tear (see AutocompleteEngine),
        // so a keystroke racing this publish can keep the pre-TUI line until Enter or Ctrl-U
        // clears it — the same window every cross-thread engine write already lives with.
        if (altScreen != emulator.altScreen) autocomplete.reset()
        altScreen = emulator.altScreen
        title = emulator.title
        workingDirectory = emulator.workingDirectory
        if (!full) {
            // A hidden surface never offers a credential on a row its user cannot read. Also
            // withdraw a previous offer at foreground cadence when that surface disappears.
            sudo?.observe(PromptRow(-1, ""), now)
            unpublishedRender = true
            return
        }
        lastFullPublishAt = now
        renderRefreshRequested = false
        unpublishedRender = false
        // Arm or disarm the saved-password offer from the row that was just drawn, and stamp when
        // it appeared: the dwell [SudoPasswordOffer.take] requires is measured from here, which is
        // the only place that knows when the prompt reached the screen. Never on the alternate
        // screen — a fullscreen TUI paints arbitrary text, a line reading like a sudo prompt
        // included, and an Enter there edits a buffer.
        sudo?.observe(
            if (altScreen || (backgroundWhenUnobserved && renderers == 0)) PromptRow(-1, "")
            else PromptRow(cursorRow, cursorLine().rowText()),
            nowMillis(),
        )
        // The echo of what was typed arrives here, and the ghost continues what this snapshot shows —
        // so this is where it is recomputed. Also clears it on entering a fullscreen TUI (vim/htop):
        // there is no "line" there.
        refreshSuggestion()
        palette = emulator.paletteSnapshot()
        // The buffer changed under an open search panel: rebuild the match list (throttled — see
        // refreshSearch) so the counter and navigation follow the output, keeping the user on the
        // hit they were reading.
        search.refreshFromSnapshot()
        snapshotVersion++
    }

    /** Start a selection at [pos] (mouse down): anchor and focus coincide, empty for now. */
    fun beginSelection(pos: TerminalPos) {
        selection = TerminalSelection(anchor = pos, focus = pos)
    }

    /** Extend the selection to [pos] (drag): moves focus, anchor stays put. */
    fun extendSelection(pos: TerminalPos) {
        selection = selection?.copy(focus = pos)
    }

    /**
     * Select the whole word under [pos] (long-press): the contiguous run of non-space (or space)
     * cells on the row ([wordSelectionAt]). An empty run does not set a selection.
     */
    fun selectWordAt(pos: TerminalPos) {
        selection = wordSelectionAt(screen, pos).takeIf { !it.isEmpty }
    }

    /** Select the whole row under [pos] (mouse triple-click, [lineSelectionAt]). */
    fun selectLineAt(pos: TerminalPos) {
        selection = lineSelectionAt(screen, pos).takeIf { !it.isEmpty }
    }

    /**
     * Move the selection's top-left bound to [pos] (dragging the start marker): the bottom-right
     * bound stays as anchor, the new position becomes focus. No-op without a selection.
     */
    fun moveSelectionStart(pos: TerminalPos) {
        selection = selection?.let { TerminalSelection(anchor = it.end, focus = pos) }
    }

    /**
     * Move the selection's bottom-right bound to [pos] (dragging the end marker): the top-left
     * bound stays as anchor, the new position becomes focus. No-op without a selection.
     */
    fun moveSelectionEnd(pos: TerminalPos) {
        selection = selection?.let { TerminalSelection(anchor = it.start, focus = pos) }
    }

    /** Clear the selection (click / new input). */
    fun clearSelection() {
        selection = null
    }

    /** Text of the current selection to copy, or `null` if there is nothing to select. */
    fun selectedText(): String? = selection
        ?.takeIf { !it.isEmpty }
        ?.extract(screen)
        ?.takeIf { it.isNotEmpty() }

    /** The path of the `file://` hyperlink the selection lies inside, or `null` (see [fileLinkPathOfSelection]). */
    fun selectedFileLinkPath(): String? = selection?.let { fileLinkPathOfSelection(screen, it) }

    /**
     * The path the selection stands for: a `file://` link's target first - its text is only what the
     * server chose to print - then the selected text itself when it reads as a path.
     */
    fun selectedPath(): String? = if (selectionIsSafePathCandidate()) {
        selectedFileLinkPath() ?: filePathFromSelection(selectedText())
    } else null

    /**
     * Best-effort text of the last command and its output — for "explain this output" when nothing is
     * selected, so the AI sees the recent result rather than the whole screen (a long login banner
     * would otherwise drown it out). `null` when the command boundary can't be found; the caller then
     * falls back to the whole visible screen. See [lastCommandBlock] for the heuristic.
     *
     * With shell integration (OSC 133) the boundaries are exact: the last command that reported an
     * output start is quoted from its own C anchor to its D (see [commandOutputSelection]) — the
     * heuristic only serves a shell without integration.
     */
    fun lastOutput(): String? {
        for (mark in commandMarks.asReversed()) {
            val text = commandOutputSelection(mark)?.let { outputSelection ->
                outputSelection.extract(screen).takeIf { it.isNotEmpty() }
            }
            if (text != null) return text
        }
        return lastCommandBlock(output)
    }

    /** Selects a command's output — the gutter marker's click. Copy then works as with any selection. */
    fun selectCommandOutput(mark: ShellCommandMark) {
        selection = commandOutputSelection(mark)?.takeIf { !it.isEmpty }
    }

    /**
     * In-app PRIMARY buffer: text of the last mouse selection. Used for middle-click paste where
     * the system PRIMARY selection is unavailable (Wayland: AWT `getSystemSelection()`==null) —
     * paste then falls back to this instead of CLIPBOARD.
     */
    var primarySelection: String? = null
        private set

    /**
     * Capture the current selection as PRIMARY (called when a mouse selection completes). Returns
     * the saved text, or `null` if there is nothing to select (buffer is then left untouched).
     */
    fun capturePrimarySelection(): String? {
        val text = selectedCopyText() ?: return null
        primarySelection = text
        return text
    }

    // --- Autocomplete ---
    // The engine tracks the line the user is typing and suggests a completion from this session's
    // command history plus common commands. Scoped to the session. Suggestions only apply in
    // normal (non-alt-screen) mode: fullscreen TUIs (vim/htop) have no "line".
    private val autocomplete = AutocompleteEngine(
        CommandHistory().apply { if (initialHistory.isNotEmpty()) preload(initialHistory) },
    )

    /**
     * What the syntax highlighter is allowed to call a command. Rebuilt when a command is committed,
     * not per keystroke: it is a set built off the whole history, and the answer can only change
     * once a new command has actually run.
     */
    var vocabulary: CommandVocabulary by mutableStateOf(SessionVocabulary(initialHistory))
        private set

    /**
     * Commands this session has run, for keeping an executed command colored after Enter. A set of
     * exact command texts, not a heuristic: matching a scrollback line against it is what stops
     * output that merely contains a `$ ` from being colored as input.
     */
    var executedCommands: Set<String> by mutableStateOf(emptySet())
        private set

    // Insertion-ordered so the oldest entry can be dropped once the cap is reached; only commands
    // still on screen can ever match, so keeping every command of a week-old session buys nothing.
    private val executed = LinkedHashSet<String>()

    /**
     * What to draw in gray at the cursor of the published snapshot: the rest of the suggested command
     * after the part the host has echoed back. `null` when there is nothing to continue there — the
     * echo has not started, the line wrapped onto the next row, or the shell redrew it. The
     * suggestion may exist anyway; [hasSuggestion] is what decides whether Tab has something to
     * accept, and it always accepts the command this tail belongs to.
     */
    var suggestionTail: String? by mutableStateOf(null)
        private set

    /**
     * Whether there is a suggestion to accept, drawable or not. Tab (accept), Shift+Tab (cycle) and
     * the mobile keycaps key off this: the completion exists the moment the key is typed, and gating
     * them on [suggestionTail] would send a raw Tab to the shell — or, on Shift+Tab, an ESC[Z that
     * the engine reads as navigation and drops the tracked line — while the echo is in flight. False
     * once the screen and the tracked line have genuinely diverged (a wrapped or shell-redrawn line),
     * where completing would edit a line the user is no longer on.
     */
    var hasSuggestion: Boolean by mutableStateOf(false)
        private set

    /**
     * Synchronized-input hook: called with input this session actually delivered, so the same keys
     * (and pastes) reach the other panes of the tab. Wired by the UI while the tab's sync toggle is
     * on, `null` otherwise. Not snapshot state — it is written from composition and only read on
     * input, and recomposing every keystroke's worth of terminal for it would be waste.
     *
     * Callers that deliver mirrored input must pass `mirror = false`, or two synchronized panes
     * would keep handing the same keystroke back to each other.
     */
    var inputMirror: ((String, MirroredInput) -> Unit)? = null

    /**
     * Whether this session is taking a secret right now: the transport reported that the host
     * stopped echoing (password entry), or the current screen line reads as a password prompt.
     *
     * Input typed here is kept out of history and out of the production guard, and synchronized
     * panes read it to decide whether a secret may be mirrored into them at all
     * ([app.skerry.ui.session.paneSyncTargets]).
     */
    val awaitingSecret: Boolean get() = session.echoSuppressed || atPasswordPrompt()

    /**
     * Keyboard/IME user input: feeds the autocomplete engine (line and history tracking) and sends
     * to the PTY. Separate from [send]/[sendBytes], which are the raw paths — what they carry
     * reaches the tracked line only where the sender says what it did to it
     * ([TerminalCommandGuard.trackSent]).
     */
    fun typeInput(text: String, guarded: Boolean = true, mirror: Boolean = true, mirrored: Boolean = false) {
        inputVersion++
        // The saved password offered at a sudo prompt (issue #360): Enter takes it, and anything
        // else declines the offer and is forwarded normally. Ahead of the secret path below because
        // this Enter is not input for the host at all - it is the answer to the client's own offer.
        // Only a keypress made on this pane can take it: input mirrored from a synchronized pane
        // was aimed at that pane's screen, and the user reading its prompt never saw this one.
        if (sudo != null && sudoOffer) {
            if (!mirrored && isEnterKey(text) && sendSudoPassword()) {
                // Carries no bytes: each synchronized pane answers its own prompt with its own
                // credential, and a pane with no offer standing is left alone. paneSyncTargets has
                // already narrowed the fan-out to panes at a password prompt, so a bare Enter
                // forwarded there would submit an empty password and burn one of their attempts.
                if (mirror) inputMirror?.invoke(text, MirroredInput.SudoAnswer)
                return
            }
            declineSudoOffer()
        }
        // Server not echoing input (password entry / line-mode signaled by the transport): do not
        // track the line or write it to history, so a secret does not persist and surface as a
        // suggestion. SSH echo status is unavailable (always false), so a password prompt is also
        // detected heuristically from the current screen line ([atPasswordPrompt]).
        // The production guard is skipped here for the same reason: what is being typed is a secret,
        // and parking it in a confirmation dialog would put it on screen in clear text.
        if (awaitingSecret) {
            autocomplete.reset()
            refreshSuggestion()
            outbound.sendInput(text)
            // A secret is mirrored like anything else typed: entering one sudo password across
            // synchronized panes is the case people turn the toggle on for. Each pane decides on its
            // own screen whether to keep it out of history — this one just did.
            if (mirror) inputMirror?.invoke(text, MirroredInput.Typed)
            return
        }
        // guarded=false: the caller already asked (broadcast confirms once for the whole fan-out,
        // where a per-session hold would strand commands in tabs nobody is looking at).
        if (guarded && guard.holdTyped(text)) return
        deliverTypedInput(text, mirror)
    }

    private fun deliverTypedInput(text: String, mirror: Boolean = true) {
        // Inside a fullscreen TUI there is no shell line: what is typed edits a file, and an Enter
        // commits a line of IT — not a command. The engine sees none of it, or a token from an
        // edited config would land in vault-backed history and surface as a ghost suggestion and in
        // the cross-host palette.
        if (altScreen) {
            outbound.sendInput(text)
            if (mirror) inputMirror?.invoke(text, MirroredInput.Typed)
            return
        }
        // Taken before the input is applied: what the shell completed with a Tab is on the line now,
        // and the engine is about to clear it.
        val blind = autocomplete.linePartial
        val typed = autocomplete.currentLine
        val committed = autocomplete.onUserInput(text.encodeToByteArray())
            // The engine records nothing for a line it only holds a prefix of, and the prefix is not
            // a command anyone ran. What the shell finished is on the screen, and this is the layer
            // that can read it — without this a session where paths are tab-completed, which is most
            // of them, builds no history at all.
            // Only a block that runs the line as it stands: one that adds to it first leaves the
            // screen a row short of what ran, and `typed` a row behind the screen.
            // Session-only: the finished text is the host's drawing, so it serves this session's
            // ghost and reverse search but is never persisted — a stored copy would surface in the
            // cross-host palette as a command nobody typed.
            ?: text.takeIf { blind && it.firstOrNull()?.let { c -> c in RUN_LINE_CONTROLS } == true }
                ?.let { guard.completedLine(typed) }
                ?.also { autocomplete.commandHistory.record(it, sessionOnly = true) }
        refreshSuggestion(lineChanged = true)
        outbound.sendInput(text)
        // Mirrored from here, not from typeInput: input held by the production guard must reach the
        // other panes only once it is confirmed (this runs again on confirm), never on the hold.
        if (mirror) inputMirror?.invoke(text, MirroredInput.Typed)
        // Command was committed with Enter (and was echoed): persist the history snapshot for this host.
        if (committed != null) {
            val commands = autocomplete.commandHistory.commands
            onHistoryChanged?.invoke(autocomplete.commandHistory.persistedCommands)
            // The host's own tooling becomes a known command after its first run.
            vocabulary = SessionVocabulary(commands)
            // Only commands run *here* count: a preloaded history belongs to earlier sessions whose
            // lines are not on this screen, and matching against them could color unrelated output.
            executed.remove(committed)
            executed.add(committed)
            while (executed.size > MAX_EXECUTED_COMMANDS) executed.remove(executed.first())
            executedCommands = executed.toSet()
        }
    }

    // --- Production guard ---
    // On a host tagged #prod a risky command is confirmed before it reaches the PTY. Enabled by the
    // UI from the session's host profile (see [app.skerry.ui.host.isProdHostId]) and kept live, so
    // adding or removing the tag arms/disarms an open session.

    // The hold/confirm/dismiss rules live in [ProductionGuardHold]; which candidates a path offers
    // and what may be quoted for them live in [TerminalCommandGuard]. What belongs here is only how
    // a held block is replayed, and the secret/alt-screen state the guard cannot know.
    private val guard = TerminalCommandGuard(
        engine = autocomplete,
        altScreen = { altScreen },
        lineToCursor = { cursorLine().toCursor() },
        rowText = { cursorLine().logicalText() },
        lineContinues = { cursorLine().continues() },
    )

    /**
     * What the production guard asks about in this session (host tag, root login, the
     * confirm-warnings setting). [ProductionGuardPolicy.Off] — no guard at all.
     */
    var guardPolicy: ProductionGuardPolicy
        get() = guard.hold.policy
        set(value) { guard.hold.policy = value }

    /** Command held by the guard, awaiting the user's confirmation; `null` when nothing is pending. */
    val pendingGuarded: GuardedCommand? get() = guard.hold.pending

    /** What [confirmGuardedCommand] would run, as the confirmation has to quote it. */
    val pendingGuardedQuote: String get() = guard.hold.pendingQuote

    /** How long that input really is — the quote stops at what a dialog can draw, this does not. */
    val pendingGuardedQuoteLength: Int? get() = guard.hold.pendingQuoteLength

    /** The line the guard tripped on when the quote does not carry it; drawn beside the quote. */
    val pendingGuardedAside: GuardAside? get() = guard.hold.pendingAside

    /**
     * Runs a ready-made command (snippet, palette, any caller that already has the full line) with
     * the guard in front of it. Unlike [typeInput] the command itself is known verbatim, but it
     * still lands on whatever the line already holds — which is what the guard's line guess covers.
     * Falls back to [sendUserInput] when the session is not production or the command is harmless.
     *
     * [secrets] are the resolved vault values the line carries (a snippet's or runbook step's
     * `${'$'}{{vault:…}}`): the dialog masks them exactly as the run confirmation did one step
     * earlier, instead of printing the resolved line in clear.
     */
    fun sendUserInputGuarded(text: String, secrets: List<String> = emptyList()) {
        // The same exemption [typeInput] makes: a line answering a password prompt is a secret, and
        // parking it in a confirmation dialog would put it on screen in clear text.
        if (awaitingSecret) {
            sendUserInput(text)
            return
        }
        if (guard.holdCommand(text, secrets)) return
        sendUserInput(text)
    }

    /** Run the held command: replays the original input exactly as the path it came from would. */
    fun confirmGuardedCommand() {
        val held = guard.hold.take() ?: return
        when (held.from) {
            HeldInputSource.Typed -> deliverTypedInput(held.text)
            HeldInputSource.Command -> sendUserInput(held.text)
            HeldInputSource.Paste -> deliverPaste(held.text)
        }
    }

    /**
     * Drop the held command. A typed one leaves its line in the shell (the characters were echoed
     * as they were typed) ready to be edited; a pasted or ready-made command never reached the
     * host, so dismissing discards it outright.
     */
    // The tracked line is deliberately left alone. A question with no command in it came from the
    // line itself, so dropping it would drop the only thing that asked — and the next Enter over the
    // same shell line would go through unasked. Asking again is the annoying answer; Ctrl-C or
    // Ctrl-U is the way out, and both really do clear the line.
    fun dismissGuardedCommand() = guard.hold.dismiss()

    /**
     * Immutable input view, replaced by the emulator owner at frame cadence. Visible surfaces use
     * the render grid; hidden surfaces freeze only the bounded logical cursor line, so an input
     * check never races a mutable emulator or waits for a slower render snapshot.
     */
    private fun cursorLine(): CursorLine = inputLine ?: CursorLine(screen, cursorRow, cursorCol, rows)

    /**
     * Whether the current cursor row looks like a password prompt (echo is usually off there). The
     * rule itself is [isPasswordPrompt], shared with the sudo detector so the two cannot disagree
     * about what a prompt is — a row that offers the saved password but does not read as a prompt
     * would put a hand-typed secret into history.
     */
    private fun atPasswordPrompt(): Boolean = isPasswordPrompt(cursorLine().rowText())

    /**
     * Whether an offer of the saved password stands on the prompt the cursor is on. Armed by the
     * publish path ([SudoPasswordOffer.observe]) rather than computed here, so reading it costs a
     * snapshot read per frame instead of joining the cursor row; the terminal draws its hint from
     * this and [typeInput] turns the next Enter into the password. See [SudoPasswordOffer] for why
     * an explicit keypress — and the dwell before it — is the whole of the safety here.
     */
    val sudoOffer: Boolean get() = sudo?.stands == true

    /**
     * The forms of Enter that may answer the saved-password offer: the two plain ones, plus the
     * numpad's SS3 under application-keypad mode (DECKPAM), which a TUI can leave set behind it.
     * Without that one the hint names a key that spends the offer on an empty answer instead of
     * taking it. Deliberately narrower than [RUN_LINE_CONTROLS] otherwise: readline's Ctrl-O also
     * runs the line, but the offer is answered by the key its hint names and by nothing else.
     */
    private fun isEnterKey(text: String): Boolean =
        text == "\r" || text == "\n" || text == NUMPAD_ENTER_SS3

    /** Whose password the hint says it is about to send, so a nested shell shows as a mismatch. */
    val sudoAccount: String get() = sudo?.account.orEmpty()

    /**
     * The user is answering the prompt themselves — by typing, pasting, or from another surface.
     * The Enter that ends what they are entering has to commit that, not the saved password.
     */
    private fun declineSudoOffer() {
        sudo?.decline()
    }

    /**
     * Hand the saved password to the prompt on screen, if the offer still stands. Returns whether
     * it was sent, because the caller has a keystroke in hand either way: an offer withdrawn by a
     * redraw, a viewer of the shared session, or an unserved dwell must not swallow the Enter.
     *
     * Sent through [send], not [sendUserInput]: nothing about it belongs on the tracked line, and
     * the secret itself is never mirrored into synchronized panes — it belongs to this session's
     * host, and the pane beside it may be another one. What a pane mirrors is the answer, so each
     * one answers its own prompt with its own credential ([MirroredInput.SudoAnswer]).
     */
    private fun sendSudoPassword(): Boolean {
        val secret = sudo?.take(nowMillis()) ?: return false
        // The same reset the secret path in [typeInput] makes: with the echo off nothing will
        // redraw, and a ghost left over from the line the prompt interrupted would sit at the
        // cursor of a password prompt.
        autocomplete.reset()
        refreshSuggestion()
        outbound.sendInput(secret + "\r")
        return true
    }

    /**
     * Typed input arriving from a synchronized pane ([app.skerry.ui.session.mirrorPaneInput]).
     *
     * The one entry point for it, so the three things that make mirrored input different cannot be
     * got wrong at a call site: the origin pane already held and confirmed the command for the whole
     * group, a copy that mirrored again would bounce between panes forever, and — the one that
     * carries a secret — a keypress made on another pane's screen must never take this pane's saved
     * password. The user read that pane's prompt, not this one's.
     */
    fun receiveMirrored(text: String) {
        typeInput(text, guarded = false, mirror = false, mirrored = true)
    }

    /**
     * Answer this pane's own sudo prompt because a synchronized pane just answered its own
     * ([MirroredInput.SudoAnswer]). Entering one sudo password across the group is the case the
     * toggle exists for, and the panes are separate hosts: each sends the credential it
     * authenticated with, never the origin's.
     *
     * A pane whose own offer is not standing is left alone, and that is not a missed keystroke: the
     * origin is at a sudo prompt when it takes its offer, so
     * [app.skerry.ui.session.paneSyncTargets] has already narrowed the targets to panes that are
     * themselves at a prompt. Sending a bare Enter here would submit an EMPTY password to that
     * host's sudo and burn one of its three attempts — seven times over on an eight-pane group.
     * The prompt stays on screen for its own user to answer.
     */
    fun answerSudoPrompt() {
        if (sendSudoPassword()) inputVersion++
    }

    /**
     * Turning "Offer the saved password to sudo" off under a live session ends its offer and drops
     * the password, the way [applyClipboardWriteEnabled] carries its own setting into open panes —
     * a toggle the user reaches for because a session is behaving oddly has to take effect there.
     */
    fun applySudoOfferEnabled(enabled: Boolean) {
        if (!enabled) sudo?.revoke()
    }

    /**
     * Accept the current autocomplete suggestion: sends its tail to the PTY (the shell echoes it).
     * Returns `true` if there was something to accept, else `false`.
     *
     * The tail comes from the tracked line, while the ghost is drawn from what the screen has echoed
     * ([refreshSuggestion]) — so while the echo lags, what is sent completes what was typed, not the
     * older line the ghost is still continuing. Completing the visible line instead would drop the
     * characters already in flight.
     */
    fun acceptSuggestion(): Boolean {
        if (altScreen) return false
        val tail = autocomplete.acceptSuggestion() ?: return false
        refreshSuggestion(lineChanged = true)
        outbound.send(tail, userInput = true)
        return true
    }

    /**
     * Cycle the ghost suggestion to the next alternative (Shift+Tab). No-op in alt-screen. Does not
     * touch the PTY line; only the proposed tail changes until accepted.
     */
    fun cycleSuggestion() {
        if (altScreen) return
        autocomplete.cycleSuggestion()
        refreshSuggestion()
    }

    /**
     * Remove [command] from the autocomplete history (manual cleanup of typos/unwanted commands)
     * and persist the update. Adjusts the reverse-search index to stay in bounds.
     */
    fun forgetHistoryCommand(command: String) {
        if (!autocomplete.forget(command)) return
        reverseSearch.clampIndex()
        onHistoryChanged?.invoke(autocomplete.commandHistory.persistedCommands)
        refreshSuggestion()
    }

    /**
     * Insert a command picked from history: clears the current shell line (Ctrl-U) and types it in
     * so the user can edit/run it. Goes through [typeInput] so the engine sees the line and the
     * echoSuppressed gate still applies.
     */
    fun applyHistoryCommand(command: String) {
        sendBytes(byteArrayOf(0x15)) // Ctrl-U: kill current input line (readline kill-line)
        autocomplete.reset()
        typeInput(command)
    }

    /**
     * Recomputes what the ghost shows and whether Tab has anything to accept.
     *
     * The ghost is drawn at the cursor of the published snapshot, so its text must continue what that
     * snapshot shows — [echoedLine], the part of the tracked line the host has echoed back. Deriving
     * it from the tracked line instead makes it jump a cell per keystroke (the cursor has not moved
     * yet); hiding it until the echo lands makes it blink off for the round trip instead. Following
     * the screen does neither: the completed command stands still while the typed part grows into it.
     *
     * [lineChanged] only opens the window in which Tab still works while the echo is in flight. What
     * leaves an already-drawn ghost alone is a line that just got SHORTER: the erased characters are
     * still on screen, so the ghost of the longer line is what belongs there — and nothing may be
     * accepted in that window either, or Tab would insert a command other than the visible one.
     */
    private fun refreshSuggestion(lineChanged: Boolean = false) {
        val line = autocomplete.currentLine
        val echoed = echoedLine(line)
        val caughtUp = line.isNotEmpty() && echoed == line
        // A local edit opens the window Tab must survive; a snapshot closes it once the screen either
        // confirms the whole line or shows none of it. A snapshot confirming just a prefix means the
        // echo is still arriving, so the window stays open.
        echoPending = if (lineChanged) true else echoed.isNotEmpty() && !caughtUp
        val shrank = line.length < trackedLength
        trackedLength = line.length
        // One candidate for both jobs: what Tab inserts is what the ghost shows, so the key never does
        // something other than what is on screen. It is chosen for the tracked line — the line Tab
        // completes — and only its rendering is cut back to the echoed prefix below.
        val chosen = if (altScreen) null else autocomplete.suggestion()
        hasSuggestion = chosen != null && !shrank && (echoPending || caughtUp)
        if (chosen == null) {
            suggestionTail = null
            return
        }
        if (shrank) return
        suggestionTail = if (echoed.isNotEmpty() && chosen.startsWith(echoed)) chosen.substring(echoed.length) else null
    }

    // Whether the tracked line moved since the last published snapshot, i.e. the screen has not seen
    // the latest keystroke/paste yet.
    @Volatile
    private var echoPending = false

    // Tracked line length at the last refresh, to tell a local erase from anything else.
    @Volatile
    private var trackedLength = 0

    /**
     * The longest prefix of the tracked line the screen confirms — the cursor row up to the cursor
     * ends with it, so it is exactly what a ghost drawn at the cursor continues. Empty when nothing
     * of the line is on screen: the echo has not started, the line wrapped onto the next row, or the
     * shell redrew it (Ctrl-W, its own Tab completion) — in all three there is nothing to continue.
     */
    private fun echoedLine(line: String): String {
        if (line.isEmpty()) return ""
        val onScreen = cursorLine().toCursor()
        // Compared in place: this runs on every published snapshot, and a substring per candidate
        // length would allocate through the whole line on each batch of output.
        var length = minOf(onScreen.length, line.length)
        while (length > 0 && !onScreen.regionMatches(onScreen.length - length, line, 0, length)) length--
        return line.substring(0, length)
    }

    /** Send typed text to the PTY (fire-and-forget via the [outbound] queue, FIFO order). */
    fun send(text: String) {
        outbound.send(text.encodeToByteArray())
    }

    /**
     * [send] for input that does not go through the typed-input path — keybar control sequences,
     * snippet output, an AI-confirmed command. Bumps [inputVersion] so the viewport snaps back to
     * the live screen like [typeInput] — unlike plain [send], whose programmatic traffic
     * (mouse/DSR/focus reports) must never yank the viewport.
     */
    fun sendUserInput(text: String) {
        inputVersion++
        // A snippet, a keybar sequence or an AI-confirmed line answering the prompt is the user
        // entering their own secret, exactly as typing one is.
        declineSudoOffer()
        // The engine never sees this input by itself, so it is told what the input did to the line —
        // otherwise the guard, the suggestion and the history all work off a line that was left
        // behind two commands ago. Applied here rather than posted to the emulator's queue: the next
        // keystroke is classified against the tracked line synchronously, and an update sitting
        // behind a backlog of output would land after it and overwrite a newer line with an older
        // one. Every field it writes is a value replaced whole, so a runbook step advancing on its
        // own dispatcher can lose an update to the typist but cannot tear one.
        // Not while a secret is being taken: the tracked line is where history comes from, and the
        // typed and pasted paths already drop it there. A snippet answering a password prompt is
        // input like any other.
        if (awaitingSecret) {
            autocomplete.reset()
            // As [typeInput] does: with the echo off nothing else will redraw, and a ghost left over
            // from the line that was just cleared would sit at the cursor of a password prompt.
            refreshSuggestion()
        } else if (!altScreen) {
            // Not on the alternate screen either: a line sent into a TUI lands in a file or a
            // prompt of its own, not on the shell's line the tracker models.
            guard.trackSent(text)
            // The ghost belonged to the line as it was; drawn over the new one it offers a
            // completion of text the shell does not have, and Tab would send its tail.
            refreshSuggestion(lineChanged = true)
        }
        outbound.sendInput(text)
    }

    /**
     * Send raw bytes to the PTY (fire-and-forget). Used for mouse reports: legacy encoding bytes
     * can exceed 0x7f and must not be run through UTF-8 like [send] does.
     */
    fun sendBytes(bytes: ByteArray) {
        outbound.send(bytes)
    }

    /**
     * Live PTY output of this session for a consumer beside the emulator — session sharing streams
     * the same bytes to the team ([app.skerry.shared.share.SessionShareHost]). The session's output
     * is a hot flow with any number of subscribers, so this observes it rather than tapping the
     * emulator's own feed.
     */
    val ptyOutput: Flow<ByteArray> get() = session.output

    /**
     * Keystrokes from a viewer of this shared session. Delivered as raw bytes (a viewer sends key
     * sequences, not text) and counted as user input, so the screen snaps back to the bottom exactly
     * as it does when the owner types — otherwise the owner could be scrolled up in history while a
     * colleague works, and never see what they are doing.
     */
    fun sendSharedInput(bytes: ByteArray) {
        inputVersion++
        // A viewer of a shared session types on the same prompt the owner sees, so their keystroke
        // ends the offer exactly as the owner's would. The hint simply goes: it is deliberately not
        // replaced with a line explaining who withdrew it, because the owner's next Enter must
        // commit what the viewer entered either way, and that is what the hint's absence says.
        declineSudoOffer()
        outbound.send(bytes, userInput = true)
    }

    /** Rows of [screen] above the live grid: the scrollback on the primary buffer, none on the alt one. */
    val historyRows: Int get() = (screen.size - rows).coerceAtLeast(0)

    /**
     * Encode a mouse event per the emulator's current mode/encoding and send it to the PTY. Returns
     * `true` if a report was sent (event is reported in the active mode), else `false` so the
     * caller can handle it locally. No-op without mouse reporting.
     */
    fun reportMouse(
        button: MouseButton,
        type: MouseEventType,
        pos: TerminalPos,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
        pixelX: Int = 0,
        pixelY: Int = 0,
    ): Boolean {
        // [pos] is a cell of [screen], which on the primary buffer starts with the scrollback; the
        // application counts from the top of the live screen.
        val bytes = encodeMouseReport(
            mouseTracking, mouseSgr, button, type,
            pos.col.coerceIn(0, cols - 1), (pos.row - historyRows).coerceIn(0, rows - 1), shift, alt, ctrl,
            pixels = mousePixels, pixelX = pixelX, pixelY = pixelY,
        ) ?: return false
        sendBytes(bytes)
        return true
    }

    /**
     * Notify the application of a terminal window focus change: sends ESC[I (focus) or ESC[O
     * (blur) when focus reporting (DEC 1004) is enabled. No-op if the application never requested it.
     */
    fun notifyFocus(focused: Boolean) {
        if (focusReporting) send(focusReportSequence(focused))
    }

    /** Paste clipboard text: wraps it in markers when bracketed paste is enabled (DEC 2004). */
    fun paste(text: String, mirror: Boolean = true) {
        if (text.isEmpty()) return
        declineSudoOffer()
        // A paste carrying a newline runs the moment it lands — on a production session it goes
        // through the same confirmation as a typed command ([TerminalCommandGuard.holdPaste]).
        // The password-prompt exemption is [typeInput]'s, for the same reason: a manager pastes the
        // secret with a trailing newline, and holding it would print it in the dialog.
        // A middle-click paste arrives through a raw pointer handler the modal scrim never sees, so
        // this is the only place that can stop one while a confirmation is open.
        if (!awaitingSecret && guard.holdPaste(text)) return
        deliverPaste(text, mirror)
    }

    private fun deliverPaste(text: String, mirror: Boolean = true) {
        inputVersion++
        // A paste is tracked like typing: without this the Enter after a no-newline paste has
        // nothing to classify (the tracked line is empty and the host's echo may not have arrived
        // yet), and the guard would miss a pasted command. The engine also records the lines a
        // multi-line paste commits, same as if they had been typed. Not on the alternate screen:
        // there the paste lands in a file, and its lines are not commands that ran.
        if (!awaitingSecret && !altScreen) {
            autocomplete.onUserInput(text.encodeToByteArray())
            refreshSuggestion(lineChanged = true) // the paste moved the line; the screen has not seen it yet
        }
        outbound.sendInput(bracketedPasteWrap(text, bracketedPaste))
        // Mirrored as a paste, not as typing: each pane wraps it for its own bracketed-paste mode,
        // which the target may have set differently from this one.
        if (mirror) inputMirror?.invoke(text, MirroredInput.Pasted)
    }

    /**
     * Report a new grid size. Applied to both the emulator and the PTY through the same command
     * queue as [feed][TerminalEmulator.feed] (no race). Repeats of the same size are ignored.
     */
    fun resize(size: PtySize) {
        if (size.cols == lastRequestedSize?.cols && size.rows == lastRequestedSize?.rows) return
        lastRequestedSize = size
        commands.trySend(TerminalCommand.Resize(size))
    }

    /**
     * Change the default cursor style on an already-open session (setting changed live). Goes
     * through the same command queue as feed/resize, avoiding a race with the single-threaded
     * emulator; a snapshot is published automatically afterward so the cursor redraws immediately.
     */
    fun applyCursorStyle(shape: CursorShape, blink: Boolean) {
        commands.trySend(TerminalCommand.SetCursorDefault(shape, blink))
    }

    /**
     * Change scrollback depth on an already-open session (setting changed live). Goes through the
     * same command queue; on decrease, excess old rows are trimmed immediately and a snapshot is
     * published automatically.
     */
    fun applyScrollback(lines: Int) {
        commands.trySend(TerminalCommand.SetMaxScrollback(lines))
    }

    /**
     * Toggle whether server OSC 52 clipboard writes are honored on an already-open session (setting
     * changed live). Goes through the same command queue as feed/resize, so it can't race the
     * single-threaded emulator.
     */
    fun applyClipboardWriteEnabled(enabled: Boolean) {
        commands.trySend(TerminalCommand.SetClipboardWriteEnabled(enabled))
    }

    /**
     * The theme this session is drawn in, for answering OSC 10/11/12 and OSC 4 color queries — an
     * editor asking for the background picks its light or dark scheme from the answer. Called by
     * [TerminalScreen] whenever the theme it draws with changes; until the first call, queries go
     * unanswered.
     */
    fun applyTerminalTheme(theme: TerminalTheme) {
        commands.trySend(TerminalCommand.SetColors(theme.reportedColors()))
    }

    // The init block sits at the very END of the class body on purpose: it starts coroutines
    // that call publishSnapshot -> refreshSuggestion, which writes state properties declared
    // further down. Kotlin runs initializers in declaration order, so an init block placed above
    // them would let the first PTY chunk land before their `by mutableStateOf` delegates exist —
    // a NullPointerException inside setValue, which is what happened when a property whose
    // initializer took a moment was added between the two.
    init {
        // Sole collector of PTY output; forwards chunks into the command queue. Closes the queue
        // when output ends (EOF/session close), otherwise the owner loop below would hang forever
        // in `for (cmd in commands)`.
        scope.launch {
            try {
                session.output.collect { chunk ->
                    commandQueue.feed(chunk)
                }
            } catch (_: ClosedSendChannelException) {
                // The emulator owner died (parser fault) and closed the queue on its way out: stop
                // feeding it. Deliberately NOT rethrown: the session flow must stay live for its
                // other subscribers (share pump), and EOF still moves the session to Closed.
            } finally {
                commands.close()
            }
        }
        // Sole owner of the emulator: feed and resize run strictly in order. Publishing is
        // coalesced twice: a batch of immediately-available commands becomes one snapshot, and
        // while the stream keeps producing, publishes are further capped to one per
        // [PUBLISH_MIN_INTERVAL_MS] window (leading edge immediate, trailing edge guaranteed) —
        // see the constant's doc. When the queue is empty, behavior is unchanged (snapshot right
        // away), so interactive latency does not grow.
        scope.launch {
            try {
                runEmulatorOwner()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // A parser fault must not be invisible: without this, the pane silently stops
                // painting while the session still reports Open. There is no logging framework in
                // this codebase, so the trace goes to stderr; closing the session flips the state
                // to Closed, which the connection layer answers with auto-reconnect - a live
                // terminal again instead of a frozen one. Not rethrown: an unhandled sibling
                // failure under the SupervisorJob scope would reach the platform's fatal handler.
                // Printing the trace is safe against escape/secret injection as long as emulator
                // exceptions never interpolate input into their messages - today they don't
                // (numeric indices only); keep it that way.
                e.printStackTrace()
                try {
                    session.close()
                } catch (ce: CancellationException) {
                    throw ce // scope died while closing - not a close failure
                } catch (closeFailure: Exception) {
                    closeFailure.printStackTrace()
                }
            } finally {
                // The session is over (EOF, disconnect, or the pane closing with the scope): drop
                // the saved password. The connection controller drops its own copy on a clean exit
                // for the same reason, and a String cannot be zeroed — the least this can do is not
                // outlive the connection it belongs to.
                sudo?.revoke()
                commandQueue.closeAndDrain { recorder?.finish() }
            }
        }
        // Sole consumer of outbound bytes: guarantees FIFO write order to the PTY regardless of how
        // many coroutines call send/sendBytes. All sends go through [outbound].
        scope.launch {
            outbound.drainTo(onUserInputWrite = inputPublication::onUserInputWrite) { session.send(it) }
        }
    }
}

/**
 * What a synchronized pane is mirroring (see [TerminalScreenState.inputMirror]). The three stay
 * apart because the receiving pane has to replay each the way it would have arrived there: typed
 * input feeds its autocomplete, a paste gets that pane's own bracketed-paste wrapping, and the
 * answer to a sudo prompt is the pane's own saved password rather than the origin's bytes.
 */
enum class MirroredInput { Typed, Pasted, SudoAnswer }

/**
 * Process start mark: the default monotonic clock behind [TerminalScreenState]'s search refresh
 * throttle and its publish-rate cap.
 */
private val STARTED_AT = TimeSource.Monotonic.markNow()

/**
 * How many PTY chunks may sit unapplied between the session collector and the emulator before the
 * collector suspends. The suspension is the backpressure path: collector -> session's SharedFlow
 * (SUSPEND on full) -> channel flow emit -> the blocking socket read pauses -> the TCP window
 * closes and the server stops sending. Without a bound, `cat` of a large file piles chunks up
 * faster than the emulator parses them and the screen freezes, then jumps. ~8 KiB per chunk, so
 * the cap bounds this queue at ~512 KiB; the total client-side cushion before the socket read
 * actually stalls also includes the session flow's own 256-slot buffer upstream
 * (TerminalSession), ~2.5 MiB combined per session.
 */
internal const val FEED_BACKLOG_CHUNKS = 64

/**
 * Minimum interval between snapshot publishes while the stream keeps producing. A publish costs a
 * visible-grid copy, a policy compare, ~15 Compose state writes and a Main-dispatcher notification
 * per registered snapshotFlow — at 200-500 PTY chunks/s that is hundreds of publishes per second
 * for at most 60 visible frames. The first command after a quiet period always publishes
 * immediately (interactive echo latency is untouched); the cap only coalesces mid-stream.
 * 16ms aligns with the common 60Hz frame: Compose paints on the frame clock, so a faster cadence
 * only produces publishes whose work is thrown away between frames; the trailing-edge guarantee
 * bounds the added latency to one window in steady state (a command landing mid-window starts a
 * fresh drain budget, so one transitional interval can approach two windows — still bounded, not
 * compounding). This constant is also the fairness bound of
 * the emulator owner's drain (one window of parse work per publish, then a yield) — growing it
 * grows the worst-case scheduling delay of the outbound writer on a saturated pool with it.
 */
internal const val PUBLISH_MIN_INTERVAL_MS = 16L

/**
 * Longest a synchronized-output frame (mode 2026) holds the screen. An application that opens a
 * frame and dies, or a stream cut mid-frame, must not leave the terminal frozen; 150ms is past any
 * real redraw and short of what reads as a hang.
 */
internal const val SYNCHRONIZED_OUTPUT_TIMEOUT_MS = 150L

/** [TerminalScreenState]'s hold start while nothing is held. */
private const val NOT_HOLDING = -1L

/** Numpad Enter in application-keypad mode (DECKPAM), as `keypadSequence` in TerminalInput.kt sends it. */
private const val NUMPAD_ENTER_SS3 = "\u001bOM"

/**
 * Extract the last command and its output from flat screen [text] (rows joined by '\n', trailing
 * blanks trimmed — see [TerminalScreenState.output]). The bottom line is the current shell prompt;
 * the nearest line above it that starts with that same prompt string is where the last command was
 * entered, so everything from there down to (but not including) the current prompt is that command
 * plus its output. Returns `null` when no such boundary exists.
 *
 * Heuristic only — no shell cooperation (OSC 133) is assumed, and prompts vary. A very short prompt
 * (e.g. a bare "$") is rejected, since it would match unrelated lines and mis-slice the screen.
 */
/**
 * The [count] most recent command blocks of screen [text], oldest first — the context the assistant
 * panel attaches to a question. Same prompt heuristic as [lastCommandBlock], applied repeatedly:
 * every line that repeats the current prompt and has something typed after it starts a block, and a
 * block runs to the next such line. Returns fewer entries than asked when the screen holds fewer,
 * and an empty list when the prompt is unusable.
 */
internal fun lastCommandBlocks(text: String, count: Int): List<String> {
    if (count <= 0) return emptyList()
    val lines = text.split("\n")
    if (lines.size < 2) return emptyList()
    val prompt = lines.last()
    if (prompt.length < 3) return emptyList()
    // Walk up from the current prompt collecting command lines, newest first, then slice each block
    // from its command line down to the next one.
    val starts = mutableListOf<Int>()
    for (i in lines.size - 2 downTo 0) {
        val line = lines[i]
        // A command line repeats the prompt and has something typed after it.
        if (line.length > prompt.length && line.startsWith(prompt)) {
            starts += i
            if (starts.size == count) break
        }
    }
    val blocks = mutableListOf<String>()
    var end = lines.size - 1
    starts.forEach { start ->
        val block = lines.subList(start, end).joinToString("\n").trim()
        if (block.isNotEmpty()) blocks += block
        end = start
    }
    return blocks.reversed()
}

internal fun lastCommandBlock(text: String): String? = lastCommandBlocks(text, 1).firstOrNull()

/**
 * Snapshots compare by IDENTITY, not structurally: at a full scrollback with repetitive output
 * (`yes`, a spinner) a structural compare walked millions of equal cells per publish. The identity
 * contract is upheld at the source — [TerminalEmulator.lines] returns the cached instance while
 * nothing visible mutated and a new one after any cell, wrap-flag, scrollback or geometry change
 * (see [TerminalEmulator.contentVersion]). The wrap-flag subtlety that used to force a structural
 * compare (`ESC[K` dropping a wrap without any cell changing) is a version bump there too.
 */
private val SCREEN_SNAPSHOT_POLICY = object : SnapshotMutationPolicy<List<List<TermCell>>> {
    override fun equivalent(a: List<List<TermCell>>, b: List<List<TermCell>>): Boolean = a === b
}
