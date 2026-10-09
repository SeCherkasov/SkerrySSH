package app.skerry.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalPos
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.render.SceneFrames
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@OptIn(ExperimentalComposeUiApi::class)
class TerminalRowDrawingCacheRenderTest {
    @Test
    fun scrollingReleasesRowsOutsideTheVisibleWindow() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ScriptedSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        var context: CountingContext? = null
        try {
            ImageComposeScene(width = 800, height = 500, density = Density(1f)).use { scene ->
                scene.setContent {
                    val original = LocalGraphicsContext.current
                    val tracked = androidx.compose.runtime.remember(original) { CountingContext(original) }
                    context = tracked
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalGraphicsContext provides tracked,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = false, output = false),
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) { TerminalScreen(state, Modifier.fillMaxSize(), fixedGrid = PtySize(80, 24)) }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(3)
                session.print(buildString {
                    append("\u001b[?25l")
                    repeat(160) { append("│ row $it 中文 ───│\r\n") }
                })
                frames.settle(6)
                assertTrue(state.historyRows > 100)
                val tracked = checkNotNull(context)
                val released = tracked.released
                repeat(12) {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(400f, 250f), scrollDelta = Offset(0f, -8f))
                    frames.settle(4)
                    assertTrue(tracked.created - tracked.released <= 28, "native storage must stay within the visible window")
                }
                assertTrue(tracked.released > released, "scrolling must actually leave previously visible rows")
            }
            val tracked = checkNotNull(context)
            assertEquals(tracked.created, tracked.released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun selectionReusesDrawingsAndContentAndThemeInvalidateThem() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ScriptedSession()
        val state = TerminalScreenState(session, scope, nowMillis = eagerPublishClock())
        val theme = mutableStateOf(TerminalThemes.NightSea)
        var context: CountingContext? = null
        try {
            ImageComposeScene(width = 800, height = 500, density = Density(1f)).use { scene ->
                scene.setContent {
                    val original = LocalGraphicsContext.current
                    val tracked = androidx.compose.runtime.remember(original) { CountingContext(original) }
                    context = tracked
                    SkerryTheme {
                        CompositionLocalProvider(
                            LocalGraphicsContext provides tracked,
                            LocalTerminalTheme provides theme.value,
                            LocalTerminalHighlight provides TerminalHighlight(commandLine = false, output = false),
                            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                        ) { TerminalScreen(state, Modifier.fillMaxSize(), fixedGrid = PtySize(80, 24)) }
                    }
                }
                val frames = SceneFrames(scene)
                frames.settle(3)
                session.print("\u001b[?25l│ hello 中文 e\u0301 ──│\r\nsecond\r\nthird")
                frames.settle(6)
                val records = rowGlyphRecordings
                val created = checkNotNull(context).created
                repeat(8) { index ->
                    state.beginSelection(TerminalPos(0, 0))
                    state.extendSelection(TerminalPos(1, index + 1))
                    frames.advance()
                }
                assertEquals(records, rowGlyphRecordings, "selection must reuse glyph drawing commands")
                repeat(8) { index ->
                    session.print("\u001b[3;1H\u001b[2Kupdate $index")
                    frames.settle(2)
                }
                assertEquals(records + 8, rowGlyphRecordings, "only the modified row must be recorded")
                assertEquals(created, checkNotNull(context).created, "streaming must reuse native layers")
                val beforeTheme = rowGlyphRecordings
                theme.value = TerminalThemes.all.first { it.id != TerminalThemes.NightSea.id }
                frames.awaitFrame("the new theme to invalidate cached drawings") { rowGlyphRecordings > beforeTheme }
                assertTrue(checkNotNull(context).released > 0, "obsolete theme layers must be released")
            }
            val tracked = checkNotNull(context)
            assertEquals(tracked.created, tracked.released, "disposing the terminal must release every layer")
        } finally {
            scope.cancel()
        }
    }

    private class CountingContext(private val delegate: GraphicsContext) : GraphicsContext by delegate {
        var created = 0
        var released = 0

        override fun createGraphicsLayer(): GraphicsLayer {
            created++
            return delegate.createGraphicsLayer()
        }

        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
            released++
            delegate.releaseGraphicsLayer(layer)
        }
    }
}
