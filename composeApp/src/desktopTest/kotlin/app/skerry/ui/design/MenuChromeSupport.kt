package app.skerry.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.skerry.ui.theme.SkerryTheme
import app.skerry.ui.theme.ThemeMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reading a rendered menu panel out of its pixels, shared by every test in this package that has to
 * tell one panel from another — [MenuChromeTest] and [TextContextMenuTest]. A menu is a popup, and a
 * popup's own bounds are larger than the panel inside it, so none of this can be a fixed coordinate.
 */

/** The theme, the fonts and the room a popup needs — every menu under test is rendered in this. */
@Composable
internal fun MenuScene(content: @Composable () -> Unit) {
    // Daybreak rather than the default: on Night Sea the panel line and the field-border tint are
    // literally the same colour, and a menu drawn with the wrong one would look right.
    SkerryTheme(ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
        ) {
            Box(Modifier.size(SCENE, SCENE)) { content() }
        }
    }
}

/**
 * What a panel is made of, at the granularity a menu can drift on: the fill, the border line over
 * it, and how much of the top row the rounded corners eat — which is twice the corner radius, and
 * the only part of a radius a flat scan can see.
 */
internal data class PanelChrome(val fill: Color, val border: Color, val cornerInset: Int)

/** [PanelChrome] of a bare [MenuPanel] — what every menu in the app has to match. */
@OptIn(ExperimentalTestApi::class)
internal fun referenceChrome(): PanelChrome {
    lateinit var chrome: PanelChrome
    runComposeUiTest {
        setContent {
            MenuScene {
                MenuPanel(Modifier.testTag(REFERENCE)) { MenuItem("Reference") {} }
            }
        }
        waitForIdle()
        chrome = chromeOf(onNodeWithTag(REFERENCE).captureToImage().toPixelMap())
    }
    return chrome
}

/** The one popup on screen, as pixels. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.popupPixels(): PixelMap = onNode(isPopup()).captureToImage().toPixelMap()

/**
 * Every clickable row of the open popup spans the panel, less the 4dp of air the panel keeps around
 * them — the property that makes a short label's click target as wide as a long one's.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertRowsFillThePanel(pixels: PixelMap, density: Density, expected: Int) {
    val rows = onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).widths()
    assertEquals(expected, rows.size, "the menu did not draw its $expected rows")
    val room = panelWidth(pixels) - 2 * with(density) { MENU_PADDING.roundToPx() }
    // A pixel of slack: the row's width comes from the layout and the panel's from a pixel scan, so
    // a panel that measures to a fraction of a dp rounds the two apart without anything being wrong.
    rows.forEach {
        assertTrue(abs(room - it) <= 1, "a row is ${it}px wide inside a ${room}px panel")
    }
}

/** The width of every node in this collection, rounded to whole pixels of the root. */
@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteractionCollection.widths(): List<Int> =
    fetchSemanticsNodes().map { it.boundsInRoot.width.toInt() }

/**
 * Reads the panel out of a capture, by the one thing a panel is and nothing else in the frame is: a
 * row of pixels opaque from edge to edge. The rest of a popup's own bounds is transparent — a menu
 * is offset below the bar it hangs from — and shows whatever is behind it, which is why the scan
 * cannot simply take the first row with a pixel in it.
 */
internal fun chromeOf(pixels: PixelMap): PanelChrome {
    val rows = panelRows(pixels)
    val top = rows.firstOrNull { it.isNotEmpty() } ?: fail("the menu drew no panel — it never opened")
    return PanelChrome(
        fill = commonest(rows.flatten()),
        border = commonest(top),
        cornerInset = panelWidth(pixels) - top.size,
    )
}

/** How wide the panel drew, in pixels: its widest run of opaque colour. */
internal fun panelWidth(pixels: PixelMap): Int =
    (0 until pixels.height).maxOfOrNull { widestRun(pixels, it) { pixel -> pixel.alpha == 1f } }
        ?: fail("the menu drew no panel — it never opened")

/**
 * The rows the panel itself drew, as their opaque runs. Half the panel's width is the cut: wide
 * enough that no glyph showing through a popup's transparent margin can reach it, narrow enough
 * that the top border — shortened by the two rounded corners — still counts.
 */
private fun panelRows(pixels: PixelMap): List<List<Color>> {
    val width = panelWidth(pixels)
    return (0 until pixels.height)
        .map { y -> longestOpaqueRun(pixels, y) }
        .filter { it.size * 2 >= width }
}

private fun longestOpaqueRun(pixels: PixelMap, y: Int): List<Color> {
    var best = emptyList<Color>()
    var run = mutableListOf<Color>()
    for (x in 0 until pixels.width) {
        val pixel = pixels[x, y]
        if (pixel.alpha == 1f) run += pixel else run = mutableListOf()
        if (run.size > best.size) best = run.toList()
    }
    return best
}

/** Length of the longest unbroken run of pixels [matches] accepts, in row [y]. */
internal fun widestRun(pixels: PixelMap, y: Int, matches: (Color) -> Boolean): Int {
    var best = 0
    var run = 0
    for (x in 0 until pixels.width) {
        run = if (matches(pixels[x, y])) run + 1 else 0
        best = maxOf(best, run)
    }
    return best
}

internal fun opaquePixels(pixels: PixelMap): List<Color> =
    (0 until pixels.height).flatMap { y ->
        (0 until pixels.width).map { x -> pixels[x, y] }.filter { it.alpha == 1f }
    }

/** The colour a run of pixels is mostly made of — the glyphs drawn on it are never the majority. */
internal fun commonest(colors: List<Color>): Color =
    colors.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        ?: fail("the menu drew no panel — it never opened")

private const val REFERENCE = "menu-panel-reference"

/** The floor [MenuItem] and [MenuActionRow] carry, plus the panel's own air on both sides. */
internal val MENU_FLOOR: Dp = MENU_MIN_WIDTH + MENU_PADDING * 2f

internal val SCENE: Dp = 600.dp
