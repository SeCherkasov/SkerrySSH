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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import app.skerry.shared.ssh.PtySize
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.highlight.HighlightKind
import app.skerry.shared.terminal.highlight.applyTo
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.searchTerminal
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.design.ModalPresence
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
import app.skerry.ui.theme.Skerry

/** Max gap (ms) between clicks to count as double/triple click. */
private const val DOUBLE_CLICK_MS = 350

/** Cursor blink half-period (ms), matching xterm's ~530ms phase. */
private const val CURSOR_BLINK_MS = 530L

/**
 * Cells the link-underline passes (4-6) must not paint: an app underline is already there (no
 * double line), or the cell is concealed (SGR 8) and the underline would trace the hidden run.
 */
private fun TermCell.blocksLinkUnderline(): Boolean = style.underline || style.hidden

// Test-only counter of TerminalScreen body executions (single-threaded: composition or the
// sequential test JVM; revisit before enabling parallel test execution). Pins that the cursor
// blink repaints its overlay without recomposing the screen.
internal var terminalScreenCompositions = 0

private const val PADDING_DP = 14
// Number of history matches shown at once in the reverse-search (Ctrl-R) overlay.
private const val REVERSE_SEARCH_ROWS = 6

/** Radius of the touch selection handle's dot, and its finger hit-test radius. */
private const val HANDLE_RADIUS_DP = 7
private const val HANDLE_TOUCH_RADIUS_DP = 22

/**
 * One consistent settled-screen read for the auto-fit machine (see the convergence effect in
 * [TerminalScreen]). [version] sits first so the equality snapshotFlow dedupes on short-circuits
 * before ever comparing the row list.
 */
private data class AutoFitScreenRead(
    val version: Int,
    val cols: Int,
    val screen: List<List<TermCell>>,
    val rows: Int,
    val cursorRow: Int,
)

@Composable
fun TerminalScreen(
    state: TerminalScreenState,
    modifier: Modifier = Modifier,
    imeInput: Boolean = false,
    imeTransform: ((String) -> String)? = null,
    fixedGrid: PtySize? = null,
    // Auto-shrink the font while wide output soft-wraps (issue #180, mobile: a narrow screen turns
    // wide output into a wall of wrapped lines). The state machine lives on
    // [TerminalScreenState.autoFit]; desktop keeps the user's font size. Ignored with a pinned
    // grid, whose font is scaled to the recording's geometry instead.
    autoFitEnabled: Boolean = false,
    // Whether this is the pane the user is typing into. On a split every pane draws its own
    // terminal, and only the focused one claims the keyboard — otherwise the last one composed
    // would take it and the accent border would point at a pane that receives nothing.
    focused: Boolean = true,
    /**
     * Drawn just under the cursor, in the terminal's own coordinates — the sharing hint ("… is
     * typing") belongs at the place where the text is appearing, and only this composable knows
     * where that is. Receives a [Modifier] carrying the offset; `null` draws nothing.
     */
    cursorOverlay: (@Composable (Modifier) -> Unit)? = null,
    // Ctrl+click on a file path in the output hands it here (the caller reveals it in its session's
    // file panel). `null` — no path affordance at all: the setting is off, or this pane has no file
    // panel of its own (split pane, recording playback, preview).
    onOpenPath: ((String) -> Unit)? = null,
) {
    terminalScreenCompositions++
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
    val density = LocalDensity.current
    // A full TUI screen redraws hundreds of distinct glyph runs per frame; the default layout cache
    // (8 entries) thrashes and re-lays-out every run every frame. Sized to hold a busy screen's runs.
    // Color is passed at draw time (see drawGlyphText), so cache hits stay theme-safe.
    val measurer = rememberTextMeasurer(cacheSize = 1024)
    val paddingPx = with(density) { PADDING_DP.dp.toPx() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    // Keyed on appearance itself (@Immutable data class, structural equality): changes exactly when
    // font/size changes. FontFamily is not a reliable key — equality of two built instances depends
    // on compose-resources Font.equals, so reference-equality would invalidate textStyle/metrics on
    // every recomposition. mono is captured by the lambda and stays consistent with appearance.font.
    val baseStyle = remember(appearance, termTheme) {
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
    // With a pinned grid ([fixedGrid]) the font is scaled so that grid fills the viewport; the scale
    // is derived from metrics measured at the unscaled size, so the style has to be built twice.
    val baseMetrics = remember(baseStyle, density) { terminalMetrics(measurer, baseStyle, density) }
    val autoFitOn = autoFitEnabled && fixedGrid == null
    val fontScale = when {
        fixedGrid != null -> fitFontScale(
            viewportSize.width.toFloat(), viewportSize.height.toFloat(), paddingPx, baseMetrics,
            fixedGrid.cols, fixedGrid.rows,
        )
        autoFitOn -> state.autoFit.scale
        else -> 1f
    }
    // While auto-shrunk the row gap tightens toward the glyph height ([AUTOFIT_LINE_HEIGHT_RATIO]):
    // the user's own line-height ratio is tuned for a reading size and leaves visually huge gaps at
    // 7-8px. Restored together with the font size.
    val autoFitShrunk = autoFitOn && fontScale < 1f
    val textStyle = remember(baseStyle, fontScale, autoFitShrunk) {
        if (fontScale == 1f) {
            baseStyle
        } else {
            baseStyle.copy(
                fontSize = baseStyle.fontSize * fontScale,
                lineHeight = if (autoFitShrunk) {
                    baseStyle.fontSize * fontScale * autoFitLineHeightRatio(appearance.lineHeight)
                } else {
                    baseStyle.lineHeight * fontScale
                },
                letterSpacing = baseStyle.letterSpacing * fontScale,
            )
        }
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

    // Bumped every time a selection is actually copied to the clipboard (right click / Ctrl+Shift+C /
    // touch "Copy" menu) — drives the transient "Copied" banner overlay below. Starts at 0 (no banner
    // on first composition); each increment re-triggers the banner's show-then-hide timer.
    var copiedNonce by remember(state) { mutableStateOf(0) }

    // Ctrl+hover over a link shows the hand cursor (VS Code style). hoverPos is the last cell the
    // pointer was over (null when it left the terminal); linkHover drives the cursor icon and is
    // recomputed both on pointer move and on Ctrl press/release so the cursor toggles without moving.
    var hoverPos by remember { mutableStateOf<TerminalPos?>(null) }
    var linkHover by remember { mutableStateOf(false) }
    // File paths get no underline at rest — a directory listing would turn into a wall of lines —
    // so the affordance appears only under the pointer while Ctrl is held, like an editor's go-to.
    var hoveredPath by remember { mutableStateOf<HoveredPath?>(null) }

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
    val metrics = remember(textStyle, density) { terminalMetrics(measurer, textStyle, density) }
    val handleRadiusPx = with(density) { HANDLE_RADIUS_DP.dp.toPx() }
    val handleTouchRadiusPx = with(density) { HANDLE_TOUCH_RADIUS_DP.dp.toPx() }

    // An active desktop session grabs focus immediately (physical keyboard, no click needed to type).
    // On touch ([imeInput]) focus is not requested automatically: the hidden IME field would raise the
    // soft keyboard right on connect, pushing the layout/terminal up. The keyboard appears on user tap
    // (gesture handler below, the usual mobile SSH client behavior); the special-key panel works without it (sends to
    // the PTY directly).
    // Also re-keyed on the open-modal count: a modal scrim takes keyboard focus for Esc handling and
    // its disposal clears focus to no one, so the terminal re-claims it once every modal is closed —
    // otherwise typing goes dead until the user clicks the terminal.
    // Also re-keyed on the search panel: while it is open its field owns the keyboard, and the
    // terminal takes focus back when it closes (otherwise typing would go dead until a click).
    // Also re-keyed on [focused]: that is how the keyboard follows a pane focused from the keyboard
    // (⌘/Ctrl+Shift + arrow), where there is no click on the terminal to hand it over.
    val modalsOpen = ModalPresence.openCount
    val searchOpen = state.search.query != null
    LaunchedEffect(state, modalsOpen, searchOpen, focused) {
        if (focused && !closed && !imeInput && modalsOpen == 0 && !searchOpen) focusRequester.requestFocus()
    }

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
        val autoScroll = TerminalAutoScroll(state.inputVersion, slackPx = (2 * metrics.cellHeight).roundToInt())
        snapshotFlow { Triple(state.snapshotVersion, state.inputVersion, scroll.maxValue) }
            .collect { (_, input, max) ->
                val snap = autoScroll.shouldSnap(scroll.value, max, input)
                // While a search hit is selected, the viewport belongs to the search: output
                // streaming in must not yank the user away from the line they are reading.
                // shouldSnap is still consulted (it tracks input/max) — only the scroll is skipped.
                if (snap && state.search.currentMatch == null) scroll.scrollTo(max)
            }
    }

    // Bring the selected search hit into view (only when it is off-screen — see scrollToRow), so
    // stepping through matches moves the viewport the way a browser's find bar does. Keyed on the
    // user's own navigation (searchNavVersion) plus the selected index, never on the match's row:
    // while scrollback evicts rows under streaming output, that row shifts on its own, and
    // following it would drag the viewport away from what the user is reading.
    LaunchedEffect(state, metrics, paddingPx) {
        var lastSelection: Pair<Int, Int>? = null
        snapshotFlow { Triple(state.search.navVersion, state.search.index, viewportSize.height) }
            .collect { (navVersion, index, height) ->
                val match = state.search.currentMatch
                if (match == null || height == 0) return@collect
                val selection = navVersion to index
                if (selection == lastSelection) return@collect
                lastSelection = selection
                val target = scrollToRow(
                    row = match.row,
                    currentScroll = scroll.value,
                    viewportPx = (height - 2 * paddingPx).coerceAtLeast(metrics.cellHeight),
                    maxScroll = scroll.maxValue,
                    cellHeight = metrics.cellHeight,
                )
                if (target != null) scroll.scrollTo(target)
            }
    }

    // OSC 52: the app (tmux/vim) requests writing text to the system clipboard; done on the UI thread.
    // Emissions arrive only when the clipboard-write gate is on (default off, enforced in the
    // emulator); the emulator never forwards server clipboard-read requests, so no user clipboard leak.
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
    // content height. ([paddingPx]/[viewportSize] are declared above — the font scale needs them.)
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
        // With a pinned grid the emulator keeps the recording's geometry (the font was scaled to it
        // instead); only the content pixel size follows the viewport.
        val content = gridSizeFor(viewportSize.width.toFloat(), viewportSize.height.toFloat(), paddingPx, metrics)
        state.resize(if (fixedGrid == null) content else fixedGrid.copy(widthPx = content.widthPx, heightPx = content.heightPx))
        sized = true
    }

    // Auto-fit convergence (issue #180): one machine step per *settled* snapshot — one whose grid
    // width matches what the current metrics imply. Right after a step the emulator still holds the
    // previous, narrower grid with its old wrap flags; stepping again on it would run far past the
    // fit, so snapshots are skipped until the resize above (debounce included) has landed. Each
    // step shrinks the font -> metrics change -> the resize effect reflows the grid -> the next
    // settled snapshot drives the next step, until the output fits or the floor is reached.
    if (autoFitOn) {
        val autoFitFloorScale = autoFitFloor(appearance.fontSizeSp)
        LaunchedEffect(state, viewportSize, metrics, paddingPx, autoFitFloorScale) {
            if (viewportSize.width == 0 || viewportSize.height == 0) return@LaunchedEffect
            val expectedCols =
                gridSizeFor(viewportSize.width.toFloat(), viewportSize.height.toFloat(), paddingPx, metrics).cols
            // All four values are read inside snapshotFlow so they come from one consistent
            // snapshot: publishes are individual writes from the session scope, and piecemeal
            // reads in the collect body could pair a fresh screen with a stale cursor — counting
            // the user's own wrapped command line as wide output and taking a spurious step.
            // One scale-moving step per effect instance: after a step the grid keeps its old
            // column count until recomposition rebuilds metrics and restarts this effect, so
            // snapshots streaming in during that gap still pass the cols gate with stale wrap
            // flags — stepping on them would run past the fit. A step that did not move the
            // scale (already at the floor, Overshot -> Converged) restarts nothing and must
            // not latch, or the machine would freeze there.
            var stepped = false
            snapshotFlow {
                AutoFitScreenRead(state.snapshotVersion, state.cols, state.screen, state.rows, state.cursorRow)
            }.collect { read ->
                if (stepped) return@collect
                // Nothing left to decide once the machine is done or handed over — skip the scan,
                // not just the step: this collector outlives convergence by the whole session.
                if (state.autoFit.converged || state.autoFit.locked) return@collect
                // A dead session cannot reflow (its command queue closed with the output), so a
                // step here would only rescale the frozen glyphs without re-wrapping them —
                // exactly what the Disconnected screen declines to offer manually.
                if (state.state.value is TerminalState.Closed) return@collect
                if (read.cols != expectedCols) return@collect
                val before = state.autoFit.scale
                state.autoFit.onScreenSettled(
                    wrapped = gridNeedsShrink(read.screen, read.rows, read.cursorRow),
                    floor = autoFitFloorScale,
                )
                if (state.autoFit.scale != before) stepped = true
            }
        }
    }

    // Cursor blink phase: with blink on, toggle a boolean once per half-period; otherwise the cursor
    // stays solid. The cursor is drawn as an overlay (see below), so blinking repaints only a small
    // Canvas, not the whole screen text.
    // A State object, not a delegated property: the blink phase is read ONLY inside the cursor
    // overlay's draw lambda, so each 530ms toggle invalidates that one small canvas's draw and
    // never recomposes this screen - a composition-time read here made every idle pane recompose
    // twice a second, per pane, focused or not.
    val blinkOn = remember { mutableStateOf(true) }
    LaunchedEffect(state.cursorBlink, state.cursorVisible, closed) {
        if (!state.cursorBlink || !state.cursorVisible || closed) {
            blinkOn.value = true
            return@LaunchedEffect
        }
        while (true) {
            blinkOn.value = true
            delay(CURSOR_BLINK_MS)
            blinkOn.value = false
            delay(CURSOR_BLINK_MS)
        }
    }
    val cursorComposed = sized && !closed && state.cursorVisible

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
    // Keyed by TermStyle, then by highlight category: looking up a highlighted run must not build a
    // derived TermStyle first, or every frame of a drag would allocate one per run just to hit the cache.
    val glyphStyleCache = remember(textStyle, state.palette, termTheme) { HashMap<TermStyle, Array<TextStyle?>>() }

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

    // Is there an openable link (OSC 8 or bare text URL) under this cell? Shares [openableLinkAt]
    // with the Ctrl+click handler, so the hand cursor appears exactly where a click would open.
    fun linkUnderPos(pos: TerminalPos): Boolean =
        openableLinkAt(state.screen, pos.row, pos.col) != null

    // The file-path span under this cell, or null. Only consulted after [linkUnderPos] says no: a
    // URL always wins, so a path is never offered where a link would open instead.
    fun filePathUnderPos(pos: TerminalPos): TextLinkSpan? {
        if (onOpenPath == null) return null
        val row = state.screen.getOrNull(pos.row) ?: return null
        return filePathSpanAt(row, pos.col)
    }

    // Recomputes both Ctrl+hover affordances (hand cursor, path underline) for [pos]. Called on
    // pointer move and on Ctrl press/release, so the cursor toggles without moving the mouse.
    fun updateHoverAffordance(pos: TerminalPos, ctrl: Boolean) {
        val onLink = ctrl && linkUnderPos(pos)
        val path = if (ctrl && !onLink) filePathUnderPos(pos) else null
        hoveredPath = path?.let { HoveredPath(pos.row, it) }
        linkHover = onLink || path != null
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
        copiedNonce++ // show the transient "Copied" banner (only when something was actually copied)
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

    // System CLIPBOARD text; the Wayland-direct vs Compose logic is shared with the snippet
    // variable dialog (see ClipboardText.kt).
    suspend fun fetchClipboardText(): String? = fetchSystemClipboardText(clipboard)

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
          // Search hits are found for the visible rows only, not taken from the state's match list:
          // that list is rebuilt on a throttle (a pass over a 50 000-row scrollback is expensive),
          // so during streaming output it can lag by a snapshot — and a highlight painted from
          // stale rows would sit on the wrong lines. Rescanning ~50 rows per change is cheap.
          val currentHit = state.search.currentMatch
          // The same window the draw loop below walks, so a hit on the partly visible slack row is
          // highlighted too. derivedStateOf, not a plain read of scroll.value: this sits in
          // composition, and the raw value changes every pixel of a drag — the window changes per row.
          // Keys use the content VERSION, not the list: a remember key compares with equals(), and
          // a structural list compare walks the scrollback on the Main thread on every publish.
          // `state` stays in every version-keyed remember: the version is monotonic per EMULATOR,
          // and an in-place session switch (mobile keeps the composition slot) could land on a
          // coinciding counter — the state reference restores the per-session scope the screen
          // instance used to provide.
          val screenVersion = state.screenContentVersion
          val searchWindow by remember(state, metrics, viewportSize, screenVersion) {
              derivedStateOf {
                  visibleRowWindow(scroll.value.toFloat(), viewportSize.height.toFloat(), metrics.cellHeight, screen.size)
              }
          }
          // The viewport bottom is what an incremental search re-selects around, and only the render
          // layer knows the scroll position — so it keeps the state's anchor current.
          LaunchedEffect(state, searchWindow) { state.search.setAnchorRow(searchWindow.last) }
          val hitsByRow = remember(
              state, screenVersion, state.search.query, state.search.caseSensitive, state.search.regex, searchWindow,
          ) {
              val query = state.search.query
              if (query.isNullOrEmpty()) {
                  emptyMap()
              } else {
                  searchTerminal(
                      screen = screen,
                      query = query,
                      caseSensitive = state.search.caseSensitive,
                      regex = state.search.regex,
                      fromRow = searchWindow.first,
                      toRow = searchWindow.last + 1,
                  ).matches.groupBy { it.row }
              }
          }
          val highlightByRow = rememberRowHighlights(state, screen, searchWindow)
          // Plain-text URLs for the visible window: a URL cut by a soft wrap spans several rows,
          // and its chain is joined and matched once rather than per row. Hoisted out of the draw
          // phase like hitsByRow above - computed inline in the draw lambda it re-flattened and
          // re-regexed the same rows on every repaint, sixty times a second during a scroll drag.
          // searchWindow is a superset of the draw loop's window for any consistent scroll (it is
          // built from the unpadded viewport height), and a fast drag inherits the same known,
          // self-healing one-frame race as the search highlight - not new here; clicks resolve
          // links from a fresh snapshot, never from this map.
          val linksByRow = remember(state, screenVersion, searchWindow) {
              linkScanPasses++
              linkSpansByRow(screen, searchWindow)
          }
          // clipToBounds after padding: the scrollback row at the scroll boundary is drawn at top=-chh and
          // would otherwise spill into the top padding zone (desktop has no default clip, unlike Android)
          // — after `clear` the command row would peek there. The clip cuts it at the content edge.
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp).clipToBounds()) {
              val scrollPx = scroll.value.toFloat()
              val cw = metrics.cellWidth
              val chh = metrics.cellHeight
              // Only the rows intersecting the viewport (±1 row of slack; clipToBounds cuts the
              // spill): walking all of scrollback per repaint is O(history) of wasted work
              // whenever output streams while the user sits scrolled up in history. The search
              // highlight above derives its window from the same helper.
              val drawWindow = visibleRowWindow(scrollPx, size.height, chh, screen.size)
              for (r in drawWindow) {
                  val top = r * chh - scrollPx
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
                  // 2b) Search hits — over the background like the selection, under the glyphs. The
                  // selected hit is washed stronger so it is findable among the others.
                  hitsByRow[r]?.forEach { hit ->
                      val left = hit.startCol * cw
                      drawRect(
                          if (hit == currentHit) termTheme.searchCurrentMatch else termTheme.searchMatch,
                          topLeft = Offset(left, top),
                          size = Size((hit.endCol - hit.startCol) * cw, chh),
                      )
                  }
                  // 3) Glyphs — segment the row into runs (see glyphRuns): consecutive same-style ASCII
                  // cells are drawn with one drawText (the fast monospace case), while each non-ASCII glyph
                  // (mc box-drawing, CJK, symbols) is drawn at its own column separately: a fallback font
                  // gives a non-cellWidth advance, and a long run would accumulate drift (ragged box
                  // horizontals, colored rows sliding). A wide cell — span=2.
                  for (run in glyphRuns(row, highlightByRow[r])) {
                      val x = run.col * cw
                      if (run.text.isNotBlank()) {
                          val byKind = glyphStyleCache.getOrPut(run.style) { arrayOfNulls(HIGHLIGHT_KIND_COUNT) }
                          val style = byKind[run.kind.ordinal]
                              ?: run.kind.applyTo(run.style).toGlyphStyle(textStyle, palette, termTheme)
                                  .also { byKind[run.kind.ordinal] = it }
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
                      // blocksLinkUnderline skips concealed cells (SGR 8) in all three link passes:
                      // LINK_UNDERLINE_STYLE carries its own color and hidden=false, so it bypasses
                      // underlineDrawColor's hidden gate and would trace the hidden run's position.
                      // Hit-testing refuses them too (openableLinkAt / filePathSpanAt).
                      var k = from
                      while (k < to) {
                          if (row[k].blocksLinkUnderline()) { k++; continue } // app already underlines — don't duplicate
                          val runStart = k
                          while (k < to && !row[k].blocksLinkUnderline()) k++
                          drawCellUnderline(LINK_UNDERLINE_STYLE, runStart * cw, top, (k - runStart) * cw, chh, palette, underlineEffects, termTheme)
                      }
                  }
                  // 5) Plain-text URLs (no OSC 8) — http(s)/ftp printed as bare text (MOTD, curl output)
                  // are underlined like hyperlinks so Ctrl+click can open them; skip cells already
                  // carrying an OSC 8 URI (pass 4) or an app underline (pass 3) to avoid a double line.
                  // Spans come from the whole soft-wrap chain, so a URL cut at the right margin keeps
                  // its underline on the row it continues on.
                  for (link in linksByRow[r].orEmpty()) {
                      var k = link.start
                      while (k < link.endExclusive) {
                          if (row[k].hyperlink != null || row[k].blocksLinkUnderline()) { k++; continue }
                          val runStart = k
                          while (k < link.endExclusive && row[k].hyperlink == null && !row[k].blocksLinkUnderline()) k++
                          drawCellUnderline(LINK_UNDERLINE_STYLE, runStart * cw, top, (k - runStart) * cw, chh, palette, underlineEffects, termTheme)
                      }
                  }
                  // 6) The file path under Ctrl+hover — underlined only while pointed at (unlike URLs,
                  // which are always underlined): every second line of a listing holds a path, and
                  // marking them all would read as noise rather than as an affordance.
                  hoveredPath?.takeIf { it.row == r }?.let { hover ->
                      var k = hover.span.start
                      while (k < hover.span.endExclusive && k < row.size) {
                          if (row[k].blocksLinkUnderline()) { k++; continue }
                          val runStart = k
                          while (k < hover.span.endExclusive && k < row.size && !row[k].blocksLinkUnderline()) k++
                          drawCellUnderline(LINK_UNDERLINE_STYLE, runStart * cw, top, (k - runStart) * cw, chh, palette, underlineEffects, termTheme)
                      }
                  }
              }
          }
      }
      // Keyboard focus sits on this wrapper rather than on the scrolling text inside it: a focusable
      // node within its own verticalScroll makes Compose scroll that container to "reveal" the node
      // when it takes focus, and clicking the pane back into focus slid the whole screen up by a
      // couple of rows. Nothing scrollable wraps this Box, so taking focus moves nothing.
      Box(
          Modifier
              .fillMaxSize()
              .focusRequester(focusRequester)
              // Focus reporting (DEC 1004): vim/tmux get ESC[I/ESC[O on terminal window focus.
              .onFocusChanged { state.notifyFocus(it.isFocused) }
              .onPreviewKeyEvent { event ->
                // Toggle the link (hand) cursor as Ctrl is pressed/released without moving the mouse.
                // Runs before the KeyDown guard so a Ctrl KeyUp also clears it. Never consumes the event.
                hoverPos?.let { updateHoverAffordance(it, event.isCtrlPressed) }
                if (event.type != KeyEventType.KeyDown || closed) return@onPreviewKeyEvent false
                if (isImeOwnedPrintable(imeInput, event.isCtrlPressed, event.isAltPressed, event.utf16CodePoint) &&
                    isSoftKeyboardEvent(event)
                ) {
                    return@onPreviewKeyEvent true
                }
                // --- Reverse history search (Ctrl-R): while the overlay is open, keys drive it, not the PTY ---
                if (state.reverseSearch.query != null) {
                    when {
                        event.key == Key.Escape -> state.reverseSearch.close()
                        event.key == Key.Enter -> state.reverseSearch.accept()
                        // Another Ctrl-R (or ↑) — to the next (older) match; ↓ — to newer.
                        (event.isCtrlPressed && event.key == Key.R) || event.key == Key.DirectionUp ->
                            state.reverseSearch.next()
                        event.key == Key.DirectionDown -> state.reverseSearch.prev()
                        // Delete — remove the selected command from history (manual cleanup), overlay open.
                        event.key == Key.Delete -> state.reverseSearch.deleteSelected()
                        event.key == Key.Backspace -> state.reverseSearch.backspace()
                        else -> {
                            val cp = event.utf16CodePoint
                            if (cp in 0x20..0xFFFF && !event.isCtrlPressed && !event.isAltPressed) {
                                state.reverseSearch.append(cp.toChar().toString())
                            }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                // Ctrl-R — open reverse history search (intercept from the shell, show our own overlay).
                if (event.isCtrlPressed && event.key == Key.R) {
                    state.reverseSearch.open()
                    return@onPreviewKeyEvent true
                }
                // Shift+Tab — cycle autocomplete suggestion alternatives. Swallowed whenever a ghost
                // is on screen, even one that cannot be accepted yet: letting it through sends ESC[Z,
                // which the engine reads as navigation and drops the line it is tracking.
                val ghostOnScreen = state.hasSuggestion || state.suggestionTail != null
                if (event.isShiftPressed && event.key == Key.Tab && ghostOnScreen) {
                    state.cycleSuggestion()
                    return@onPreviewKeyEvent true
                }
                // Clipboard chords: Ctrl+Shift+C/V and the X11 pair Ctrl+Insert / Shift+Insert.
                // Plain Ctrl+C stays SIGINT and bare Insert stays CSI 2~ — see [clipboardChord].
                val clipboard = clipboardChord(
                    ctrl = event.isCtrlPressed,
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed,
                    insertKey = event.isInsertKey(),
                    key = event.key,
                )
                if (clipboard != null) {
                    when (clipboard) {
                        ClipboardChord.Copy -> copySelection()
                        ClipboardChord.Paste -> pasteFromClipboard()
                    }
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
                    if (bytes == "\t" && state.hasSuggestion) {
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
      ) {
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
            // Link (hand) cursor on Ctrl+hover: track the hovered cell and whether an openable link sits
            // under it. Independent of mouse-tracking mode — a local UI affordance, nothing is sent to
            // the app. Ctrl press/release is handled in onPreviewKeyEvent using the tracked hoverPos.
            .pointerInput(state, closed, metrics) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Exit -> { hoverPos = null; linkHover = false; hoveredPath = null }
                            PointerEventType.Move, PointerEventType.Enter -> {
                                if (event.buttons.areAnyPressed) { linkHover = false; hoveredPath = null; continue }
                                val change = event.changes.firstOrNull() ?: continue
                                val pos = posAt(change.position.x, change.position.y)
                                hoverPos = pos
                                updateHoverAffordance(pos, event.keyboardModifiers.isCtrlPressed)
                            }
                            else -> {}
                        }
                    }
                }
            }
            .pointerHoverIcon(if (linkHover) PointerIcon.Hand else PointerIcon.Default)
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
                        // Ctrl+click on a link — open the URI (don't start a selection). An OSC 8
                        // hyperlink wins; otherwise fall back to a bare http(s)/ftp URL detected in the
                        // row text. The URI comes from an untrusted server — open only safe web schemes,
                        // blocking file:/javascript:/anything else that could harm locally.
                        if (mods.isCtrlPressed) {
                            val uri = openableLinkAt(state.screen, pos.row, pos.col)
                            if (uri != null) {
                                runCatching { uriHandler.openUri(uri) }
                                down.consume()
                                return@awaitEachGesture
                            }
                            // No URL here: a bare file path opens in this session's file panel. The
                            // path is server-controlled text, so it goes to the SFTP pane (which
                            // only ever lists it), never to the platform URI handler.
                            val path = filePathUnderPos(pos)
                            if (path != null && onOpenPath != null) {
                                onOpenPath(path.uri)
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
      }

      // Cursor overlay over the text per the DECSCUSR shape. Block — fill the cell + redraw the char in
      // a contrasting color; Underline — a bar at the bottom; Bar — a vertical line on the left. Geometry
      // uses the same monospace metric as the text, offset by the scroll. The blink phase gates
      // the DRAW, not the composition: the canvas stays composed while the cursor exists, and the
      // 530ms toggle repaints only this overlay.
      if (cursorComposed && screen.isNotEmpty()) {
          val thickness = with(density) { 2.dp.toPx() }
          // A concealed cell (SGR 8) must stay unreadable under the block cursor too: the text
          // pass renders it transparent (TerminalGlyphs.toSpanStyle), and redrawing it here in
          // the contrast color would reveal the one character the host asked to hide.
          val glyph = screen.getOrNull(cursorRow)?.getOrNull(cursorCol)
              ?.takeUnless { it.style.hidden }?.text
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp)) {
              if (!blinkOn.value) return@Canvas
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
      // Same monospace geometry as the cursor; Tab (desktop) and the `tab` keycap (mobile) accept it.
      // It continues what the cursor row shows, so text and ghost always read as one command. In
      // alt-screen (vim/htop) there's no suggestion — [suggestionTail] is already cleared there.
      if (!closed && screen.isNotEmpty()) {
          Canvas(Modifier.fillMaxSize().padding(PADDING_DP.dp).clipToBounds()) {
              // Tail and cursor are read here, in the draw phase, rather than in composition: the tail
              // changes on each published snapshot and on Shift+Tab, and reading it above would
              // recompose the whole terminal to repaint one overlay. Reading the cursor alongside it
              // keeps both from the same snapshot even if one lands between composition and draw.
              val ghost = state.suggestionTail ?: return@Canvas
              val x = state.cursorCol * metrics.cellWidth
              val y = state.cursorRow * metrics.cellHeight - scroll.value.toFloat()
              drawGlyphText(measurer, ghost, Offset(x, y), ghostStyle)
          }
      }

      // Reverse history search overlay (Ctrl-R): a bottom panel with the current query and matches
      // (selected — cyan). Enter inserts, Esc closes, another Ctrl-R/arrows page through.
      val rsQuery = state.reverseSearch.query
      if (rsQuery != null && !closed) {
          val matches = state.reverseSearch.results
          val shown = matches.take(REVERSE_SEARCH_ROWS)
          Column(
              Modifier
                  .align(Alignment.BottomStart)
                  .fillMaxWidth()
                  .background(Skerry.colors.railBg.copy(alpha = 0.94f))
                  .padding(horizontal = 10.dp, vertical = 6.dp),
          ) {
              Text(
                  text = stringResource(Res.string.terminal_reverse_search_prompt, rsQuery),
                  style = textStyle.copy(color = Skerry.colors.dim),
              )
              if (shown.isEmpty()) {
                  Text(stringResource(Res.string.terminal_reverse_search_no_matches), style = textStyle.copy(color = Skerry.colors.faint))
              } else {
                  shown.forEachIndexed { i, cmd ->
                      val selected = i == state.reverseSearch.index.mod(matches.size.coerceAtLeast(1))
                      Text(
                          text = cmd,
                          maxLines = 1,
                          style = textStyle.copy(
                              color = if (selected) Skerry.colors.cyan else Skerry.colors.textMid,
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
      // Stood down while the search panel is open: two fields fighting over the soft keyboard would
      // send the query into the PTY.
      if (imeInput && !closed && !searchOpen) {
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
                      if (state.reverseSearch.query != null) {
                          for (ch in out) when (ch.code) {
                              127, 8 -> state.reverseSearch.backspace() // DEL / BS
                              13, 10 -> state.reverseSearch.accept() // CR / LF — accept
                              else -> if (ch.code >= 0x20) state.reverseSearch.append(ch.toString())
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

      // Sharing hint under the cursor (see [cursorOverlay]): positioned from the bottom edge, so it
      // tracks the line being typed into — the live screen keeps the cursor on one of the last rows,
      // and measuring from the top would need the scrollback offset the canvas owns.
      cursorOverlay?.let { slot ->
          val (caretX, caretY) = caretOffsetPx(
              cursorRow = state.cursorRow,
              cursorCol = state.cursorCol,
              snapshotRows = state.screen.size,
              metrics = metrics,
              scrollPx = scroll.value.toFloat(),
          )
          slot(
              Modifier.align(Alignment.TopStart).offset(
                  x = PADDING_DP.dp + with(density) { caretX.toDp() },
                  y = PADDING_DP.dp + with(density) { caretY.toDp() },
              ),
          )
      }

      // Find-in-output panel (⌘F / Ctrl+Shift+F, or the mobile keybar's magnifier). Pinned to the
      // pane's top edge over the terminal: on a phone it spans the width (its controls would not
      // fit otherwise), on desktop it hugs the right corner like a browser's find bar.
      if (searchOpen && !closed) {
          if (imeInput) {
              TerminalSearchBar(state, expand = true, modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(6.dp))
          } else {
              TerminalSearchBar(state, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
          }
      }

      // Transient "Copied" confirmation over the top of the terminal (right-click / Ctrl+Shift+C /
      // touch "Copy"). Same overlay slot and visual language as the DisconnectedBanner in TerminalView.
      CopiedBanner(copiedNonce, Modifier.align(Alignment.TopCenter))
    }
}
