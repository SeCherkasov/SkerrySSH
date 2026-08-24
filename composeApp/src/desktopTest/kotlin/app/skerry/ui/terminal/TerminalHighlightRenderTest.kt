package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.render.SceneFrames
import app.skerry.ui.theme.SkerryTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Renders a live [TerminalScreen] offscreen and checks that syntax highlighting reaches the glyphs:
 * a typed command turns the theme's green, the switch actually turns it off, and a color the server
 * chose itself is never overpainted. The categorization is covered by the tokenizer's own tests;
 * this is the draw path.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalHighlightRenderTest {

    /** Fake PTY session: output only, input/resize are no-ops. */
    private class FakeSession : TerminalSession {
        private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
        override val state: StateFlow<TerminalState> = _state.asStateFlow()
        private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val output: Flow<ByteArray> = _output.asSharedFlow()
        override suspend fun send(data: ByteArray) {}
        override suspend fun resize(size: PtySize) {}
        override suspend fun close() {}
        fun emit(text: String) {
            check(_output.tryEmit(text.encodeToByteArray())) { "output buffer overflow" }
        }

        /** Transport drop: the session reports Closed, the way a dead connection does. */
        fun die() {
            _state.value = TerminalState.Closed()
        }
    }

    private val theme = TerminalThemes.NightSea

    /** Frames spent on layout before the first emit — nothing is asserted about them. */
    private val layoutFrames = 3

    /**
     * Frames a claim about what must NOT be on screen is given. A negative has no condition to wait
     * for, so the budget is all there is — and these scenes feed and resize synchronously
     * ([Dispatchers.Unconfined]), which is what makes a budget honest here and not on a scene whose
     * work runs off the frame clock (issue #330).
     */
    private val settleFrames = 6

    /** The cursor's blink half-period: the waits about its phase assert that period by name. */
    private val blinkBudgetMs = 2_500L

    /** Runs [body] against a rendered terminal with the given highlight settings. */
    private fun withScreen(
        highlight: TerminalHighlight,
        body: (session: FakeSession, frames: SceneFrames) -> Unit,
    ) {
        // Unconfined: feed/resize run synchronously at the emit point, making frames deterministic.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 420, height = 240, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides theme,
                            LocalTerminalHighlight provides highlight,
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) {
                            TerminalScreen(state, Modifier.fillMaxSize())
                        }
                    }
                }
                val frames = SceneFrames(scene)
                // Let layout settle (resize -> sized=true) before emitting.
                frames.settle(layoutFrames)
                body(session, frames)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun typedCommandIsPaintedInTheThemeGreen() {
        withScreen(TerminalHighlight(commandLine = true, output = false)) { session, frames ->
            session.emit("user@host:~$ git status")
            frames.awaitFrame("a known command to be drawn in the theme's green") {
                it.hasColorNear(theme.ansi[2].toArgb())
            }
        }
    }

    @Test
    fun theSwitchTurnsHighlightingOff() {
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frames ->
            session.emit("user@host:~$ git status")
            assertFalse(
                frames.settle(settleFrames).hasColorNear(theme.ansi[2].toArgb()),
                "nothing may be recolored while the switch is off",
            )
        }
    }

    @Test
    fun outputLevelsArePaintedOnlyWhenAskedFor() {
        withScreen(TerminalHighlight(commandLine = false, output = true)) { session, frames ->
            session.emit("\r\nERROR failed to bind\r\n")
            frames.awaitFrame("a log level to be drawn in the theme's red") {
                it.hasColorNear(theme.ansi[1].toArgb())
            }
        }
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frames ->
            session.emit("\r\nERROR failed to bind\r\n")
            assertFalse(
                frames.settle(settleFrames).hasColorNear(theme.ansi[1].toArgb()),
                "output stays plain while the switch is off",
            )
        }
    }

    @Test
    fun theServerWinsTheArgumentAboutColor() {
        withScreen(TerminalHighlight(commandLine = true, output = true)) { session, frames ->
            // The server prints ERROR in its own green; the client's rule would make it red.
            session.emit("\r\n\u001b[32mERROR failed to bind\u001b[0m\r\n")
            val pixels = frames.awaitFrame("the server's own green to be drawn") {
                it.hasColorNear(theme.ansi[2].toArgb())
            }
            // The same frame, not a later one: the claim is about what the server's green was drawn with.
            assertFalse(pixels.hasColorNear(theme.ansi[1].toArgb()), "an already-colored cell must not be repainted")
        }
    }

    @Test
    fun anExecutedCommandKeepsItsColorAfterEnter() {
        // The regression a user hit: the command went plain the moment it ran. The state is driven
        // through typeInput so the executed-command set is populated the way it is in a session.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 420, height = 240, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides theme,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = true, output = false),
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) {
                            TerminalScreen(state, Modifier.fillMaxSize())
                        }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(layoutFrames)

                session.emit("user@host:~$ ")
                state.typeInput("git status")
                session.emit("git status")
                state.typeInput("\r")
                // The shell echoes the newline and draws the next prompt; the command is now history.
                session.emit("\r\nuser@host:~$ ")

                frames.awaitFrame("the executed command to stay green once the cursor moves to the next prompt") {
                    it.hasColorNear(theme.ansi[2].toArgb())
                }
            }
        } finally {
            scope.cancel()
        }
    }

    // NightSea's cursor #2BBDEE happens to equal ansi[6] (cyan) - the cursor-presence probes below
    // are sound only because these scenarios emit plain uncolored text; re-verify if extending.
    // The cell-0 interior region is tied to the defaults these scenes run under: 13px font, 18px
    // line height, PADDING_DP=14, Density(1f) - re-derive on change; a drifted region fails
    // loudly on the sampled-while-lit / drag-painted gates, never silently.
    private val cell0InteriorX = 16..20
    private val cell0InteriorY = 18..28

    /** Cell 0 including the underline band at the row's bottom edge. */
    private val cell0WithUnderlineY = 16..31

    // Row 0's link-underline band under the 19 cells of "https://example.com". The link cyan
    // equals theme.cursor, so the probe stays inside this band - the tests park the blink cursor
    // on row 2, clear of it.
    private val urlUnderlineX = 16..160
    private val urlUnderlineY = 29..31

    /** Row 0's underline band under the 4 cells of the OSC 8 anchor text "link". */
    private val hyperlinkBandX = 16..44

    /** LINK_UNDERLINE_STYLE's own color - carried by the style, not by the cell's text color. */
    private val linkCyan = Color(0xFF2BBDEE).toArgb()

    /** What a selected blank cell reads as on screen: the half-alpha wash over the background. */
    private val selectionWash = theme.selection.compositeOver(theme.background).toArgb()

    @Test
    fun cursorBlinkRepaintsWithoutRecomposingTheScreen() {
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frames ->
            session.emit("x")
            // Two phases: the blink coroutine delays on the wall clock, not on the render clock.
            frames.awaitFrame("the cursor to become visible", timeoutMillis = blinkBudgetMs) {
                it.hasColorNear(theme.cursor.toArgb())
            }
            val compositions = terminalScreenCompositions
            frames.awaitFrame("the cursor to blink off", timeoutMillis = blinkBudgetMs) {
                !it.hasColorNear(theme.cursor.toArgb())
            }
            assertEquals(
                compositions, terminalScreenCompositions,
                "a blink toggle must repaint the overlay, not recompose the screen",
            )
        }
    }

    @Test
    fun aSteadyCursorNeverBlanks() {
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frames ->
            // DECSCUSR 2 = steady block; the effect's early-return must pin the phase on.
            session.emit("${'\u001b'}[2 qx")
            frames.awaitFrame("the steady cursor to be drawn", timeoutMillis = blinkBudgetMs) {
                it.hasColorNear(theme.cursor.toArgb())
            }
            // Longer than two half-periods: a blink regression would blank at least once. A period
            // is exactly what this asserts, so the frames are counted on purpose.
            repeat(13) {
                Thread.sleep(100)
                assertTrue(frames.next().hasColorNear(theme.cursor.toArgb()), "a steady cursor must never blank")
            }
        }
    }

    @Test
    fun aClosedSessionHidesTheCursor() {
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frames ->
            session.emit("x")
            frames.awaitFrame("the cursor to become visible first", timeoutMillis = blinkBudgetMs) {
                it.hasColorNear(theme.cursor.toArgb())
            }
            session.die()
            // One frame for the composition to drop the overlay, then samples across more than one full
            // blink period (2 x 530 ms). Deliberately NOT preceded by a wait for the cursor to go: such a
            // wait returns at the start of an OFF phase, and every sample after it would land inside that
            // same phase — a cursor still blinking over a dead session would go unnoticed.
            frames.next()
            repeat(13) {
                Thread.sleep(100)
                assertFalse(frames.next().hasColorNear(theme.cursor.toArgb()), "a dead session must not draw a cursor")
            }
        }
    }

    @Test
    fun concealedTextStaysHiddenUnderTheSelectionWash() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 420, height = 240, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides theme,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = false, output = false),
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) {
                            TerminalScreen(state, Modifier.fillMaxSize())
                        }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(layoutFrames)
                // Concealed AND dim X at cell 0 (SGR 2;8 - the combination where a naive alpha
                // override repainted the secret in 60% black); cursor sits at cell 1, clear of
                // the probed region.
                session.emit("${'\u001b'}[2;8mX")
                frames.settle(settleFrames)
                // Select across the row: the wash paints under the glyph pass, and hidden strokes
                // of any color read through it as non-uniformity.
                scene.sendPointerEvent(PointerEventType.Press, Offset(16f, 20f))
                scene.sendPointerEvent(PointerEventType.Move, Offset(120f, 24f))
                // Guard against a vacuous pass: the wash color itself must be present on blank
                // cells clear of the cursor cell - a frame delta on the probed row would also be
                // satisfied by a mere cursor blink.
                val after = frames.awaitFrame("the drag to visibly paint the selection wash") {
                    it.regionHasColor(selectionWash, 60..110, cell0InteriorY)
                }
                assertTrue(
                    after.regionIsUniform(cell0InteriorX, cell0InteriorY, tolerance = 8),
                    "the concealed cell under the wash must be indistinguishable from blank cells",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concealedTextDrawsNoUnderline() {
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frames ->
            // SGR 8;4: concealed and underlined. The underline would trace the position and run
            // length of the hidden text - the whole cell, band included, must stay blank.
            session.emit("${'\u001b'}[8;4mX")
            assertTrue(
                frames.settle(settleFrames).regionIsUniform(cell0InteriorX, cell0WithUnderlineY, tolerance = 8),
                "a concealed cell must not draw its underline",
            )
        }
    }

    @Test
    fun concealedUrlDrawsNoLinkUnderline() {
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frames ->
            // Visible URL first: pins the probe region to where the link underline actually lands,
            // so a drifted band cannot make the concealed assertion below pass vacuously.
            session.emit("https://example.com\r\n\r\n")
            // Tolerance 90: the ~1.3px line antialiases against the near-black background, so no
            // pixel reads as pure cyan (the strongest measures ~#1F8BB0, blue off by 62); 90 still
            // cannot match the background itself (green differs by 175) or the foreground (red
            // differs by 187).
            frames.awaitFrame("a visible bare URL to get the link underline") {
                it.regionHasColor(linkCyan, urlUnderlineX, urlUnderlineY, tolerance = 90)
            }
            // The same URL concealed (SGR 8): LINK_UNDERLINE_STYLE carries its own color and
            // hidden=false, so underlineDrawColor's hidden gate never sees it - the link passes
            // themselves must skip concealed cells, or the underline traces the hidden run.
            session.emit("${'\u001b'}[2J${'\u001b'}[H${'\u001b'}[8mhttps://example.com${'\u001b'}[0m\r\n\r\n")
            assertFalse(
                // The same wide tolerance makes this STRICTER: even a faint antialiased trace
                // of the underline fails it.
                frames.settle(settleFrames).regionHasColor(linkCyan, urlUnderlineX, urlUnderlineY, tolerance = 90),
                "a concealed URL must not draw the link underline",
            )
        }
    }

    @Test
    fun concealedHyperlinkDrawsNoLinkUnderline() {
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frames ->
            // Visible OSC 8 hyperlink first: pins the probe band for pass 4, a structurally
            // separate loop (keyed on the cell's hyperlink) from the bare-URL pass 5 that
            // concealedUrlDrawsNoLinkUnderline covers.
            session.emit("\u001b]8;;https://example.com\u0007link\u001b]8;;\u0007\r\n\r\n")
            frames.awaitFrame("a visible OSC 8 hyperlink to get the link underline") {
                it.regionHasColor(linkCyan, hyperlinkBandX, urlUnderlineY, tolerance = 90)
            }
            // The same hyperlink concealed (SGR 8): the span filter that protects bare URLs never
            // sees OSC 8 cells, so pass 4 needs its own hidden gate - and its own test.
            session.emit(
                "\u001b[2J\u001b[H\u001b]8;;https://example.com\u0007\u001b[8mlink\u001b[0m\u001b]8;;\u0007\r\n\r\n",
            )
            assertFalse(
                frames.settle(settleFrames).regionHasColor(linkCyan, hyperlinkBandX, urlUnderlineY, tolerance = 90),
                "a concealed OSC 8 hyperlink must not draw the link underline",
            )
        }
    }

    @Test
    fun concealedTextStaysHiddenUnderTheBlockCursor() {
        withScreen(TerminalHighlight(commandLine = false, output = false)) { session, frames ->
            // SGR 8 conceals X; CUB puts the block cursor onto it. The text pass renders the
            // glyph transparent (TerminalGlyphs.toSpanStyle); the cursor overlay must not repaint
            // it readable in the contrast color.
            session.emit("${'\u001b'}[8mX${'\u001b'}[D")
            // The sample has to be taken while the block is lit, which is a phase of the wall-clock
            // blink — so the wait is for that phase, and never for a number of frames to go by.
            val lit = frames.awaitFrame("the block cursor to be lit over the concealed cell", timeoutMillis = blinkBudgetMs) {
                it.regionHasColor(theme.cursor.toArgb(), cell0InteriorX, cell0InteriorY)
            }
            // A solid block: any glyph stroke inside the interior (antialiased or not) breaks the
            // uniform cursor fill and reveals the concealed character.
            assertTrue(
                lit.regionAllNearColor(theme.cursor.toArgb(), cell0InteriorX, cell0InteriorY, tolerance = 40),
                "a concealed glyph must not be redrawn readable under the cursor",
            )
        }
    }

    @Test
    fun linkScanStaysCachedAcrossSelectionRepaints() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 420, height = 240, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides theme,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = false, output = false),
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) {
                            TerminalScreen(state, Modifier.fillMaxSize())
                        }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(layoutFrames)
                val scansBefore = linkScanPasses
                session.emit("see https://example.com")
                frames.awaitFrame("the hoisted link scan to run over the new content") { linkScanPasses > scansBefore }
                val passes = linkScanPasses

                // A selection drag repaints the canvas on every move; the scan keys on content and
                // window, not on frames - computed in the draw phase it would rerun per repaint.
                scene.sendPointerEvent(PointerEventType.Press, Offset(40f, 20f))
                scene.sendPointerEvent(PointerEventType.Move, Offset(60f, 24f))
                // Guard against a vacuous pass: the wash color must be present on the blank cell 3
                // inside the dragged range - a frame delta would also be satisfied by cursor blink.
                frames.awaitFrame("the drag to visibly paint the selection wash") {
                    it.regionHasColor(selectionWash, 39..44, cell0InteriorY)
                }
                repeat(5) { step ->
                    scene.sendPointerEvent(PointerEventType.Move, Offset(80f + step * 20f, 24f))
                    frames.next()
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(180f, 24f))
                frames.next()
                assertEquals(passes, linkScanPasses, "selection repaints must not rescan links")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun glyphRunsStayCachedAcrossSelectionRepaints() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 420, height = 240, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides theme,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = false, output = false),
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) {
                            TerminalScreen(state, Modifier.fillMaxSize())
                        }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(layoutFrames)
                val runsBefore = glyphRunSegmentations
                session.emit("hello world")
                frames.awaitFrame("the draw pass to segment the new row into runs") {
                    glyphRunSegmentations > runsBefore
                }
                val segmentations = glyphRunSegmentations

                // A selection drag repaints the canvas per move; rows and highlights are unchanged,
                // so every repaint must reuse the cached runs instead of re-segmenting the window.
                scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 20f))
                scene.sendPointerEvent(PointerEventType.Move, Offset(60f, 24f))
                frames.awaitFrame("the drag to visibly paint the selection wash") {
                    it.regionHasColor(selectionWash, 16..20, cell0InteriorY)
                }
                repeat(5) { step ->
                    scene.sendPointerEvent(PointerEventType.Move, Offset(80f + step * 20f, 24f))
                    frames.next()
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(180f, 24f))
                frames.next()
                assertEquals(segmentations, glyphRunSegmentations, "selection repaints must not re-segment rows")

                // New content really does re-segment.
                session.emit("!")
                frames.awaitFrame("new content to re-segment its rows") { glyphRunSegmentations > segmentations }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun duplicateHighlightedRowsStayCachedAcrossRepaints() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = FakeSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        try {
            ImageComposeScene(width = 420, height = 240, density = Density(1f)).use { scene ->
                scene.setContent {
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalTerminalTheme provides theme,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = false, output = true),
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) {
                            TerminalScreen(state, Modifier.fillMaxSize())
                        }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(layoutFrames)
                // Two content-equal rows carry two DISTINCT RowHighlight instances - a
                // content-keyed cache aliases them into one entry and the identity check misses
                // alternately, re-segmenting both rows on every repaint. Retry-loop logs are
                // exactly this shape. An idle frame does not re-execute the draw lambda, so the
                // repaints are forced with a selection drag (wash-guarded against a no-op).
                val runsBefore = glyphRunSegmentations
                session.emit("ERROR connection lost\r\nERROR connection lost\r\n")
                frames.awaitFrame("the duplicate rows to be segmented") { glyphRunSegmentations > runsBefore }
                val segmentations = glyphRunSegmentations
                scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 60f))
                scene.sendPointerEvent(PointerEventType.Move, Offset(60f, 64f))
                // Probe columns 3..5 of the dragged row: the cursor block sits on column 0 and
                // would read as cursor color, not wash.
                frames.awaitFrame("the drag to visibly paint the selection wash") {
                    it.regionHasColor(selectionWash, 40..56, 54..64)
                }
                repeat(4) { step ->
                    scene.sendPointerEvent(PointerEventType.Move, Offset(80f + step * 20f, 64f))
                    frames.next()
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(160f, 64f))
                frames.next()
                assertEquals(
                    segmentations, glyphRunSegmentations,
                    "repaints of duplicate highlighted rows must not re-segment",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aHighlightChangeResegmentsItsRows() {
        withScreen(TerminalHighlight(commandLine = true, output = false)) { session, frames ->
            val runsBefore = glyphRunSegmentations
            session.emit("$ ls -la")
            frames.awaitFrame("the live line to be segmented") { glyphRunSegmentations > runsBefore }
            val segmentations = glyphRunSegmentations
            // A cursor move through the live line rebuilds that row's RowHighlight instance; the
            // run cache must treat it as a miss - runs bake the highlight kinds in.
            session.emit("\u001b[D")
            frames.awaitFrame("a rebuilt highlight to re-segment its row") {
                glyphRunSegmentations > segmentations
            }
        }
    }

    @Test
    fun rewritingTheLineUnderAStationaryCursorRecomputesTheOverlay() {
        withScreen(TerminalHighlight(commandLine = true, output = true)) { session, frames ->
            // Both lines are 8 cells, so after the rewrite the cursor lands on the same (row, col)
            // and the background map is empty both times (no output markers, nothing executed) -
            // the exact shape where keying the overlay on the background map alone kept stale
            // spans painted over the new text.
            val overlaysBefore = liveOverlayPasses
            session.emit("$ ls -la")
            frames.awaitFrame("the live overlay to run over the typed line") { liveOverlayPasses > overlaysBefore }
            val passes = liveOverlayPasses

            session.emit("\r$ git st")
            frames.awaitFrame(
                "an in-place rewrite under a stationary cursor to recompute the live overlay",
            ) { liveOverlayPasses > passes }
        }
    }

    @Test
    fun cursorMovesDoNotRescanTheBackgroundPass() {
        withScreen(TerminalHighlight(commandLine = true, output = true)) { session, frames ->
            val scansBefore = backgroundHighlightPasses
            session.emit("\r\nERROR failed to bind\r\nuser@host:~$ echo hi")
            frames.awaitFrame("the background pass to scan the emitted window") {
                backgroundHighlightPasses > scansBefore
            }
            val passes = backgroundHighlightPasses

            // Pure cursor motion over the live line: the whole-window output/executed scan must
            // stay cached - only the small live-command overlay may recompute. Nothing may happen,
            // so this one is a budget: frames in which the count must not move.
            session.emit("\u001b[D")
            frames.settle(settleFrames)
            assertEquals(passes, backgroundHighlightPasses, "a cursor move must not rescan the window")

            // Real content invalidates the background pass.
            session.emit("x")
            frames.awaitFrame("new content to rescan the window") { backgroundHighlightPasses > passes }
        }
    }

    /** Whether every pixel inside the region matches the region's own first pixel within [tolerance]. */
    private fun PixelMap.regionIsUniform(xs: IntRange, ys: IntRange, tolerance: Int): Boolean {
        val ref = this[xs.first, ys.first].toArgb()
        for (y in ys) {
            for (x in xs) {
                if (x < width && y < height && !matches(this[x, y].toArgb(), ref, tolerance)) return false
            }
        }
        return true
    }

    /** Whether every pixel inside the region matches [argb] within [tolerance]. */
    private fun PixelMap.regionAllNearColor(argb: Int, xs: IntRange, ys: IntRange, tolerance: Int): Boolean {
        for (y in ys) {
            for (x in xs) {
                if (x < width && y < height && !matches(this[x, y].toArgb(), argb, tolerance)) return false
            }
        }
        return true
    }

    /** Whether any pixel inside the region matches [argb] within [tolerance]. */
    private fun PixelMap.regionHasColor(argb: Int, xs: IntRange, ys: IntRange, tolerance: Int = 2): Boolean {
        for (y in ys) {
            for (x in xs) {
                if (x < width && y < height && matches(this[x, y].toArgb(), argb, tolerance)) return true
            }
        }
        return false
    }

    /** Whether opaque pixel [argb] matches [target] within [tolerance] on every channel. */
    private fun matches(argb: Int, target: Int, tolerance: Int): Boolean {
        if ((argb ushr 24) != 0xFF) return false
        for (shift in intArrayOf(16, 8, 0)) {
            if (abs((argb shr shift and 0xFF) - (target shr shift and 0xFF)) > tolerance) return false
        }
        return true
    }

    /** Whether any pixel is within [tolerance] per channel of [argb] (antialiasing shifts edges). */
    private fun PixelMap.hasColorNear(argb: Int, tolerance: Int = 2): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matches(this[x, y].toArgb(), argb, tolerance)) return true
            }
        }
        return false
    }
}
