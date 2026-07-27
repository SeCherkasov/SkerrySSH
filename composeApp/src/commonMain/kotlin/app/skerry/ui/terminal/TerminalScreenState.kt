package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.AutocompleteEngine
import app.skerry.shared.terminal.CommandHistory
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.DEFAULT_MAX_SCROLLBACK
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.SessionRecorder
import app.skerry.shared.terminal.epochMillis
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TerminalEmulator
import app.skerry.shared.terminal.TerminalMatch
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSearchError
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.terminal.bracketedPasteWrap
import app.skerry.shared.terminal.encodeMouseReport
import app.skerry.shared.terminal.lineSelectionAt
import app.skerry.shared.terminal.matchNearestTo
import app.skerry.shared.terminal.searchTerminal
import app.skerry.shared.terminal.wordSelectionAt
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
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
    // Monotonic milliseconds, injectable for tests. Only the search refresh throttle reads it, and
    // it must not step backwards (a wall clock would), or a refresh could be skipped for minutes.
    private val nowMillis: () -> Long = { STARTED_AT.elapsedNow().inWholeMilliseconds },
) {
    // OSC 52 requests to write to the system clipboard. extraBufferCapacity keeps tryEmit from the
    // owner coroutine from dropping when there's no subscriber yet; DROP_OLDEST on burst keeps the
    // latest entry (last-writer-wins), not a stale one.
    private val _clipboardCopies = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Text the application asks to place on the system clipboard (OSC 52). UI collects and writes it. */
    val clipboardCopies: SharedFlow<String> = _clipboardCopies

    private val emulator = TerminalEmulator(
        maxScrollback = scrollback,
        initialCursorShape = cursorShape,
        initialCursorBlink = cursorBlink,
        // Terminal responses (DSR/DA) go back to the PTY, otherwise apps polling cursor/attributes
        // hang. Called synchronously from feed() (owner coroutine): must only write to the PTY
        // (send -> session.send) and never start a new feed/resize, or the emulator's single-thread
        // contract breaks.
        respond = { reply -> send(reply) },
        // OSC 52 write is also called synchronously from feed(); publish to the flow, UI thread
        // writes to the system clipboard. Gated in the emulator by [clipboardWriteEnabled].
        onClipboardCopy = { text -> _clipboardCopies.tryEmit(text) },
        clipboardWriteEnabled = clipboardWriteEnabled,
    )

    /** Screen snapshot (rows top to bottom) for rendering. */
    var screen: List<List<TermCell>> by mutableStateOf(emptyList())
        private set

    /**
     * Monotonic snapshot publish counter, incremented on every feed/resize even if [screen] is
     * structurally unchanged. Auto-scroll-to-bottom must key off this, not [screen]: Compose
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

    /** Current grid size (live `cols x rows` from the emulator). */
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

    val state: StateFlow<TerminalState> get() = session.state

    // The emulator is single-threaded: feed and resize must not be called from different coroutines.
    // All interactions go through this command queue, drained by the single collector below, so
    // PTY output and resize stay serialized relative to each other.
    private val commands = Channel<TerminalCommand>(Channel.UNLIMITED)

    // Outbound byte queue to the PTY (input, mouse reports, DSR/DA responses). The single consumer
    // in init serializes writes, preserving order across sends from different coroutines. UNLIMITED
    // means trySend never blocks or drops (fire-and-forget).
    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)

    // Last size sent to the PTY: duplicates are suppressed to avoid spamming resize on relayout.
    // @Volatile because resize() can be called from different coroutines (LaunchedEffect/gestures).
    @Volatile
    private var lastRequestedSize: PtySize? = null

    init {
        // Sole collector of PTY output; forwards chunks into the command queue. Closes the queue
        // when output ends (EOF/session close), otherwise the owner loop below would hang forever
        // in `for (cmd in commands)`.
        scope.launch {
            try {
                session.output.collect { chunk -> commands.send(TerminalCommand.Feed(chunk)) }
            } finally {
                commands.close()
            }
        }
        // Sole owner of the emulator: feed and resize run strictly in order. Publishes one snapshot
        // per batch of immediately-available commands: under heavy output (build, cat) the PTY
        // delivers many chunks in a row, and publishSnapshot copies the whole scrollback, so doing
        // it per chunk is expensive (freezes/GC, especially on Android). When the queue is empty,
        // behavior is unchanged (snapshot right away), so interactive latency does not grow.
        scope.launch {
            try {
                for (cmd in commands) {
                    applyCommand(cmd)
                    while (true) {
                        val next = commands.tryReceive().getOrNull() ?: break
                        applyCommand(next)
                    }
                    publishSnapshot()
                }
            } finally {
                // Cancellation can leave a stop-recording queued with nobody to answer it; hand the
                // take over here rather than leave the exporting caller awaiting forever.
                while (true) {
                    val left = commands.tryReceive().getOrNull() ?: break
                    if (left is TerminalCommand.StopRecording) left.cast.complete(recorder?.finish())
                }
            }
        }
        // Sole consumer of outbound bytes: guarantees FIFO write order to the PTY regardless of how
        // many coroutines call send/sendBytes. All sends go through [outbound].
        scope.launch {
            for (bytes in outbound) session.send(bytes)
        }
    }

    /** Apply one command to the emulator (does not publish a snapshot; the caller batches that). */
    private suspend fun applyCommand(cmd: TerminalCommand) {
        when (cmd) {
            is TerminalCommand.Feed -> {
                recorder?.let {
                    it.record(cmd.chunk)
                    if (it.truncated && !recordingTruncated) recordingTruncated = true
                }
                emulator.feed(cmd.chunk)
            }
            is TerminalCommand.StartRecording -> {
                // Elapsed time comes off a monotonic source: a wall clock can step backwards (NTP,
                // suspend/resume) and take the event timeline with it. The epoch stamp is only the
                // header's "when was this recorded".
                val started = TimeSource.Monotonic.markNow()
                recorder = SessionRecorder(
                    columns = cmd.columns,
                    rows = cmd.rows,
                    startedAtEpochSeconds = cmd.startedAtMillis / 1000,
                    title = cmd.title,
                    now = { started.elapsedNow().inWholeMilliseconds },
                )
            }
            is TerminalCommand.StopRecording -> {
                cmd.cast.complete(recorder?.finish())
                recorder = null
            }
            is TerminalCommand.SetCursorDefault -> emulator.applyCursorDefault(cmd.shape, cmd.blink)
            is TerminalCommand.SetMaxScrollback -> emulator.applyMaxScrollback(cmd.lines)
            is TerminalCommand.SetClipboardWriteEnabled -> emulator.applyClipboardWrite(cmd.enabled)
            is TerminalCommand.Resize -> {
                // PTY is resized first, the emulator only on success: otherwise the grid would be
                // wider than the application knows and the tail of rows would stay undrawn. A PTY
                // resize failure must not kill this coroutine, or feed stops being processed and
                // the terminal freezes.
                try {
                    session.resize(cmd.size)
                    emulator.resize(cmd.size.cols, cmd.size.rows)
                } catch (e: CancellationException) {
                    throw e // do not swallow scope cancellation
                } catch (_: Exception) {
                    // only recoverable failures (e.g. PTY dropped); Error propagates
                }
            }
        }
    }

    /** Publish the emulator snapshot into Compose state (after feed/resize). */
    private fun publishSnapshot() {
        screen = emulator.lines // rows are already copied into immutable form inside the getter
        cols = emulator.cols
        rows = emulator.rows
        cursorRow = emulator.cursorRow
        cursorCol = emulator.cursorCol
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
        altScreen = emulator.altScreen
        // Entering a fullscreen TUI (vim/htop) clears the autocomplete suggestion — no "line" there.
        if (altScreen && suggestionTail != null) suggestionTail = null
        title = emulator.title
        palette = emulator.paletteSnapshot()
        // The buffer changed under an open search panel: rebuild the match list (throttled — see
        // refreshSearch) so the counter and navigation follow the output, keeping the user on the
        // hit they were reading.
        if (searchQuery != null) refreshSearch(keep = currentMatch, force = false)
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

    /**
     * Best-effort text of the last command and its output — for "explain this output" when nothing is
     * selected, so the AI sees the recent result rather than the whole screen (a long login banner
     * would otherwise drown it out). `null` when the command boundary can't be found; the caller then
     * falls back to the whole visible screen. See [lastCommandBlock] for the heuristic.
     */
    fun lastOutput(): String? = lastCommandBlock(output)

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
        val text = selectedText() ?: return null
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

    /** Tail of the current autocomplete suggestion (shown grayed after typed text) or `null`. */
    var suggestionTail: String? by mutableStateOf(null)
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
     * to the PTY. Separate from [send]/[sendBytes], used for mouse/focus reports, paste, and
     * snippet output, which must not reach the engine or the tracked line would be corrupted.
     */
    fun typeInput(text: String, guarded: Boolean = true, mirror: Boolean = true) {
        inputVersion++
        // Server not echoing input (password entry / line-mode signaled by the transport): do not
        // track the line or write it to history, so a secret does not persist and surface as a
        // suggestion. SSH echo status is unavailable (always false), so a password prompt is also
        // detected heuristically from the current screen line ([atPasswordPrompt]).
        // The production guard is skipped here for the same reason: what is being typed is a secret,
        // and parking it in a confirmation dialog would put it on screen in clear text.
        if (awaitingSecret) {
            autocomplete.reset()
            if (suggestionTail != null) suggestionTail = null
            send(text)
            // A secret is mirrored like anything else typed: entering one sudo password across
            // synchronized panes is the case people turn the toggle on for. Each pane decides on its
            // own screen whether to keep it out of history — this one just did.
            if (mirror) inputMirror?.invoke(text, MirroredInput.Typed)
            return
        }
        // guarded=false: the caller already asked (broadcast confirms once for the whole fan-out,
        // where a per-session hold would strand commands in tabs nobody is looking at).
        if (guarded && holdForProductionGuard(text)) return
        deliverTypedInput(text, mirror)
    }

    private fun deliverTypedInput(text: String, mirror: Boolean = true) {
        val committed = autocomplete.onUserInput(text.encodeToByteArray())
        refreshSuggestion()
        send(text)
        // Mirrored from here, not from typeInput: input held by the production guard must reach the
        // other panes only once it is confirmed (this runs again on confirm), never on the hold.
        if (mirror) inputMirror?.invoke(text, MirroredInput.Typed)
        // Command was committed with Enter (and was echoed): persist the history snapshot for this host.
        if (committed != null) onHistoryChanged?.invoke(autocomplete.commandHistory.commands)
    }

    // --- Production guard ---
    // On a host tagged #prod a risky command is confirmed before it reaches the PTY. Enabled by the
    // UI from the session's host profile (see [app.skerry.ui.host.isProdHostId]) and kept live, so
    // adding or removing the tag arms/disarms an open session.

    // The hold/confirm/dismiss rules live in [ProductionGuardHold]; what belongs here is only what
    // is terminal-specific — which candidates a path offers, and how a held block is replayed.
    private val guard = ProductionGuardHold()

    /**
     * What the production guard asks about in this session (host tag, root login, the
     * confirm-warnings setting). [ProductionGuardPolicy.Off] — no guard at all.
     */
    var guardPolicy: ProductionGuardPolicy
        get() = guard.policy
        set(value) { guard.policy = value }

    /** Command held by the guard, awaiting the user's confirmation; `null` when nothing is pending. */
    val pendingGuarded: GuardedCommand? get() = guard.pending

    /**
     * Holds [text] when it would run a risky command on a production session; `true` means nothing
     * was sent (see [ProductionGuardHold.hold] for when a held block is dropped instead).
     *
     * Only an input block containing Enter can run something, so that is the only thing held —
     * typing itself stays live (a half-typed line the user is still editing must keep echoing).
     * Alt-screen is exempt: inside vim/htop there is no shell line, and Enter is not "run this".
     *
     * The command is guessed from two sources, because the client never truly knows it: the locally
     * tracked line (what was typed here) and the screen line up to the cursor (which also covers a
     * command recalled with arrow-up, where nothing was typed at all).
     */
    private fun holdForProductionGuard(text: String): Boolean {
        if (altScreen) return false
        if (text.none { it == '\r' || it == '\n' }) return false
        return guard.hold(text, HeldInputSource.Typed) {
            // The first line continues whatever is already on the shell line; the rest of the block
            // stands on its own. A soft-keyboard delta or an IME clipboard insert arrives whole, so
            // a risky command can sit on any line of it, not just the first.
            val lines = ProductionGuard.candidatesOf(text)
            val typed = listOf(autocomplete.currentLine + lines.first()) + lines.drop(1)
            ProductionGuard.promptCandidates(screenLineToCursor()) + typed
        }
    }

    /**
     * Runs a ready-made command (snippet, palette, any caller that already has the full line) with
     * the guard in front of it. Unlike [typeInput] the command is known verbatim, so it is
     * classified as-is — nothing is guessed off the screen. Falls back to [sendUserInput] when the
     * session is not production or the command is harmless.
     */
    fun sendUserInputGuarded(text: String) {
        if (guard.hold(text, HeldInputSource.Command) { ProductionGuard.candidatesOf(text) }) return
        sendUserInput(text)
    }

    /** Run the held command: replays the original input exactly as the path it came from would. */
    fun confirmGuardedCommand() {
        val held = guard.take() ?: return
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
    fun dismissGuardedCommand() = guard.dismiss()

    /**
     * Visible cursor row up to the cursor column — the shell line as the user sees it, prompt
     * included ([ProductionGuard.promptCandidates] strips it). Reads the published [screen]
     * snapshot, like [atPasswordPrompt].
     */
    private fun screenLineToCursor(): String {
        val grid = screen
        if (grid.isEmpty() || rows <= 0) return ""
        val line = grid.getOrNull(grid.size - rows + cursorRow) ?: return ""
        return line.take(cursorCol.coerceIn(0, line.size)).joinToString("") { it.text }
    }

    /**
     * Whether the current cursor row looks like a password prompt (echo is usually off there).
     * Reads the published [screen] snapshot (UI thread, no race with the emulator): the visible
     * grid is the last [rows] rows, cursor row is [cursorRow] within it. A row is treated as a
     * prompt if it ends with ":" and contains one of the keyword hints, to avoid suppressing
     * history on plain text like `cat passwords.txt`. Heuristic: erring toward not saving a
     * command is safer than leaking a secret.
     */
    private fun atPasswordPrompt(): Boolean {
        val grid = screen
        if (grid.isEmpty() || rows <= 0) return false
        val line = grid.getOrNull(grid.size - rows + cursorRow) ?: return false
        val text = line.joinToString("") { it.text }.trim().lowercase()
        if (!text.endsWith(":")) return false
        return PASSWORD_PROMPT_HINTS.any { it in text }
    }

    /**
     * Accept the current autocomplete suggestion: sends its tail to the PTY (the shell echoes it).
     * Returns `true` if there was something to accept, else `false`.
     */
    fun acceptSuggestion(): Boolean {
        if (altScreen) return false
        val tail = autocomplete.acceptSuggestion() ?: return false
        refreshSuggestion()
        sendBytes(tail)
        return true
    }

    /**
     * Cycle the ghost suggestion to the next alternative (Shift+Tab). No-op in alt-screen. Does not
     * touch the PTY line; only the proposed tail changes until accepted.
     */
    fun cycleSuggestion() {
        if (altScreen) return
        autocomplete.cycleSuggestion()
        suggestionTail = autocomplete.suggestionTail()
    }

    // --- Reverse search (Ctrl-R): overlay state lives here so desktop keys and the mobile
    // panel/IME drive it uniformly, and the render overlay reads a single source. ---

    /** Current reverse-search query, or `null` if the overlay is closed. */
    var reverseSearchQuery: String? by mutableStateOf(null)
        private set

    /** Index of the selected match in [reverseSearchResults]. */
    var reverseSearchIndex: Int by mutableStateOf(0)
        private set

    /** Matches for the current query (newest to oldest), or empty if the overlay is closed. */
    val reverseSearchResults: List<String>
        get() = reverseSearchQuery?.let { autocomplete.commandHistory.search(it) } ?: emptyList()

    /** Selected match (at [reverseSearchIndex]) or `null`. */
    val reverseSearchSelection: String?
        get() {
            val r = reverseSearchResults
            return if (r.isEmpty()) null else r[reverseSearchIndex.mod(r.size)]
        }

    /** Open reverse search (empty query). No-op in alt-screen (no line history there). */
    fun openReverseSearch() {
        if (altScreen) return
        // Only one overlay can own the keyboard: the find bar's field would keep focus and leave
        // this overlay visible but deaf to its own keys.
        closeSearch()
        reverseSearchQuery = ""
        reverseSearchIndex = 0
    }

    /** Close the reverse-search overlay without inserting anything. */
    fun closeReverseSearch() {
        reverseSearchQuery = null
        reverseSearchIndex = 0
    }

    /** Append [text] to the reverse-search query (resets selection to the first match). */
    fun reverseSearchAppend(text: String) {
        val q = reverseSearchQuery ?: return
        reverseSearchQuery = q + text
        reverseSearchIndex = 0
    }

    /** Remove the last character of the reverse-search query. */
    fun reverseSearchBackspace() {
        val q = reverseSearchQuery ?: return
        reverseSearchQuery = q.dropLast(1)
        reverseSearchIndex = 0
    }

    /** Move to the next (older) match. */
    fun reverseSearchNext() {
        val n = reverseSearchResults.size
        if (n > 0) reverseSearchIndex = (reverseSearchIndex + 1) % n
    }

    /** Move to the previous (newer) match. */
    fun reverseSearchPrev() {
        val n = reverseSearchResults.size
        if (n > 0) reverseSearchIndex = (reverseSearchIndex - 1 + n) % n
    }

    /** Accept the selected match (insert via [applyHistoryCommand]) and close the overlay. */
    fun reverseSearchAccept() {
        reverseSearchSelection?.let { applyHistoryCommand(it) }
        closeReverseSearch()
    }

    /**
     * Remove [command] from the autocomplete history (manual cleanup of typos/unwanted commands)
     * and persist the update. Adjusts the reverse-search index to stay in bounds.
     */
    fun forgetHistoryCommand(command: String) {
        if (!autocomplete.forget(command)) return
        val n = reverseSearchResults.size
        if (n == 0) reverseSearchIndex = 0 else if (reverseSearchIndex >= n) reverseSearchIndex = n - 1
        onHistoryChanged?.invoke(autocomplete.commandHistory.commands)
        refreshSuggestion()
    }

    /** Remove the currently selected reverse-search match from history; overlay stays open. */
    fun reverseSearchDeleteSelected() {
        reverseSearchSelection?.let { forgetHistoryCommand(it) }
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

    private fun refreshSuggestion() {
        suggestionTail = if (altScreen) null else autocomplete.suggestionTail()
    }

    // --- Output search (find in scrollback): the panel's whole state lives here so desktop keys and
    // the mobile panel drive it uniformly and the render overlay reads a single source. It searches
    // whatever [screen] holds — in alt-screen (vim/less/htop) that is the application's own frame,
    // which has no scrollback, so the search follows what is on screen. ---

    /** Current search query, or `null` if the panel is closed. */
    var searchQuery: String? by mutableStateOf(null)
        private set

    /** Whether the search respects letter case (panel's `Aa` toggle). */
    var searchCaseSensitive: Boolean by mutableStateOf(false)
        private set

    /** Whether the query is a regular expression rather than a literal (panel's `.*` toggle). */
    var searchRegex: Boolean by mutableStateOf(false)
        private set

    /** Matches in the current buffer, top to bottom. Empty while the panel is closed. */
    var searchMatches: List<TerminalMatch> by mutableStateOf(emptyList())
        private set

    /** Index of the selected match in [searchMatches], or `-1` when there is nothing selected. */
    var searchIndex: Int by mutableStateOf(-1)
        private set

    /** Why the query yielded nothing usable (bad or too costly regex), or `null`. */
    var searchError: TerminalSearchError? by mutableStateOf(null)
        private set

    /** Whether the match list hit its cap and more matches exist in the buffer. */
    var searchTruncated: Boolean by mutableStateOf(false)
        private set

    /** The selected match (render scrolls to it and paints it as the current hit), or `null`. */
    val currentMatch: TerminalMatch?
        get() = searchMatches.getOrNull(searchIndex)

    // Query of the last search, kept across closing so reopening the panel resumes it (as editors do).
    private var lastSearchQuery: String = ""

    // Buffer row the selection is measured from when the query changes: the bottom of what the user
    // was looking at, so an incremental search lands on the nearest hit above rather than at the top
    // of a long scrollback.
    private var searchAnchorRow: Int = 0

    // When the match list was last rebuilt, for the snapshot-driven throttle in [refreshSearch].
    private var lastSearchRefreshAt: Long = Long.MIN_VALUE / 2

    // The running search. A newer one cancels it: only the latest query's result may be published,
    // and an abandoned pass stops scanning instead of burning a core to completion.
    private var searchJob: Job? = null

    // Steps requested by next/previous that no published list has applied yet. The pass runs off
    // this coroutine, so a press is banked here and applied when its result lands — two quick
    // presses move two matches, not one.
    private var pendingSearchStep: Int = 0

    /**
     * Bumped by every user action that deliberately moves the selection (open, new query, toggle,
     * next/previous). The viewport follows *this*, not the selected match itself: while scrollback
     * evicts rows under streaming output, a match's row index shifts without the user asking for
     * anything, and scrolling to it would yank them off the line they are reading.
     */
    var searchNavVersion: Int by mutableStateOf(0)
        private set

    /**
     * Open the search panel, restoring the previous query. [anchorRow] is the buffer row at the
     * viewport bottom (kept current by [TerminalScreen] through [setSearchAnchorRow]); the first
     * selected match is the last one at or above it.
     */
    fun openSearch(anchorRow: Int = searchAnchorRow) {
        // See [openReverseSearch]: the two overlays cannot both hold the keyboard.
        closeReverseSearch()
        searchAnchorRow = anchorRow
        searchQuery = lastSearchQuery
        searchNavVersion++
        refreshSearch(keep = null)
    }

    /**
     * Report the buffer row currently at the bottom of the viewport. The render layer owns the
     * scroll position, so it feeds the anchor that an incremental search re-selects around.
     */
    fun setSearchAnchorRow(row: Int) {
        searchAnchorRow = row
    }

    /** Close the search panel and drop its matches (the highlight goes with them). */
    fun closeSearch() {
        searchJob?.cancel()
        searchJob = null
        pendingSearchStep = 0
        searchQuery = null
        searchMatches = emptyList()
        searchIndex = -1
        searchError = null
        searchTruncated = false
    }

    /** Replace the query and re-run the search (incremental: selection re-anchors to the viewport). */
    fun updateSearchQuery(text: String) {
        if (searchQuery == null) return
        // A pasted novel is not a search term; the cap keeps pattern compilation bounded too.
        val query = if (text.length <= MAX_SEARCH_QUERY_LENGTH) text else text.take(MAX_SEARCH_QUERY_LENGTH)
        lastSearchQuery = query
        searchQuery = query
        pendingSearchStep = 0
        searchNavVersion++
        refreshSearch(keep = null)
    }

    /** Toggle case sensitivity, keeping the selected match if it survives. */
    fun applySearchCase(enabled: Boolean) {
        if (searchCaseSensitive == enabled) return
        searchCaseSensitive = enabled
        searchNavVersion++
        refreshSearch(keep = currentMatch)
    }

    /** Switch between literal and regex matching, keeping the selected match if it survives. */
    fun applySearchRegex(enabled: Boolean) {
        if (searchRegex == enabled) return
        searchRegex = enabled
        searchNavVersion++
        refreshSearch(keep = currentMatch)
    }

    /** Select the next (lower) match, wrapping around. No-op without matches. */
    fun searchNext() {
        stepSearch(+1)
    }

    /** Select the previous (higher) match, wrapping around. No-op without matches. */
    fun searchPrev() {
        stepSearch(-1)
    }

    /**
     * Move the selection by [delta] matches. The step is banked ([pendingSearchStep]) and applied
     * to the freshly published list rather than to the current one: navigation must walk the buffer
     * as it is now, not as it was when the list was last rebuilt (up to
     * [SEARCH_REFRESH_INTERVAL_MS] ago under streaming output).
     */
    private fun stepSearch(delta: Int) {
        if (searchQuery.isNullOrEmpty()) return
        pendingSearchStep += delta
        searchNavVersion++
        refreshSearch(keep = currentMatch)
    }

    /**
     * Re-run the search over the current buffer. [keep] is the match to stay on if it is still
     * there (output arriving, a toggle flipped); otherwise the selection re-anchors to the viewport
     * ([searchAnchorRow]).
     *
     * The pass runs in its own coroutine and only the latest one publishes: a full buffer walk
     * takes tens of milliseconds, and doing it inline would either block the UI thread (a keystroke
     * in the field) or the coroutine that feeds the emulator (a published snapshot), which the user
     * sees as a terminal that stopped updating.
     *
     * Snapshot-driven refreshes are additionally throttled to [SEARCH_REFRESH_INTERVAL_MS] ([force]
     * `false`). Nothing visible lags behind — the highlight is computed per frame over the visible
     * rows by [TerminalScreen] — only the counter and the navigation list.
     */
    private fun refreshSearch(keep: TerminalMatch?, force: Boolean = true) {
        val query = searchQuery
        if (query.isNullOrEmpty()) {
            searchJob?.cancel()
            searchJob = null
            searchMatches = emptyList()
            searchIndex = -1
            searchError = null
            searchTruncated = false
            return
        }
        val now = nowMillis()
        if (!force && now - lastSearchRefreshAt < SEARCH_REFRESH_INTERVAL_MS) return
        lastSearchRefreshAt = now
        // Everything the pass depends on is captured up front: it runs off this coroutine, while
        // the fields it reads keep changing.
        val buffer = screen
        val caseSensitive = searchCaseSensitive
        val useRegex = searchRegex
        val anchorRow = keep?.row ?: searchAnchorRow
        searchJob?.cancel()
        searchJob = scope.launch {
            val result = searchTerminal(
                screen = buffer,
                query = query,
                caseSensitive = caseSensitive,
                regex = useRegex,
                cancelled = { !isActive },
            )
            if (!isActive) return@launch
            // One atomic publish: readers must never see a new match list against an old counter
            // or a selection index from another query.
            Snapshot.withMutableSnapshot {
                // A newer search took over while this one ran — its result is the one that counts.
                if (searchQuery != query || searchCaseSensitive != caseSensitive || searchRegex != useRegex) {
                    return@withMutableSnapshot
                }
                searchMatches = result.matches
                searchError = result.error
                searchTruncated = result.truncated
                searchIndex = selectMatch(result.matches, keep, anchorRow)
            }
        }
    }

    /**
     * Index to select in a freshly built [matches] list: the same hit if it is still there, else the
     * nearest one to [anchorRow] — then any banked next/previous steps ([pendingSearchStep]).
     */
    private fun selectMatch(matches: List<TerminalMatch>, keep: TerminalMatch?, anchorRow: Int): Int {
        if (matches.isEmpty()) {
            pendingSearchStep = 0
            return -1
        }
        val kept = keep?.let { matches.indexOf(it) } ?: -1
        val base = if (kept >= 0) kept else matchNearestTo(matches, anchorRow)
        val stepped = if (pendingSearchStep == 0) base else (base + pendingSearchStep).mod(matches.size)
        pendingSearchStep = 0
        return stepped
    }

    /** Send typed text to the PTY (fire-and-forget via the [outbound] queue, FIFO order). */
    fun send(text: String) {
        outbound.trySend(text.encodeToByteArray())
    }

    /**
     * [send] for user-pressed input that must not feed autocomplete: keybar control sequences,
     * snippet output, an AI-confirmed command. Bumps [inputVersion] so the viewport snaps back to
     * the live screen like [typeInput] — unlike plain [send], whose programmatic traffic
     * (mouse/DSR/focus reports) must never yank the viewport.
     */
    fun sendUserInput(text: String) {
        inputVersion++
        send(text)
    }

    /**
     * Send raw bytes to the PTY (fire-and-forget). Used for mouse reports: legacy encoding bytes
     * can exceed 0x7f and must not be run through UTF-8 like [send] does.
     */
    fun sendBytes(bytes: ByteArray) {
        outbound.trySend(bytes)
    }

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
        val bytes = encodeMouseReport(
            mouseTracking, mouseSgr, button, type, pos.col, pos.row, shift, alt, ctrl,
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
        // A paste carrying a newline runs the moment it lands — on a production session it goes
        // through the same confirmation as a typed command. A paste without one only fills the
        // shell line, and the Enter that would run it is guarded separately ([typeInput]).
        // The password-prompt exemption is [typeInput]'s, for the same reason: a manager pastes the
        // secret with a trailing newline, and holding it would print it in the dialog.
        // A middle-click paste arrives through a raw pointer handler the modal scrim never sees, so
        // this is the only place that can stop one while a confirmation is open.
        if (guardPolicy.production && !awaitingSecret &&
            text.any { it == '\n' || it == '\r' }
        ) {
            if (guard.hold(text, HeldInputSource.Paste) { ProductionGuard.candidatesOf(text) }) return
        }
        deliverPaste(text, mirror)
    }

    private fun deliverPaste(text: String, mirror: Boolean = true) {
        inputVersion++
        // A paste is tracked like typing: without this the Enter after a no-newline paste has
        // nothing to classify (the tracked line is empty and the host's echo may not have arrived
        // yet), and the guard would miss a pasted command. The engine also records the lines a
        // multi-line paste commits, same as if they had been typed.
        if (!awaitingSecret) {
            autocomplete.onUserInput(text.encodeToByteArray())
            refreshSuggestion()
        }
        send(bracketedPasteWrap(text, bracketedPaste))
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
}

/**
 * What a synchronized pane is mirroring (see [TerminalScreenState.inputMirror]). Typing and pasting
 * stay apart because the receiving pane has to replay each the way it would have arrived there:
 * typed input feeds its autocomplete, a paste gets that pane's own bracketed-paste wrapping.
 */
enum class MirroredInput { Typed, Pasted }

/**
 * How often a published snapshot may rebuild the search match list. Long enough that streaming
 * output cannot stall the emulator's coroutine with full-buffer passes, short enough that the
 * counter never looks frozen. The on-screen highlight does not wait for this (see [TerminalScreen]).
 */
const val SEARCH_REFRESH_INTERVAL_MS = 300L

/** Longest accepted search query: past this it is not a search term but a paste accident. */
const val MAX_SEARCH_QUERY_LENGTH = 512

/** Process start mark: the default monotonic clock behind [TerminalScreenState]'s refresh throttle. */
private val STARTED_AT = TimeSource.Monotonic.markNow()

/** Command to the sole emulator owner; the queue preserves feed/resize ordering. */
private sealed interface TerminalCommand {
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
}

/**
 * Prompt-line keywords that mark input as secret and exempt it from history (see
 * [TerminalScreenState.atPasswordPrompt]). Covers typical sudo/ssh/passwd/su prompts and the common
 * MFA wordings: with synchronized panes a missed prompt mirrors the secret into every other pane of
 * the tab, so the list errs wide — a false match only keeps an ordinary command out of history.
 */
private val PASSWORD_PROMPT_HINTS = listOf(
    "password", "passphrase", "passcode", "verification code", "pin",
    "otp", "one-time", "token", "2fa", "mfa", "authenticator", "challenge",
)

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
internal fun lastCommandBlock(text: String): String? {
    val lines = text.split("\n")
    if (lines.size < 2) return null
    val prompt = lines.last()
    if (prompt.length < 3) return null
    for (i in lines.size - 2 downTo 0) {
        val line = lines[i]
        // A command line repeats the prompt and has something typed after it.
        if (line.length > prompt.length && line.startsWith(prompt)) {
            return lines.subList(i, lines.size - 1).joinToString("\n").trim().ifBlank { null }
        }
    }
    return null
}
