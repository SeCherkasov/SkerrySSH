package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.terminal.bracketedPasteWrap
import app.skerry.shared.terminal.encodeMouseReport
import app.skerry.shared.terminal.lineSelectionAt
import app.skerry.shared.terminal.wordSelectionAt
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
        val queued = commands.trySend(TerminalCommand.StartRecording(title, epochMillis(), cols, rows))
        // The queue is closed once the session's output ends: there is nothing left to record.
        if (queued.isFailure) return
        recording = true
        recordingTruncated = false
    }

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
     * Keyboard/IME user input: feeds the autocomplete engine (line and history tracking) and sends
     * to the PTY. Separate from [send]/[sendBytes], used for mouse/focus reports, paste, and
     * snippet output, which must not reach the engine or the tracked line would be corrupted.
     */
    fun typeInput(text: String) {
        inputVersion++
        // Server not echoing input (password entry / line-mode signaled by the transport): do not
        // track the line or write it to history, so a secret does not persist and surface as a
        // suggestion. SSH echo status is unavailable (always false), so a password prompt is also
        // detected heuristically from the current screen line ([atPasswordPrompt]).
        if (session.echoSuppressed || atPasswordPrompt()) {
            autocomplete.reset()
            if (suggestionTail != null) suggestionTail = null
            send(text)
            return
        }
        val committed = autocomplete.onUserInput(text.encodeToByteArray())
        refreshSuggestion()
        send(text)
        // Command was committed with Enter (and was echoed): persist the history snapshot for this host.
        if (committed != null) onHistoryChanged?.invoke(autocomplete.commandHistory.commands)
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
    fun paste(text: String) {
        if (text.isEmpty()) return
        inputVersion++
        send(bracketedPasteWrap(text, bracketedPaste))
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
 * [TerminalScreenState.atPasswordPrompt]). Covers typical sudo/ssh/passwd/su prompts.
 */
private val PASSWORD_PROMPT_HINTS = listOf(
    "password", "passphrase", "passcode", "verification code", "pin",
)
