package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.AutocompleteEngine
import app.skerry.shared.terminal.CommandHistory
import app.skerry.shared.terminal.highlight.CommandVocabulary
import app.skerry.shared.terminal.highlight.SessionVocabulary
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
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.terminal.bracketedPasteWrap
import app.skerry.shared.terminal.encodeMouseReport
import app.skerry.shared.terminal.lineSelectionAt
import app.skerry.shared.terminal.wordSelectionAt
import app.skerry.shared.terminal.wrapsToNextRow
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
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
    var screen: List<List<TermCell>> by mutableStateOf(emptyList(), SCREEN_SNAPSHOT_POLICY)
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
        // The echo of what was typed arrives here, and the ghost continues what this snapshot shows —
        // so this is where it is recomputed. Also clears it on entering a fullscreen TUI (vim/htop):
        // there is no "line" there.
        refreshSuggestion()
        title = emulator.title
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
            refreshSuggestion()
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
        refreshSuggestion(lineChanged = true)
        send(text)
        // Mirrored from here, not from typeInput: input held by the production guard must reach the
        // other panes only once it is confirmed (this runs again on confirm), never on the hold.
        if (mirror) inputMirror?.invoke(text, MirroredInput.Typed)
        // Command was committed with Enter (and was echoed): persist the history snapshot for this host.
        if (committed != null) {
            val commands = autocomplete.commandHistory.commands
            onHistoryChanged?.invoke(commands)
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

    // The hold/confirm/dismiss rules live in [ProductionGuardHold]; what belongs here is only what
    // is terminal-specific — which candidates a path offers, and how a held block is replayed.
    private val guard = ProductionGuardHold()

    // What can make the shell run the line it is holding: Enter in both forms, plus readline's
    // accept-line-and-down-history (Ctrl-O), which the mobile keybar reaches as ctrl + "/".
    private val runLineControls = charArrayOf('\r', '\n', '\u000F')

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
        if (text.none { it in runLineControls }) return false
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
     * snapshot, like [atPasswordPrompt]. [cursorRow] indexes that snapshot directly (the emulator
     * counts scrollback into it), so it must NOT be offset by the screen's start — doing that ran
     * off the end as soon as any history existed, and the guard then saw an empty line.
     */
    private fun screenLineToCursor(): String {
        val grid = screen
        if (grid.isEmpty() || rows <= 0) return ""
        val line = grid.getOrNull(cursorRow) ?: return ""
        return line.take(cursorCol.coerceIn(0, line.size)).joinToString("") { it.text }
    }

    /**
     * Whether the current cursor row looks like a password prompt (echo is usually off there).
     * Reads the published [screen] snapshot (UI thread, no race with the emulator) at [cursorRow],
     * which already addresses that snapshot including scrollback. A row is treated as a
     * prompt if it ends with ":" and contains one of the keyword hints, to avoid suppressing
     * history on plain text like `cat passwords.txt`. Heuristic: erring toward not saving a
     * command is safer than leaking a secret.
     */
    private fun atPasswordPrompt(): Boolean {
        val grid = screen
        if (grid.isEmpty() || rows <= 0) return false
        val line = grid.getOrNull(cursorRow) ?: return false
        val text = line.joinToString("") { it.text }.trim().lowercase()
        if (!text.endsWith(":")) return false
        return PASSWORD_PROMPT_HINTS.any { it in text }
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
        refreshSuggestion()
    }

    /**
     * Remove [command] from the autocomplete history (manual cleanup of typos/unwanted commands)
     * and persist the update. Adjusts the reverse-search index to stay in bounds.
     */
    fun forgetHistoryCommand(command: String) {
        if (!autocomplete.forget(command)) return
        reverseSearch.clampIndex()
        onHistoryChanged?.invoke(autocomplete.commandHistory.commands)
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
    private var echoPending = false

    // Tracked line length at the last refresh, to tell a local erase from anything else.
    private var trackedLength = 0

    /**
     * The longest prefix of the tracked line the screen confirms — the cursor row up to the cursor
     * ends with it, so it is exactly what a ghost drawn at the cursor continues. Empty when nothing
     * of the line is on screen: the echo has not started, the line wrapped onto the next row, or the
     * shell redrew it (Ctrl-W, its own Tab completion) — in all three there is nothing to continue.
     */
    private fun echoedLine(line: String): String {
        if (line.isEmpty()) return ""
        val onScreen = screenLineToCursor()
        // Compared in place: this runs on every published snapshot, and a substring per candidate
        // length would allocate through the whole line on each batch of output.
        var length = minOf(onScreen.length, line.length)
        while (length > 0 && !onScreen.regionMatches(onScreen.length - length, line, 0, length)) length--
        return line.substring(0, length)
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
        sendBytes(bytes)
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
            refreshSuggestion(lineChanged = true) // the paste moved the line; the screen has not seen it yet
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
}

/**
 * What a synchronized pane is mirroring (see [TerminalScreenState.inputMirror]). Typing and pasting
 * stay apart because the receiving pane has to replay each the way it would have arrived there:
 * typed input feeds its autocomplete, a paste gets that pane's own bracketed-paste wrapping.
 */
enum class MirroredInput { Typed, Pasted }

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
 * Whether two screen snapshots are the same as far as the UI is concerned — cell content **and** the
 * soft-wrap flags. Compose skips a state write whose new value is equivalent to the old one, and list
 * equality only compares cells: a row can drop its wrap without any cell changing (`ESC[K` over an
 * already-blank tail), and publishing that as "no change" would leave [TerminalScreen]'s link joining
 * reading wrap flags that no longer hold.
 */
internal fun sameScreen(a: List<List<TermCell>>, b: List<List<TermCell>>): Boolean =
    a == b && a.indices.all { a[it].wrapsToNextRow() == b[it].wrapsToNextRow() }

/** [sameScreen] as the equivalence Compose uses to decide whether publishing a snapshot is a no-op. */
private val SCREEN_SNAPSHOT_POLICY = object : SnapshotMutationPolicy<List<List<TermCell>>> {
    override fun equivalent(a: List<List<TermCell>>, b: List<List<TermCell>>): Boolean = sameScreen(a, b)
}
