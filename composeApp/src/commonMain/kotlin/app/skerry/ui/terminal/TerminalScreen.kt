package app.skerry.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.CellWidth
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.UnderlineStyle
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.TerminalState
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.terminal_reverse_search_no_matches
import app.skerry.ui.generated.resources.terminal_reverse_search_prompt
import app.skerry.ui.theme.SkerryColors

/** Max gap (ms) between clicks to count as double/triple click. */
private const val DOUBLE_CLICK_MS = 350

/** Cursor blink half-period (ms), matching xterm's ~530ms phase. */
private const val CURSOR_BLINK_MS = 530L

private const val PADDING_DP = 14
// Number of history matches shown at once in the reverse-search (Ctrl-R) overlay.
private const val REVERSE_SEARCH_ROWS = 6

/** Radius of the touch selection handle's dot, and its finger hit-test radius. */
private const val HANDLE_RADIUS_DP = 7
private const val HANDLE_TOUCH_RADIUS_DP = 22

/**
 * Interactive terminal: renders [TerminalScreenState.screen] (cell grid with color/weight from
 * [app.skerry.shared.terminal.TerminalEmulator]) and a block cursor at the cursor position. This is
 * the input focus target; keystrokes go to the PTY per-character ([mapTerminalKey]) and the shell
 * draws its own echo. There is no command line under the terminal; the bottom row is reserved for
 * the AI assistant.
 *
 * Selection: on mouse, drag directly extends a linear range ([TerminalSelection]) over the grid
 * (single click clears selection and returns focus); on touch, plain drag scrolls and selection
 * starts on long-press. The range is highlighted with translucent cyan. Copy via `Ctrl+Shift+C`
 * (desktop) or the system text toolbar's "Copy" menu that appears after touch selection
 * ([LocalTextToolbar]). Typing clears both selection and menu.
 *
 * [imeInput] enables the mobile input path: the soft keyboard does not send key events to
 * [onPreviewKeyEvent], so input is captured from a hidden `BasicTextField` ([imeDeltaToPty]).
 * Desktop keeps this `false`, relying on the physical keyboard via [mapTerminalKey].
 *
 * [imeTransform] (IME path only) post-processes a non-empty [imeDeltaToPty] result before sending —
 * the mobile key panel routes it through sticky-ctrl ([app.skerry.ui.mobile.applyStickyCtrl]) so
 * Ctrl+<letter> works from the soft keyboard too, not just from panel keys.
 */
@Composable
fun TerminalScreen(
    state: TerminalScreenState,
    modifier: Modifier = Modifier,
    imeInput: Boolean = false,
    imeTransform: ((String) -> String)? = null,
) {
    // Terminal font/size from Appearance settings ([LocalTerminalAppearance]); defaults to Hack 13px
    // where no provider is set (mobile target/preview/connection screen). Ligatures always disabled
    // ([NO_LIGATURES]) so `->`/`=>`/`!=` never merge regardless of font.
    val appearance = LocalTerminalAppearance.current
    // Terminal color theme (Appearance settings): background/text/ANSI/cursor accent. Read live by
    // Box background, textStyle, cellBgColor, cursor, and selection below.
    val termTheme = LocalTerminalTheme.current
    val cursorBg = termTheme.cursor
    val cursorFg = termTheme.cursorText
    val selectionBg = termTheme.selection
    val handleColor = termTheme.cursor
    val mono = rememberTerminalFontFamily(appearance.font)
    // Keyed on appearance itself (@Immutable data class, structural equality): changes exactly when
    // font/size changes. FontFamily is not a reliable key — equality of two built instances depends
    // on compose-resources Font.equals, so reference-equality would invalidate textStyle/metrics on
    // every recomposition. mono is captured by the lambda and stays consistent with appearance.font.
    val textStyle = remember(appearance, termTheme) {
        TextStyle(
            fontFamily = mono,
            fontFeatureSettings = NO_LIGATURES,
            fontSize = appearance.fontSizeSp.sp,
            lineHeight = (appearance.fontSizeSp * appearance.lineHeight).sp,
            // Letter spacing is part of advance: cellWidth below is measured with this same style, so
            // the grid (glyphs/background/cursor/mouse) stays consistent for any value.
            letterSpacing = appearance.letterSpacingSp.sp,
            color = termTheme.foreground,
        )
    }
    val sessionState by state.state.collectAsState()
    val closed = sessionState is TerminalState.Closed
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    // Hidden IME field (touch input): holds focus/keyboard, always reset to the anchor.
    val imeFocusRequester = remember { FocusRequester() }
    val imeBaseline = remember { TextFieldValue(ANCHOR, selection = TextRange(ANCHOR.length)) }
    var imeValue by remember { mutableStateOf(imeBaseline) }
    // System clipboard via the suspend API ([androidx.compose.ui.platform.Clipboard]); reads/writes
    // go through clipboardScope (called fire-and-forget from non-suspend key/mouse handlers).
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val textToolbar = LocalTextToolbar.current
    val uriHandler = LocalUriHandler.current
    // Soft keyboard controller: shown explicitly on touch since requestFocus() on an already-focused
    // hidden field is a no-op (focus remains after hiding, so the keyboard would not reappear).
    val keyboard = LocalSoftwareKeyboardController.current
    var layoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Mouse click counting for double (word) / triple (line) selection: tracks time and position of
    // the previous click; a repeat in the same cell within the threshold increments the count.
    // Keyed on state so the counter resets on tab switch; otherwise the first click on a new tab at
    // the same position within the threshold would be miscounted as a double/triple click.
    var clickCount by remember(state) { mutableStateOf(0) }
    var lastClickMark by remember(state) { mutableStateOf<TimeMark?>(null) }
    var lastClickPos by remember(state) { mutableStateOf<TerminalPos?>(null) }

    // Monospace cell size in pixels is the single source of truth for geometry: glyphs, background,
    // selection, cursor, mouse, and handles are all derived from it via col*cellWidth /
    // row*cellHeight, so everything stays consistent across fonts and system scale.
    val density = LocalDensity.current
    // A full TUI screen redraws hundreds of distinct glyph runs per frame; the default layout cache
    // (8 entries) thrashes and re-lays-out every run every frame. Sized to hold a busy screen's runs.
    // Color is passed at draw time (see drawGlyphText), so cache hits stay theme-safe.
    val measurer = rememberTextMeasurer(cacheSize = 1024)
    val metrics = remember(textStyle, density) {
        // cellWidth is the font's real advance, which drawText uses to lay out ASCII runs. Measured on
        // a long string and divided by its length: size.width is an integer (rounds to ~0.5px), which
        // at 10 chars gave error up to ~0.05px/char and drifted the ASCII run off the cw-grid by ~1
        // cell near the row's right edge (highlight/cursor/mouse use the grid, text uses advance). At
        // 200 chars the error is negligible and the grid matches drawText's layout.
        val sampleLen = 200
        val sample = measurer.measure(AnnotatedString("M".repeat(sampleLen)), textStyle)
        TerminalMetrics(
            cellWidth = sample.size.width / sampleLen.toFloat(),
            // cellHeight is rounded to a whole pixel: rows tile edge-to-edge (top = r*cellHeight), and a
            // fractional height (e.g. line-height multiplier 1.38 → 13×1.38 = 17.94px, or fractional
            // display scale) puts adjacent background rects' borders on fractional pixels — Skia
            // antialiases the seam, showing horizontal banding every row on solid backgrounds (e.g. mc
            // panels). Integer height removes the seam. Width cannot be rounded the same way (its
            // fractional advance is intentional, see above), but height can: each row's text is drawn
            // independent of its top, so no drift accumulates.
            cellHeight = with(density) { textStyle.lineHeight.toPx() }.roundToInt().toFloat(),
        )
    }
    val handleRadiusPx = with(density) { HANDLE_RADIUS_DP.dp.toPx() }
    val handleTouchRadiusPx = with(density) { HANDLE_TOUCH_RADIUS_DP.dp.toPx() }

    // An active desktop session grabs focus immediately (physical keyboard, no click needed to type).
    // On touch ([imeInput]) focus is not requested automatically: the hidden IME field would raise the
    // soft keyboard right on connect, pushing the layout/terminal up. The keyboard appears on user tap
    // (gesture handler below, the usual mobile SSH client behavior); the special-key panel works without it (sends to
    // the PTY directly).
    LaunchedEffect(state) { if (!closed && !imeInput) focusRequester.requestFocus() }

    // Autoscroll to bottom on new output — but only when the user was already at the bottom
    // (sticky bottom, like a real terminal): scrolling up to read history must survive streaming
    // output instead of being yanked back down on every chunk. Typing/pasting (inputVersion) always
    // snaps back to the live screen (xterm's scroll-on-keypress). Watches both snapshotVersion and
    // scroll.maxValue: layout recomputes content height in the placement phase, after the snapshot,
    // so reading maxValue at snapshot time would be stale. This matters especially on `clear`, where
    // height changes sharply: scrolling by the old maxValue lands past the new bottom (stale text
    // still visible) or into empty space. snapshotFlow re-emits once the value is recomputed, so
    // this always lands on the actual bottom (or top, if content shrank).
    LaunchedEffect(state, metrics) {
        // "At the bottom" tolerance: a couple of rows, absorbing the sub-row slack below the grid.
        val slackPx = (2 * metrics.cellHeight).roundToInt()
        var lastMax = scroll.maxValue
        var lastInput = state.inputVersion
        snapshotFlow { Triple(state.snapshotVersion, state.inputVersion, scroll.maxValue) }
            .collect { (_, input, max) ->
                val typed = input != lastInput
                lastInput = input
                val stick = typed || shouldStickToBottom(scroll.value, lastMax, slackPx)
                lastMax = max
                if (stick) scroll.scrollTo(max)
            }
    }

    // OSC 52: the app (tmux/vim) requests writing text to the system clipboard; done on the UI thread.
    // (The emulator does not forward server clipboard-read requests, so no user clipboard leak.)
    LaunchedEffect(state) {
        // try/catch per write: one failed copy (clipboard unavailable) must not kill collect, or OSC
        // 52 would stop working for the rest of the session. Coroutine cancellation is rethrown.
        state.clipboardCopies.collect {
            try {
                // On Wayland, write via wl-copy (same buffer read on paste); otherwise via Compose.
                if (!withContext(Dispatchers.Default) { writeSystemClipboardDirect(it) }) {
                    clipboard.setClipEntry(plainTextClipEntry(it))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Clipboard unavailable/busy: drop the copy silently, collect keeps running.
            }
        }
    }

    // Viewport size in cells is pushed to the PTY/emulator on first layout and on window resize;
    // without this the grid stays at the default 80x24 and wide output wraps. state.resize dedupes on
    // its own. Measured on the outer Box (viewport), not the scrollable Text, whose size is the full
    // content height.
    val paddingPx = with(density) { PADDING_DP.dp.toPx() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    // Glyphs/cursor stay hidden until the grid has been fitted to the viewport at least once: otherwise
    // the shell's first output would land on the default 80x24 and then "re-flow" on resize — a visible
    // jump on open. Until then only the terminal background is visible, then the final layout appears.
    var sized by remember(state) { mutableStateOf(false) }
    // state MUST be a key: switching tabs only changes state (a new TerminalScreenState for the active
    // session), while viewportSize/metrics/paddingPx stay the same. sized resets to false (remember(state)
    // above), and without the state key this effect wouldn't restart — resize wouldn't fire, sized would
    // stay false forever, and the grid/cursor wouldn't render (output "disappears" when switching tabs).
    LaunchedEffect(state, viewportSize, metrics, paddingPx) {
        if (viewportSize.width == 0 || viewportSize.height == 0) return@LaunchedEffect
        // Resize debounce: during the soft-keyboard animation (adjustResize) the viewport changes every
        // frame; without a delay the PTY would resize on every intermediate size, re-flowing the grid and
        // making the text "flicker/jump and settle back". A new resize restarts the effect and cancels the
        // pending one, so this fires once the size has settled. The very first resize (sized=false, terminal
        // opening) happens instantly, without a delay.
        if (sized) delay(150)
        state.resize(gridSizeFor(viewportSize.width.toFloat(), viewportSize.height.toFloat(), paddingPx, metrics))
        sized = true
    }

    // Cursor blink phase: with blink on, toggle a boolean once per half-period; otherwise the cursor
    // stays solid. The cursor is drawn as an overlay (see below), so blinking repaints only a small
    // Canvas, not the whole screen text.
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(state.cursorBlink, state.cursorVisible, closed) {
        if (!state.cursorBlink || !state.cursorVisible || closed) {
            blinkOn = true
            return@LaunchedEffect
        }
        while (true) {
            blinkOn = true
            delay(CURSOR_BLINK_MS)
            blinkOn = false
            delay(CURSOR_BLINK_MS)
        }
    }
    val cursorVisibleNow = sized && !closed && state.cursorVisible && blinkOn

    // A single state snapshot per recomposition: both the text overlay and the cursor overlay read the
    // same screen/cursor — otherwise the snapshot could diverge between the two draw passes (cursor at a
    // new position on the old grid). Compose repaints both on the next publish (snapshotVersion).
    val screen = state.screen
    val cursorRow = state.cursorRow
    val cursorCol = state.cursorCol

    // Bottom scroll slack: a grid of rows rows is shorter than the viewport by the remainder of its
    // height divided by a row (floor in gridSizeFor). Without compensation, scrolling down lets the last
    // scrollback row peek into this gap from the top — after `clear`, the very command row that should
    // scroll up. Add an empty gap below the content: then maxValue == scrollback height and the live grid
    // sticks to the viewport top (history hides upward, like a real terminal).
    val bottomSlack = with(density) {
        (viewportSize.height - 2 * paddingPx - state.rows * metrics.cellHeight).coerceAtLeast(0f).toDp()
    }

    // The scrollable content height is set explicitly in pixels (row count × cellHeight), not by an
    // invisible Text's row count: the actual Text row height across platforms (Compose/Skia on desktop
    // vs Android) may differ from the cellHeight the Canvas draws with. On long scrollback the drift
    // accumulates and skews maxValue by ~a row — then scrolling down under-scrolls and a scrollback row
    // peeks from the top (after `clear` — the command row). This way we don't depend on font metrics.
    val contentHeight = with(density) { (screen.size * metrics.cellHeight).toDp() }
    // The Text underlay itself is invisible: it sets the input/IME area and focus — glyphs are drawn by a separate overlay (below).
    val structural = remember { AnnotatedString("") }

    // Glyph TextStyle cache keyed by TermStyle: toGlyphStyle does merge() (SpanStyle+TextStyle
    // allocation) per run. There are hundreds of rows/runs per frame but few distinct styles, so
    // memoize. Reset on a base style or palette (OSC 4/104) change — the result depends on them.
    val glyphStyleCache = remember(textStyle, state.palette, termTheme) { HashMap<TermStyle, TextStyle>() }

    // textStyle-derived styles are computed once per font/theme change, not in the draw phase: the
    // cursor overlay repaints every blink half-period, where copy() would allocate a new TextStyle per
    // frame; likewise for the suggestion "ghost" and the invisible Text underlay.
    val structuralStyle = remember(textStyle) { textStyle.copy(color = Color.Transparent) }
    val cursorGlyphStyle = remember(textStyle, termTheme) { textStyle.copy(color = cursorFg) }
    // "Ghost" — a muted theme text color (not a hardcoded light one): on light themes (Solarized Light)
    // a translucent white on a cream background was invisible.
    val ghostStyle = remember(textStyle, termTheme) { textStyle.copy(color = termTheme.foreground.copy(alpha = 0.45f)) }

    // The PathEffect for dotted/dashed underline depends only on cell height (constant at a fixed font)
    // — compute it once, not per underlined run in the draw phase.
    val underlineEffects = remember(metrics) {
        val t = (metrics.cellHeight / 14f).coerceAtLeast(1f)
        UnderlineEffects(
            dotted = PathEffect.dashPathEffect(floatArrayOf(t, t)),
            dashed = PathEffect.dashPathEffect(floatArrayOf(t * 4f, t * 3f)),
        )
    }

    fun cellAt(x: Float, y: Float) = cellAtOffset(x, y, metrics)

    // Pointer coordinate → cell by plain arithmetic: pointerInput is after verticalScroll and padding,
    // so offset is already in content coordinates (with scroll, without padding). Column/row are clamped
    // to the actual grid so selection doesn't run past it.
    fun posAt(x: Float, y: Float): TerminalPos {
        val p = cellAt(x, y)
        // A fresh snapshot (gestures run outside recomposition), not a captured composition-local.
        val snap = state.screen
        if (snap.isEmpty()) return p
        val row = p.row.coerceIn(0, snap.lastIndex)
        return TerminalPos(row, p.col.coerceIn(0, snap[row].size))
    }

    // Mouse report to the application by pointer coordinates. Pixels come from the same content
    // coordinate system as posAt (after verticalScroll/padding) and are passed separately — encodeMouseReport
    // uses them only in SGR-Pixels (1016); in cell modes they're ignored.
    fun reportMouseAt(
        button: MouseButton,
        type: MouseEventType,
        x: Float,
        y: Float,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
    ): Boolean = state.reportMouse(
        button, type, posAt(x, y), shift, alt, ctrl,
        x.toInt().coerceAtLeast(0), y.toInt().coerceAtLeast(0),
    )

    // try/catch per clipboard coroutine: the scope from rememberCoroutineScope carries a regular Job (not
    // a Supervisor), so an unhandled exception in one operation would cancel the whole scope and kill
    // copy/paste for the rest of the session. Rethrow cancellation, swallow the rest (clipboard unavailable).
    fun copySelection() {
        val text = state.selectedText() ?: return
        clipboardScope.launch {
            try {
                // Wayland: wl-copy (paired with paste via wl-paste); otherwise the standard Compose clipboard.
                if (!withContext(Dispatchers.Default) { writeSystemClipboardDirect(text) }) {
                    clipboard.setClipEntry(plainTextClipEntry(text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // System CLIPBOARD text. On Wayland (the direct path takes over reading) read via wl-paste, bypassing
    // AWT — and don't fall back to Compose even on an empty result, else a non-text clipboard would raise
    // a noisy JDK trace. The subprocess and utility resolution stay off the UI thread (Default).
    suspend fun fetchClipboardText(): String? =
        // Gate and subprocess in one pass on Default; getClipEntry (suspend, waits on the UI thread) on
        // the returned caller context (Main). The direct path, once it takes reading, no longer falls to AWT.
        withContext(Dispatchers.Default) {
            if (systemClipboardDirectHandlesReads()) readSystemClipboardDirect() else null
        } ?: if (systemClipboardDirectHandlesReads()) null else clipboard.getClipEntry()?.readPlainText()

    // Paste from the system clipboard: read asynchronously and send text to the PTY (paste wraps
    // bracketed-paste itself if the app enabled it). Empty/non-text clipboard — no-op.
    fun pasteFromClipboard() {
        clipboardScope.launch {
            try {
                fetchClipboardText()?.let { state.paste(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // Middle click = PRIMARY paste: system PRIMARY (X11 AWT / Wayland wl-paste, sees selections in other
    // windows) → in-app buffer → CLIPBOARD. Reading PRIMARY on Wayland is a subprocess, so the whole flow
    // goes into a coroutine (Default for reading, paste back on the UI) to not block the click.
    fun pastePrimaryOrClipboard() {
        clipboardScope.launch {
            try {
                val primary = withContext(Dispatchers.Default) { readPrimarySelectionText() }
                    ?.takeUnless { it.isBlank() }
                    ?: state.primarySelection?.takeUnless { it.isBlank() }
                if (primary != null) state.paste(primary) else fetchClipboardText()?.let { state.paste(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // Publish a finished mouse selection as PRIMARY: to system PRIMARY (X11 AWT / Wayland wl-copy) and
    // the in-app buffer. Then middle click pastes exactly what was selected. Writing PRIMARY on Wayland
    // is a subprocess, so it goes into a coroutine (Default) to not block the UI. No-op if nothing is selected.
    fun publishPrimary() {
        val text = state.capturePrimarySelection() ?: return
        clipboardScope.launch {
            try {
                withContext(Dispatchers.Default) { writePrimarySelectionText(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // System text "Copy" menu over the selection — a touch copy affordance (Ctrl+Shift+C on a mouse).
    fun showCopyMenu() {
        val sel = state.selection ?: return
        if (state.selectedText() == null) return
        val coords = layoutCoords ?: return
        if (!coords.isAttached) return
        val local = selectionAnchorRect(sel, metrics)
        val topLeft = coords.localToWindow(Offset(local.left, local.top))
        val bottomRight = coords.localToWindow(Offset(local.right, local.bottom))
        textToolbar.showMenu(
            rect = Rect(topLeft, bottomRight),
            onCopyRequested = {
                copySelection()
                state.clearSelection()
                textToolbar.hide()
            },
        )
    }

    Box(modifier.onSizeChanged { viewportSize = it }.background(termTheme.background)) {
      // The whole visible screen is drawn by a single per-cell overlay on the monospace grid (not a
      // flowing Text): cell backgrounds across the full row width (including TUI trailing reverse-spaces),
      // the selection highlight, and the glyphs — each at its column `col*cellWidth`. This way wide
      // characters (CJK, emoji) and their continuation cells hold the grid, and cursor/mouse/handles (same
      // arithmetic) match it. Geometry like the cursor: same padding and scroll offset. The Text below is
      // an invisible underlay (height for scroll + input capture).
      if (sized && screen.isNotEmpty()) {
          val sel = state.selection
          val palette = state.palette // OSC 4/104 index overrides; empty — theme defaults
          // clipToBounds after padding: the scrollback row at the scroll boundary is drawn at top=-chh and
          // would otherwise spill into the top padding zone (desktop has no default clip, unlike Android)
          // — after `clear` the command row would peek there. The clip cuts it at the content edge.
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp).clipToBounds()) {
              val scrollPx = scroll.value.toFloat()
              val cw = metrics.cellWidth
              val chh = metrics.cellHeight
              for (r in screen.indices) {
                  val top = r * chh - scrollPx
                  if (top + chh < 0f || top > size.height) continue
                  val row = screen[r]
                  // 1) Cell backgrounds — collapse same-color runs; stretch the trailing run to the viewport edge.
                  var c = 0
                  while (c < row.size) {
                      val color = cellBgColor(row[c].style, palette, termTheme)
                      if (color == null) { c++; continue }
                      val s = c; c++
                      while (c < row.size && cellBgColor(row[c].style, palette, termTheme) == color) c++
                      val left = s * cw
                      val right = if (c >= row.size) size.width else c * cw
                      drawRect(color, topLeft = Offset(left, top), size = Size(right - left, chh))
                  }
                  // 2) Selection highlight — over the background, under the glyphs.
                  if (sel != null && !sel.isEmpty) {
                      var k = 0
                      while (k < row.size) {
                          if (!sel.contains(r, k)) { k++; continue }
                          val s = k
                          while (k < row.size && sel.contains(r, k)) k++
                          drawRect(selectionBg, topLeft = Offset(s * cw, top), size = Size((k - s) * cw, chh))
                      }
                  }
                  // 3) Glyphs — segment the row into runs (see glyphRuns): consecutive same-style ASCII
                  // cells are drawn with one drawText (the fast monospace case), while each non-ASCII glyph
                  // (mc box-drawing, CJK, symbols) is drawn at its own column separately: a fallback font
                  // gives a non-cellWidth advance, and a long run would accumulate drift (ragged box
                  // horizontals, colored rows sliding). A wide cell — span=2.
                  for (run in glyphRuns(row)) {
                      val x = run.col * cw
                      if (run.text.isNotBlank()) {
                          val style = glyphStyleCache.getOrPut(run.style) { run.style.toGlyphStyle(textStyle, palette, termTheme) }
                          drawGlyphText(measurer, run.text, Offset(x, top), style)
                      }
                      // Draw the underline across the full run width, including under spaces (like xterm).
                      if (run.style.underline) drawCellUnderline(run.style, x, top, run.span * cw, chh, palette, underlineEffects, termTheme)
                  }
                  // 4) Hyperlinks (OSC 8) are underlined in a separate pass — runs of adjacent cells with
                  // one URI; skip those already underlined by the app (SGR) to avoid duplicating.
                  var h = 0
                  while (h < row.size) {
                      val uri = row[h].hyperlink
                      if (uri == null) { h++; continue }
                      val from = h
                      while (h < row.size && row[h].hyperlink == uri) h++
                      val to = h
                      var k = from
                      while (k < to) {
                          if (row[k].style.underline) { k++; continue } // app already underlines — don't duplicate
                          val runStart = k
                          while (k < to && !row[k].style.underline) k++
                          drawCellUnderline(LINK_UNDERLINE_STYLE, runStart * cw, top, (k - runStart) * cw, chh, palette, underlineEffects, termTheme)
                      }
                  }
              }
          }
      }
      Text(
        text = structural,
        style = structuralStyle,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            // bottom += bottomSlack — an empty gap below the content so the live grid sticks to the
            // viewport top rather than leaving a peeked scrollback row above (see bottomSlack).
            .padding(start = PADDING_DP.dp, top = PADDING_DP.dp, end = PADDING_DP.dp, bottom = PADDING_DP.dp + bottomSlack)
            // Scroll height is set explicitly (contentHeight) from cellHeight, not the Text font metrics.
            .height(contentHeight)
            .fillMaxWidth()
            .focusRequester(focusRequester)
            // Focus reporting (DEC 1004): vim/tmux get ESC[I/ESC[O on terminal window focus.
            .onFocusChanged { state.notifyFocus(it.isFocused) }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || closed) return@onPreviewKeyEvent false
                // --- Reverse history search (Ctrl-R): while the overlay is open, keys drive it, not the PTY ---
                if (state.reverseSearchQuery != null) {
                    when {
                        event.key == Key.Escape -> state.closeReverseSearch()
                        event.key == Key.Enter -> state.reverseSearchAccept()
                        // Another Ctrl-R (or ↑) — to the next (older) match; ↓ — to newer.
                        (event.isCtrlPressed && event.key == Key.R) || event.key == Key.DirectionUp ->
                            state.reverseSearchNext()
                        event.key == Key.DirectionDown -> state.reverseSearchPrev()
                        // Delete — remove the selected command from history (manual cleanup), overlay open.
                        event.key == Key.Delete -> state.reverseSearchDeleteSelected()
                        event.key == Key.Backspace -> state.reverseSearchBackspace()
                        else -> {
                            val cp = event.utf16CodePoint
                            if (cp in 0x20..0xFFFF && !event.isCtrlPressed && !event.isAltPressed) {
                                state.reverseSearchAppend(cp.toChar().toString())
                            }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                // Ctrl-R — open reverse history search (intercept from the shell, show our own overlay).
                if (event.isCtrlPressed && event.key == Key.R) {
                    state.openReverseSearch()
                    return@onPreviewKeyEvent true
                }
                // Shift+Tab — cycle autocomplete suggestion alternatives (only if one exists).
                if (event.isShiftPressed && event.key == Key.Tab && state.suggestionTail != null) {
                    state.cycleSuggestion()
                    return@onPreviewKeyEvent true
                }
                // Ctrl+Shift+C — copy the selection (Ctrl+C stays SIGINT for the shell).
                if (event.isCtrlPressed && event.isShiftPressed && event.key == Key.C) {
                    copySelection()
                    return@onPreviewKeyEvent true
                }
                // Ctrl+Shift+V and Shift+Insert — paste from the clipboard (bracketed-paste if the app enabled it).
                if ((event.isCtrlPressed && event.isShiftPressed && event.key == Key.V) ||
                    (event.isShiftPressed && event.key == Key.Insert)
                ) {
                    pasteFromClipboard()
                    return@onPreviewKeyEvent true
                }
                val bytes = mapTerminalKey(
                    key = event.key,
                    ctrl = event.isCtrlPressed,
                    codePoint = event.utf16CodePoint,
                    alt = event.isAltPressed,
                    shift = event.isShiftPressed,
                    applicationCursor = state.applicationCursorKeys,
                    applicationKeypad = state.applicationKeypad,
                )
                if (bytes != null) {
                    state.clearSelection()
                    textToolbar.hide()
                    // Tab with an autocomplete suggestion — accept it (fish-style), don't send to the shell.
                    // Without a suggestion Tab goes to the PTY as usual (server-side completion isn't broken).
                    if (bytes == "\t" && state.suggestionTail != null) {
                        state.acceptSuggestion()
                    } else {
                        state.typeInput(bytes)
                    }
                    true
                } else {
                    false
                }
            }
            .focusable()
            .onGloballyPositioned { layoutCoords = it }
            // Mouse wheel: when the app listens for the mouse — send a wheel report; in alt-screen without
            // mouse tracking (less/man) — arrows (3 rows per "tick"), since there's no own scrollback.
            // Intercept in the Initial pass and consume the event so verticalScroll doesn't jog scrollback;
            // otherwise (primary buffer, no reporting) pass it through — the wheel scrolls scrollback normally.
            // metrics in the keys: reportMouseAt computes the cell from geometry captured at loop start —
            // without restarting after a font-size change, coordinates would use the old grid.
            .pointerInput(state, closed, metrics) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Scroll || closed) continue
                        val reporting = state.mouseTracking != MouseTracking.Off
                        if (!reporting && !state.altScreen) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val dy = change.scrollDelta.y
                        if (dy != 0f) {
                            val up = dy < 0f
                            if (reporting) {
                                reportMouseAt(if (up) MouseButton.WheelUp else MouseButton.WheelDown, MouseEventType.Press, change.position.x, change.position.y)
                            } else {
                                val seq = arrowSequence(if (up) ArrowKey.Up else ArrowKey.Down, state.applicationCursorKeys)
                                repeat(3) { state.send(seq) }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            // Hover reporting for AnyEvent (DEC 1003): the app wants motion events even with no button
            // held. Send Move only on a cell change (button 3 = "not held" is set inside
            // encodeMouseReport). Motion with a button held is Drag, reported by the gesture loops below
            // (left — awaitEachGesture, middle/right — raw handler), so here we suppress events with any
            // button held, else one motion frame would go out as both Move and Drag.
            .pointerInput(state, closed, metrics) {
                var lastHover: TerminalPos? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (closed || state.mouseTracking != MouseTracking.AnyEvent) continue
                        if (event.type != PointerEventType.Move || event.buttons.areAnyPressed) continue
                        val change = event.changes.firstOrNull { !it.pressed } ?: continue
                        val pos = posAt(change.position.x, change.position.y)
                        if (pos != lastHover) {
                            reportMouseAt(MouseButton.Left, MouseEventType.Move, change.position.x, change.position.y)
                            lastHover = pos
                        }
                    }
                }
            }
            // Middle/right mouse buttons: awaitFirstDown (in the gesture below) reacts only to the primary
            // (left) button, so middle/right clicks are caught here on raw events. With active mouse
            // tracking — report press/release to the app; otherwise middle = paste (X11 PRIMARY selection
            // with a clipboard fallback), right = copy the current selection to the clipboard.
            .pointerInput(state, closed, metrics) {
                var reported: MouseButton? = null
                var lastPos: TerminalPos? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                        if (closed) continue
                        val mods = event.keyboardModifiers
                        val shift = mods.isShiftPressed
                        val reporting = state.mouseTracking != MouseTracking.Off && !shift
                        val x = change.position.x
                        val y = change.position.y
                        val pos = posAt(x, y)
                        when (event.type) {
                            PointerEventType.Press -> {
                                val btn = when {
                                    event.buttons.isTertiaryPressed -> MouseButton.Middle
                                    event.buttons.isSecondaryPressed -> MouseButton.Right
                                    else -> null
                                } ?: continue
                                if (reporting) {
                                    reportMouseAt(btn, MouseEventType.Press, x, y, shift, mods.isAltPressed, mods.isCtrlPressed)
                                    reported = btn
                                    lastPos = pos
                                } else if (btn == MouseButton.Middle) {
                                    // Middle click = PRIMARY paste (PRIMARY → in-app buffer → CLIPBOARD).
                                    // Reading PRIMARY on Wayland is a subprocess, so the whole flow is async.
                                    pastePrimaryOrClipboard()
                                } else {
                                    // Right click = copy the current selection to the clipboard (no paste —
                                    // paste stayed on the middle button). No-op if nothing is selected. The
                                    // selection isn't cleared so it can be copied again.
                                    copySelection()
                                }
                                change.consume()
                            }
                            // Middle/right button drag (DEC 1002/1003): awaitFirstDown in the gesture below
                            // reacts only to the primary button, so motion with middle/right held is tracked
                            // here — report only on a cell change to avoid spamming.
                            PointerEventType.Move -> reported?.let { btn ->
                                // If the app disabled tracking (or Shift is held) mid-drag — stop reporting,
                                // but still consume the event until the button is released.
                                if (reporting && pos != lastPos) {
                                    reportMouseAt(btn, MouseEventType.Drag, x, y, shift, mods.isAltPressed, mods.isCtrlPressed)
                                    lastPos = pos
                                }
                                change.consume()
                            }
                            PointerEventType.Release -> reported?.let { btn ->
                                if (reporting) {
                                    reportMouseAt(btn, MouseEventType.Release, x, y, shift, mods.isAltPressed, mods.isCtrlPressed)
                                }
                                reported = null
                                lastPos = null
                                change.consume()
                            }
                            else -> {}
                        }
                    }
                }
            }
            .pointerInput(state, metrics) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    textToolbar.hide()
                    if (down.type == PointerType.Mouse) {
                        focusRequester.requestFocus()
                        val mods = currentEvent.keyboardModifiers
                        val shift = mods.isShiftPressed
                        // Left button with mouse tracking held: translate press/drag/release into reports
                        // until released. (Middle/right are caught by the separate handler below —
                        // awaitFirstDown reacts only to the primary button.) Shift forces selection.
                        if (state.mouseTracking != MouseTracking.Off && !shift) {
                            val ctrl = mods.isCtrlPressed
                            val alt = mods.isAltPressed
                            reportMouseAt(MouseButton.Left, MouseEventType.Press, down.position.x, down.position.y, shift, alt, ctrl)
                            down.consume()
                            var last = posAt(down.position.x, down.position.y)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    ?: continue
                                if (change.pressed) {
                                    val pos = posAt(change.position.x, change.position.y)
                                    if (pos != last) {
                                        // In Normal mode (1000) drag isn't reported — encodeMouseReport
                                        // returns false and sends nothing; for 1002/1003 a report goes out.
                                        reportMouseAt(MouseButton.Left, MouseEventType.Drag, change.position.x, change.position.y, shift, alt, ctrl)
                                        last = pos
                                    }
                                    change.consume()
                                } else {
                                    reportMouseAt(MouseButton.Left, MouseEventType.Release, change.position.x, change.position.y, shift, alt, ctrl)
                                    change.consume()
                                    break
                                }
                            }
                            return@awaitEachGesture
                        }
                        // Local selection. Click counter: 1 — drag selection, 2 — word, 3 — row.
                        val pos = posAt(down.position.x, down.position.y)
                        // Ctrl+click on a cell with an OSC 8 hyperlink — open the URI (don't start a selection).
                        // The URI comes from an untrusted server — open only safe web schemes, blocking
                        // file:/javascript:/anything else that could harm locally.
                        if (mods.isCtrlPressed) {
                            val uri = state.screen.getOrNull(pos.row)?.getOrNull(pos.col)?.hyperlink
                            if (uri != null && isSafeLinkUri(uri)) {
                                runCatching { uriHandler.openUri(uri) }
                                down.consume()
                                return@awaitEachGesture
                            }
                        }
                        val multi = lastClickPos == pos &&
                            lastClickMark?.let { it.elapsedNow() < DOUBLE_CLICK_MS.milliseconds } == true
                        clickCount = if (multi) clickCount + 1 else 1
                        lastClickMark = TimeSource.Monotonic.markNow()
                        lastClickPos = pos
                        when ((clickCount - 1) % 3) {
                            1 -> state.selectWordAt(pos)   // double click — word
                            2 -> state.selectLineAt(pos)   // triple click — row
                            else -> {                       // single — drag selection
                                state.beginSelection(pos)
                                val dragged = drag(down.id) { change ->
                                    change.consume()
                                    state.extendSelection(posAt(change.position.x, change.position.y))
                                }
                                if (!dragged || state.selection?.isEmpty != false) state.clearSelection()
                            }
                        }
                        // A finished mouse selection becomes PRIMARY — the source for a middle click.
                        publishPrimary()
                    } else {
                        // Touch: first — did the finger land on a handle of an existing selection? If so,
                        // drag that edge (hold the other) — edge adjustment like in messengers; update the
                        // "Copy" menu when done. Handle anchors and down.position are in the same content
                        // coordinate system (pointerInput is after verticalScroll/padding), so compare directly.
                        val sel = state.selection
                        val handle = sel?.let { hitTestSelectionHandle(down.position, it, metrics, handleTouchRadiusPx) }
                        if (handle != null) {
                            drag(down.id) { change ->
                                change.consume()
                                val pos = posAt(change.position.x, change.position.y)
                                if (handle == SelectionHandle.START) state.moveSelectionStart(pos)
                                else state.moveSelectionEnd(pos)
                            }
                            if (state.selection?.isEmpty != false) state.clearSelection() else { showCopyMenu(); publishPrimary() }
                            return@awaitEachGesture
                        }
                        // Otherwise split the gestures so tap-for-keyboard and long-press-for-selection
                        // don't fight. Long press → selection mode (don't raise the keyboard); a short tap
                        // → keyboard; finger movement goes to scrolling.
                        val held = awaitLongPressOrCancellation(down.id)
                        if (held != null) {
                            // On hold, immediately select the word under the finger (like in messengers) —
                            // selection and handles show instantly, no need to move the finger. A further
                            // drag extends the edge from the word; raise the "Copy" menu at the end.
                            state.selectWordAt(posAt(held.position.x, held.position.y))
                            drag(held.id) { change ->
                                change.consume()
                                state.extendSelection(posAt(change.position.x, change.position.y))
                            }
                            if (state.selection?.isEmpty != false) state.clearSelection() else { showCopyMenu(); publishPrimary() }
                        } else if (imeInput) {
                            // Not a long press: if the finger is already released — it's a tap, raise the
                            // keyboard; if still on screen (scrolling took the gesture) — leave it.
                            val released = currentEvent.changes.none { it.id == down.id && it.pressed }
                            if (released) {
                                imeFocusRequester.requestFocus()
                                keyboard?.show()
                            }
                        }
                    }
                }
            },
      )

      // Cursor overlay over the text per the DECSCUSR shape. Block — fill the cell + redraw the char in
      // a contrasting color; Underline — a bar at the bottom; Bar — a vertical line on the left. Geometry
      // uses the same monospace metric as the text, offset by the scroll.
      if (cursorVisibleNow && screen.isNotEmpty()) {
          val thickness = with(density) { 2.dp.toPx() }
          val glyph = screen.getOrNull(cursorRow)?.getOrNull(cursorCol)?.text
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp)) {
              val x = cursorCol * metrics.cellWidth
              val y = cursorRow * metrics.cellHeight - scroll.value.toFloat()
              when (state.cursorShape) {
                  CursorShape.Block -> {
                      drawRect(cursorBg, topLeft = Offset(x, y), size = Size(metrics.cellWidth, metrics.cellHeight))
                      if (!glyph.isNullOrBlank()) {
                          drawGlyphText(measurer, glyph, Offset(x, y), cursorGlyphStyle)
                      }
                  }
                  CursorShape.Underline -> drawRect(
                      cursorBg,
                      topLeft = Offset(x, y + metrics.cellHeight - thickness),
                      size = Size(metrics.cellWidth, thickness),
                  )
                  CursorShape.Bar -> drawRect(
                      cursorBg,
                      topLeft = Offset(x, y),
                      size = Size(thickness, metrics.cellHeight),
                  )
              }
          }
      }

      // Autocomplete "ghost": draw the suggestion tail in gray from the cursor position (fish/zsh-style).
      // Same monospace geometry as the cursor; Tab (desktop) / chip (mobile) accept it. In alt-screen
      // (vim/htop) there's no suggestion — [suggestionTail] is already cleared there.
      val ghost = state.suggestionTail
      if (ghost != null && !closed && screen.isNotEmpty()) {
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp).clipToBounds()) {
              val x = cursorCol * metrics.cellWidth
              val y = cursorRow * metrics.cellHeight - scroll.value.toFloat()
              drawGlyphText(measurer, ghost, Offset(x, y), ghostStyle)
          }
      }

      // Reverse history search overlay (Ctrl-R): a bottom panel with the current query and matches
      // (selected — cyan). Enter inserts, Esc closes, another Ctrl-R/arrows page through.
      val rsQuery = state.reverseSearchQuery
      if (rsQuery != null && !closed) {
          val matches = state.reverseSearchResults
          val shown = matches.take(REVERSE_SEARCH_ROWS)
          Column(
              Modifier
                  .align(Alignment.BottomStart)
                  .fillMaxWidth()
                  .background(Color(0xF00A1620))
                  .padding(horizontal = 10.dp, vertical = 6.dp),
          ) {
              Text(
                  text = stringResource(Res.string.terminal_reverse_search_prompt, rsQuery),
                  style = textStyle.copy(color = Color(0xFF8AA0AE)),
              )
              if (shown.isEmpty()) {
                  Text(stringResource(Res.string.terminal_reverse_search_no_matches), style = textStyle.copy(color = Color(0xFF5B6B77)))
              } else {
                  shown.forEachIndexed { i, cmd ->
                      val selected = i == state.reverseSearchIndex.mod(matches.size.coerceAtLeast(1))
                      Text(
                          text = cmd,
                          maxLines = 1,
                          style = textStyle.copy(
                              color = if (selected) SkerryColors.cyan else Color(0xFFB8C6D0),
                              fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                          ),
                      )
                  }
              }
          }
      }

      // Touch selection handles ("drops" at the edges). Drawn only on the mobile path ([imeInput]): the
      // overlay is inside the same padding as the text, offset vertically by the current scroll so the
      // handles stay on the selection edges while scrolling. On a mouse (desktop) there are none.
      if (imeInput && !closed) {
          val sel = state.selection
          val anchors = sel?.takeIf { !it.isEmpty }?.let { selectionHandleAnchors(it, metrics) }
          if (anchors != null) {
              val (startAnchor, endAnchor) = anchors
              Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp)) {
                  val dy = -scroll.value.toFloat()
                  drawSelectionHandle(startAnchor.copy(y = startAnchor.y + dy), handleRadiusPx, metrics.cellHeight, SelectionHandle.START, handleColor)
                  drawSelectionHandle(endAnchor.copy(y = endAnchor.y + dy), handleRadiusPx, metrics.cellHeight, SelectionHandle.END, handleColor)
              }
          }
      }

      // Touch input: an invisible field captures soft-keyboard characters. Diff against the anchor
      // ([imeDeltaToPty]) and reset immediately — the field is just a "funnel" into the PTY, holds no text.
      if (imeInput && !closed) {
          BasicTextField(
              value = imeValue,
              onValueChange = { nv ->
                  val raw = imeDeltaToPty(ANCHOR, nv.text)
                  // sticky-ctrl etc. apply only to real input (not to an empty delta).
                  val out = if (raw.isEmpty()) raw else imeTransform?.invoke(raw) ?: raw
                  if (out.isNotEmpty()) {
                      state.clearSelection()
                      textToolbar.hide()
                      // While reverse-search is open — the soft keyboard edits the query, not the PTY:
                      // DEL → backspace, Enter(CR) → accept, printable chars → into the query.
                      if (state.reverseSearchQuery != null) {
                          for (ch in out) when (ch.code) {
                              127, 8 -> state.reverseSearchBackspace() // DEL / BS
                              13, 10 -> state.reverseSearchAccept() // CR / LF — accept
                              else -> if (ch.code >= 0x20) state.reverseSearchAppend(ch.toString())
                          }
                      } else {
                          state.typeInput(out) // feeds autocomplete (soft keyboard), then goes to the PTY
                      }
                  }
                  imeValue = imeBaseline
              },
              modifier = Modifier.size(1.dp).focusRequester(imeFocusRequester),
              textStyle = TextStyle(color = Color.Transparent),
              cursorBrush = SolidColor(Color.Transparent),
              keyboardOptions = KeyboardOptions(
                  capitalization = KeyboardCapitalization.None,
                  autoCorrectEnabled = false,
                  keyboardType = KeyboardType.Ascii,
                  imeAction = ImeAction.None,
              ),
          )
      }
    }
}

/**
 * Draws one touch selection handle: a vertical "stem" along the cell edge (a row high) and a "drop"
 * circle below the anchor, offset outward from the text (start — left, end — right), like system
 * selection handles. [anchor] — the corner point of the edge in canvas coordinates.
 */
private fun DrawScope.drawSelectionHandle(
    anchor: Offset,
    radius: Float,
    cellHeight: Float,
    which: SelectionHandle,
    handleColor: Color,
) {
    drawLine(
        color = handleColor,
        start = Offset(anchor.x, anchor.y - cellHeight),
        end = anchor,
        strokeWidth = radius * 0.5f,
    )
    val cx = anchor.x + if (which == SelectionHandle.START) -radius else radius
    drawCircle(color = handleColor, radius = radius, center = Offset(cx, anchor.y + radius))
}

/**
 * Draws a terminal glyph run: explicit measure + drawing with a color override on top of the layout.
 * The `drawText(measurer, text, style = …)` overload can't be used: the [TextMeasurer] cache compares
 * styles only by layout attributes (color isn't in the key), and that overload paints with the color
 * baked into the cached layout — on a terminal theme change the whole screen stayed in the old palette
 * until the screen was recreated (tab switch), and identical text of two different colors in one frame
 * would be painted with the first's color. Here color is passed at draw time ([drawText] over a ready
 * [androidx.compose.ui.text.TextLayoutResult] overrides it), so a cache hit is safe; the cache key also
 * doesn't depend on the column (constraints aren't derived from topLeft by default) — identical glyphs
 * in different columns share one layout.
 */
private fun DrawScope.drawGlyphText(measurer: TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    val layout = measurer.measure(AnnotatedString(text), style, density = this, layoutDirection = layoutDirection)
    drawText(layout, color = style.color, topLeft = topLeft)
}

/**
 * Cell background color for the per-cell overlay (fill across the full row width, including trailing
 * spaces). inverse → the text color (reverse-video swaps fg/bg); an explicit bg → its color; default
 * background without inverse → `null` (nothing to draw — the shared terminal background shows). The
 * selection highlight is applied by the overlay in a separate layer over the background.
 */
private fun cellBgColor(style: TermStyle, palette: Palette, theme: TerminalTheme): Color? = when {
    style.inverse -> style.fg.toComposeColor(theme, palette)
    style.bg == TermColor.Default -> null
    else -> style.bg.toComposeColor(theme, palette)
}

/**
 * One glyph run to draw: text, start column [col], number of columns occupied [span] (for underline:
 * Wide=2, ASCII run = cell count), and style.
 */
internal data class GlyphRun(val col: Int, val text: String, val span: Int, val style: TermStyle)

/**
 * Printable ASCII (a single BMP char 0x20..0x7e) — in JetBrains Mono guaranteed a cellWidth advance.
 * Called only for Single cells (Continuation/Wide are filtered earlier in [glyphRuns]).
 */
private fun TermCell.isPlainAscii(): Boolean = text.length == 1 && text[0].code in 0x20..0x7e

/**
 * Segments a grid row into glyph runs. Consecutive same-style ASCII cells are merged into one run (the
 * fast monospace drawText), while each non-ASCII glyph (mc box-drawing, CJK, symbols) is split into its
 * own one-column run — because a fallback font draws such glyphs with advance ≠ cellWidth, and in a long
 * run that accumulates drift (ragged box horizontals, colored rows sliding by a column). A wide cell — a
 * separate two-column run; a Continuation carries no glyph. The run's column is the physical cell index,
 * so a Continuation "hole" doesn't shift the next run.
 */
internal fun glyphRuns(row: List<TermCell>): List<GlyphRun> {
    val runs = ArrayList<GlyphRun>()
    var g = 0
    while (g < row.size) {
        val cell = row[g]
        when {
            cell.width == CellWidth.Continuation -> g++
            cell.width == CellWidth.Wide -> {
                runs.add(GlyphRun(g, cell.text, 2, cell.style)); g++
            }
            !cell.isPlainAscii() -> {
                runs.add(GlyphRun(g, cell.text, 1, cell.style)); g++
            }
            else -> {
                val st = cell.style
                val start = g
                val sb = StringBuilder()
                while (g < row.size && row[g].width == CellWidth.Single && row[g].style == st && row[g].isPlainAscii()) {
                    sb.append(row[g].text); g++
                }
                runs.add(GlyphRun(start, sb.toString(), g - start, st))
            }
        }
    }
    return runs
}

/**
 * [TextStyle] for drawing a cell glyph: the base monospace style + color/weight/underline from
 * [TermStyle]. The background is removed (the overlay draws it in a separate layer across the full cell width).
 */
private fun TermStyle.toGlyphStyle(base: TextStyle, palette: Palette, theme: TerminalTheme): TextStyle =
    base.merge(toSpanStyle(palette, theme).copy(background = Color.Unspecified))

private fun TermStyle.toSpanStyle(palette: Palette, theme: TerminalTheme): SpanStyle {
    // inverse swaps text and background; with a default background it becomes the terminal background color.
    val resolvedFg = fg.toComposeColor(theme, palette)
    val resolvedBg = if (bg == TermColor.Default) theme.background else bg.toComposeColor(theme, palette)
    var fgColor = if (inverse) resolvedBg else resolvedFg
    val bgColor = when {
        inverse -> resolvedFg
        bg == TermColor.Default -> Color.Unspecified
        else -> resolvedBg
    }
    if (hidden) fgColor = bgColor.takeIf { it != Color.Unspecified } ?: theme.background
    if (dim) fgColor = fgColor.copy(alpha = 0.6f)
    // Underline (including modern 4:x forms and the SGR 58 color) is drawn manually in Canvas — Compose
    // TextDecoration can't do wavy/dotted/double or a separate color. Here only strikethrough, which is native.
    return SpanStyle(
        color = fgColor,
        background = bgColor,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (strikethrough) TextDecoration.LineThrough else null,
    )
}

/** Palette overrides (OSC 4/104): index 0..255 → Rgb. Empty — theme defaults are used. */
private typealias Palette = Map<Int, TermColor.Rgb>

/** Precomputed PathEffects for dotted/dashed underline (depend only on cell height). */
private data class UnderlineEffects(val dotted: PathEffect, val dashed: PathEffect)

/** OSC 8 hyperlink underline style: a single line in the theme cyan (primary cyan). */
private val LINK_UNDERLINE_STYLE = TermStyle(
    underlineStyle = UnderlineStyle.Single,
    underlineColor = TermColor.Rgb(0x2B, 0xBD, 0xEE),
)

/**
 * Underline line color: [TermStyle.underlineColor], or with [TermColor.Default] it follows the text
 * color (accounting for inverse and dim). Rendered separately from the glyph, so the color is computed here.
 */
private fun TermStyle.underlineDrawColor(palette: Palette, theme: TerminalTheme): Color {
    val base = if (underlineColor == TermColor.Default) {
        if (inverse) {
            if (bg == TermColor.Default) theme.background else bg.toComposeColor(theme, palette)
        } else fg.toComposeColor(theme, palette)
    } else {
        underlineColor.toComposeColor(theme, palette)
    }
    return if (dim) base.copy(alpha = 0.6f) else base
}

/**
 * Draws the underline of the required shape (modern SGR `4:x`) at the bottom edge of a cell/run.
 * [left]/[width] — the horizontal segment, [top] — the row top, [chh] — the cell height.
 */
private fun DrawScope.drawCellUnderline(style: TermStyle, left: Float, top: Float, width: Float, chh: Float, palette: Palette, effects: UnderlineEffects, theme: TerminalTheme) {
    if (style.underlineStyle == UnderlineStyle.None) return
    val color = style.underlineDrawColor(palette, theme)
    val thickness = (chh / 14f).coerceAtLeast(1f)
    val y = top + chh - thickness * 1.5f
    val right = left + width
    when (style.underlineStyle) {
        UnderlineStyle.None -> {}
        UnderlineStyle.Single ->
            drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = thickness)
        UnderlineStyle.Double -> {
            val gap = thickness * 1.6f
            drawLine(color, Offset(left, y - gap), Offset(right, y - gap), strokeWidth = thickness)
            drawLine(color, Offset(left, y + gap), Offset(right, y + gap), strokeWidth = thickness)
        }
        UnderlineStyle.Dotted ->
            drawLine(
                color, Offset(left, y), Offset(right, y), strokeWidth = thickness,
                pathEffect = effects.dotted,
            )
        UnderlineStyle.Dashed ->
            drawLine(
                color, Offset(left, y), Offset(right, y), strokeWidth = thickness,
                pathEffect = effects.dashed,
            )
        UnderlineStyle.Curly -> {
            val amp = thickness * 1.6f
            val halfPeriod = (chh / 6f).coerceAtLeast(2f)
            val path = Path().apply {
                moveTo(left, y)
                var x = left
                var up = true
                while (x < right) {
                    val nx = (x + halfPeriod).coerceAtMost(right)
                    val peak = if (up) y - amp else y + amp
                    quadraticTo((x + nx) / 2f, peak, nx, y)
                    x = nx
                    up = !up
                }
            }
            drawPath(path, color, style = Stroke(width = thickness))
        }
    }
}

/**
 * Converts [TermColor] to a Compose Color: Default — the contextual color; Rgb — directly; Indexed —
 * the xterm palette, where the first 16 indices come from the active theme ([TerminalTheme.ansi]) and
 * 16..255 is the standard 6×6×6 cube and grayscale.
 */
private fun TermColor.toComposeColor(theme: TerminalTheme, palette: Palette): Color = when (this) {
    TermColor.Default -> theme.foreground
    is TermColor.Rgb -> Color(r, g, b)
    is TermColor.Indexed -> xtermColor(index, palette, theme)
}

/** ANSI 0..15 from the active theme + the standard xterm cube/grayscale for 16..255; an OSC 4 override takes priority. */
private fun xtermColor(index: Int, palette: Palette, theme: TerminalTheme): Color {
    palette[index]?.let { return Color(it.r, it.g, it.b) }
    if (index in 0..15) return theme.ansi[index]
    return xtermDefaultColor(index)
}

/** The theme's default xterm palette (without OSC 4 overrides). */
private fun xtermDefaultColor(index: Int): Color = when (index) {
    0 -> Color(0xFF2A3540); 1 -> Color(0xFFE94B4B); 2 -> Color(0xFF5DCE9E); 3 -> Color(0xFFF2A65A)
    4 -> Color(0xFF4A9EDB); 5 -> Color(0xFFC792EA); 6 -> Color(0xFF2BBDEE); 7 -> Color(0xFFC9D6DE)
    8 -> Color(0xFF5A7080); 9 -> Color(0xFFFF6B6B); 10 -> Color(0xFF7FE9B8); 11 -> Color(0xFFFFC078)
    12 -> Color(0xFF6FC3F5); 13 -> Color(0xFFE0A8FF); 14 -> Color(0xFF5FD1F4); 15 -> Color(0xFFFFFFFF)
    in 16..231 -> {
        val n = index - 16
        val r = n / 36; val g = (n / 6) % 6; val b = n % 6
        fun lvl(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(lvl(r), lvl(g), lvl(b))
    }
    in 232..255 -> { val v = 8 + (index - 232) * 10; Color(v, v, v) }
    else -> Color(0xFFC9D6DE)
}
