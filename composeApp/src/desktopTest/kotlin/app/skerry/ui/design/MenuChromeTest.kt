package app.skerry.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.skerry.shared.host.Host
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_more_actions
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.host.hostCatalogOf
import app.skerry.ui.remote.MenuRow
import app.skerry.ui.remote.REMOTE_MENU_WIDTH
import app.skerry.ui.remote.RemoteMenuHost
import app.skerry.ui.remote.belowAnchor
import app.skerry.ui.terminal.OVERFLOW_MENU_WIDTH
import app.skerry.ui.terminal.PANE_PICKER_HEIGHT
import app.skerry.ui.terminal.PANE_PICKER_WIDTH
import app.skerry.ui.terminal.OverflowActionsButton
import app.skerry.ui.terminal.PaneHostPicker
import app.skerry.ui.terminal.PaneMenu
import app.skerry.ui.terminal.ToolbarAction
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every menu the app pops up is the same object, so it has to be painted by the same code: fill,
 * border and corner all come from [MenuPanel]. Four drew their own panel instead — the pane header's
 * menu and its host picker, the work bar's overflow, and the ones a remote desktop hangs off its bar
 * — and had drifted onto the field-border tint (`cyan14`), with the remote one on its own surface
 * and corner as well.
 *
 * Rendered on Daybreak on purpose. On Night Sea `lineStrong` and `cyan14` are literally the same
 * colour, so the drift is invisible on the default theme and plain on each of the other eight.
 *
 * The reference is a real [MenuPanel] rendered the same way rather than a colour written down here:
 * the border is an alpha line over the panel fill, and re-deriving that blend in the test would
 * assert the arithmetic instead of the pixels.
 *
 * Each menu is also measured, not only sampled: the chrome is the same whatever width a panel comes
 * out at, so a colour comparison alone would let a menu spread across the window (a fixed width
 * dropped) or collapse onto its text and still pass.
 */
@OptIn(ExperimentalTestApi::class)
class MenuChromeTest {

    /** Two rows of different length: the panel measures itself, and both rows fill what it measured. */
    @Test
    fun `the pane header's menu wears the shared panel`() {
        val expected = referenceChrome()
        runComposeUiTest {
            var density = Density(1f)
            setContent {
                density = LocalDensity.current
                MenuScene { PaneMenu(onDismiss = {}, onChangeHost = {}, onClose = {}) }
            }
            waitForIdle()
            val pixels = popupPixels()
            assertEquals(expected, chromeOf(pixels), "the pane menu is not drawn on the shared panel")
            val measured = panelWidth(pixels)
            assertTrue(
                measured >= with(density) { MENU_FLOOR.roundToPx() },
                "the pane menu measured ${measured}px, below the floor its rows carry",
            )
            // The upper bound is the half of the assertion that means anything: a popup measures its
            // content against the window, so a panel that stopped sizing itself would open as a bar
            // across the whole scene and still clear the floor.
            assertTrue(
                measured < with(density) { (SCENE / 2).roundToPx() },
                "the pane menu measured ${measured}px — it filled the popup instead of its rows",
            )
            assertRowsFillThePanel(pixels, density, expected = 2)
        }
    }

    /**
     * Both row shapes give a short label the whole row to be clicked on. Asserted with labels that
     * straddle the width floor: below it every row comes out at the floor whether it fills the panel
     * or not, so a menu of short verbs cannot tell the two apart.
     */
    @Test
    fun `a menu's rows are as wide as the menu`() {
        runComposeUiTest {
            var density = Density(1f)
            setContent {
                density = LocalDensity.current
                MenuScene {
                    Box(Modifier.testTag(ROWS)) {
                        MenuPanel {
                            MenuItem("Edit") {}
                            MenuItem("Duplicate this host and everything on it") {}
                            MenuActionRow("close", "Close") {}
                        }
                    }
                }
            }
            waitForIdle()
            val pixels = onNodeWithTag(ROWS).captureToImage().toPixelMap()
            val room = panelWidth(pixels) - 2 * with(density) { MENU_PADDING.roundToPx() }
            val rows = onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot.width.toInt() }
            assertEquals(3, rows.size, "the panel did not draw its three rows")
            rows.forEach {
                assertTrue(abs(room - it) <= 1, "a row is ${it}px wide inside a ${room}px panel")
            }
        }
    }

    /**
     * Fixed width, and the test says so: which actions land in this menu depends on how narrow the
     * window is, so a panel that measured itself would open at a different width from one resize to
     * the next — the drift [MenuPanel]'s own width floor exists to bound.
     */
    @Test
    fun `the work bar's overflow menu wears the shared panel`() {
        val expected = referenceChrome()
        runComposeUiTest {
            var density = Density(1f)
            setContent {
                density = LocalDensity.current
                MenuScene {
                    // Anchored at the scene's right edge, as it is in the work bar: the popup opens
                    // to the left of its button, and from the top-left corner it would land off the
                    // scene entirely.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                        OverflowActionsButton(
                            hidden = setOf(ToolbarAction.Files, ToolbarAction.Snippets),
                            state = DesktopDesignState(),
                            tabKey = null,
                            onOpenSftp = {},
                            onOpenMonitor = {},
                        )
                    }
                }
            }
            // By semantics rather than by pointer: a mouse click leaves the pointer on the button,
            // which keeps it hovered and its tooltip — a popup of its own — on screen beside the
            // menu, and then there is no single popup to capture.
            onNodeWithContentDescription(string(Res.string.shell_tip_more_actions))
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            val pixels = popupPixels()
            assertEquals(expected, chromeOf(pixels), "the overflow menu is not drawn on the shared panel")
            assertEquals(
                with(density) { OVERFLOW_MENU_WIDTH.roundToPx() },
                panelWidth(pixels),
                "the overflow menu is not the width it fixes itself at",
            )
            // Two labels of different length in a panel wider than either: a row that sized itself
            // to its own text would leave the rest of the row dead to a click.
            assertRowsFillThePanel(pixels, density, expected = 2)
        }
    }

    /**
     * Opened from the same pane header as the menu above, one click to its left — two panels of the
     * same object, so a border they disagree on is visible without even moving the mouse. Fed a
     * catalog taller than the panel may grow, since the height cap and its scroll are the reason
     * this one is not measured like a menu.
     */
    @Test
    fun `the pane header's host picker wears the shared panel`() {
        val expected = referenceChrome()
        runComposeUiTest {
            var density = Density(1f)
            setContent {
                density = LocalDensity.current
                MenuScene {
                    CompositionLocalProvider(LocalHosts provides remember { catalogOf(30) }) {
                        Box(Modifier.testTag(PICKER)) { PaneHostPicker(onPick = {}) }
                    }
                }
            }
            waitForIdle()
            val pixels = onNodeWithTag(PICKER).captureToImage().toPixelMap()
            assertEquals(expected, chromeOf(pixels), "the host picker is not drawn on the shared panel")
            assertEquals(
                with(density) { PANE_PICKER_WIDTH.roundToPx() },
                panelWidth(pixels),
                "the host picker is not the width it fixes itself at",
            )
            // The cap plus the panel's 4dp of air above and below the scrolling list.
            val cap = with(density) { (PANE_PICKER_HEIGHT + 8.dp).roundToPx() }
            assertTrue(
                pixels.height <= cap,
                "a 30-host catalog grew the picker to ${pixels.height}px — the list is not scrolling inside the panel",
            )
        }
    }

    @Test
    fun `a remote desktop's menu wears the shared panel`() {
        val expected = referenceChrome()
        runComposeUiTest {
            var density = Density(1f)
            setContent {
                density = LocalDensity.current
                MenuScene {
                    RemoteMenuHost(
                        expanded = true,
                        onDismiss = {},
                        onToggle = {},
                        position = belowAnchor(gap = 0),
                        trigger = {},
                    ) {
                        Txt("Fit to window")
                    }
                }
            }
            waitForIdle()
            val pixels = popupPixels()
            assertEquals(expected, chromeOf(pixels), "the remote desktop menu is not drawn on the shared panel")
            assertEquals(
                with(density) { REMOTE_MENU_WIDTH.roundToPx() },
                panelWidth(pixels),
                "the remote desktop menu is not the width it fixes itself at",
            )
        }
    }

    /**
     * The panel insets its rows now, which a full-bleed highlight was not drawn for: the picked
     * quality would paint a square-cornered bar with the panel's rounded corner showing beside it.
     */
    @Test
    fun `a picked remote-desktop row is inset and rounded inside the panel`() {
        runComposeUiTest {
            var density = Density(1f)
            setContent {
                density = LocalDensity.current
                MenuScene {
                    MenuPanel(Modifier.testTag(PICKED), width = REMOTE_MENU_WIDTH) {
                        MenuRow("High", selected = true) {}
                        MenuRow("Medium", selected = false) {}
                        MenuRow("Low", selected = false) {}
                    }
                }
            }
            waitForIdle()
            val pixels = onNodeWithTag(PICKED).captureToImage().toPixelMap()
            val chrome = chromeOf(pixels)
            val inset = with(density) { MENU_PADDING.roundToPx() }
            val highlight = commonest(
                opaquePixels(pixels).filter { it != chrome.fill && it != chrome.border },
            )
            val runs = (0 until pixels.height).map { widestRun(pixels, it) { pixel -> pixel == highlight } }
            val widest = runs.max()
            assertTrue(widest > 0, "the picked row drew no highlight at all")
            // The panel's own padding already keeps the highlight off the frame, so the width says
            // nothing on its own: what the clip adds is the corner, and only the highlight's first
            // row can see it.
            val top = runs.first { it > 0 }
            assertTrue(
                top < widest - inset,
                "the highlight starts ${widest - top}px short of its width — its corners are square inside a rounded panel",
            )
        }
    }
}

/** A catalog of [count] terminal hosts — enough of them to overflow the host picker. */
private fun catalogOf(count: Int): HostManagerController =
    hostCatalogOf(List(count) { i -> Host("h$i", "host-$i", "10.0.0.$i", 22, "root", null) })

private const val PICKER = "pane-host-picker"
private const val ROWS = "menu-panel-rows"
private const val PICKED = "remote-menu-picked-row"
