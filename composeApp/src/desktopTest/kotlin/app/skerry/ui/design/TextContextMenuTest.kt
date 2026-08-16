package app.skerry.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.MouseButton
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Right-clicking selected text opens a menu Compose builds and, by default, paints as a white
 * Material sheet — which is what this app's dark chrome showed until [SkerryTextContextMenu] was
 * provided. Every other menu in the app is a [MenuPanel]; this one has to be too.
 *
 * Asserts the pixels rather than the presence of a menu: the default representation also opens a
 * menu with the same clickable items, so only its colour tells the two apart.
 */
@OptIn(ExperimentalTestApi::class)
class TextContextMenuTest {

    @Test
    fun `the selection context menu is drawn in the app's palette`() = runComposeUiTest {
        var panel = Color.Unspecified
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                ) {
                    panel = Skerry.colors.surface2
                    Box(Modifier.width(300.dp)) {
                        SelectionContainer { Txt(TEXT) }
                    }
                }
            }
        }
        // Select first: without a selection the menu's Copy item is not worth opening for.
        onNodeWithText(TEXT).performMouseInput {
            moveTo(centerLeft)
            press()
            moveTo(centerRight)
            release()
        }
        onNodeWithText(TEXT).performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()

        assertEquals(panel, popupFill(), "the menu is not painted on the app's panel colour")
    }

    /**
     * The same representation serves text fields, and that is the half a future Compose bump could
     * regress on its own: the parallel "new context menu" implementation ignores the local this
     * override is provided through. Selected text alone would not notice.
     */
    @Test
    fun `a text field's context menu is drawn in the app's palette too`() = runComposeUiTest {
        var panel = Color.Unspecified
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                ) {
                    panel = Skerry.colors.surface2
                    Box(Modifier.width(300.dp)) {
                        BasicTextField(value = TEXT, onValueChange = {}, modifier = Modifier.width(300.dp))
                    }
                }
            }
        }
        onNodeWithText(TEXT).performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()

        assertEquals(panel, popupFill(), "the field's menu is not painted on the app's panel colour")
    }

    /**
     * The panel is ours, the actions are Compose's — so the wiring between them is the part worth
     * proving. Over a selection the menu is one row, Copy; clicking it must reach the selection
     * manager, not just dismiss a good-looking popup. (The four-item form belongs to a text field.)
     */
    @Test
    fun `clicking Copy in the menu puts the selection on the clipboard`() = runComposeUiTest {
        val clipboard = FakeClipboard()
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    LocalClipboard provides clipboard,
                ) {
                    Box(Modifier.width(300.dp)) {
                        SelectionContainer { Txt(TEXT) }
                    }
                }
            }
        }
        onNodeWithText(TEXT).performMouseInput {
            moveTo(centerLeft)
            press()
            moveTo(centerRight)
            release()
        }
        onNodeWithText(TEXT).performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()

        onAllNodes(hasClickAction())[0].performClick()
        waitForIdle()

        assertEquals(TEXT, clipboard.text)
    }
}

/**
 * The colour the menu is mostly made of — `surface2` for the app's menu, white for the one Compose
 * draws by default. Read through [chromeOf], which is the one place in this package that knows how
 * to find a panel in a capture: the popup's own bounds are larger than the panel it holds, so a
 * corner or an edge reads as transparent, and the rounded corners and the glyphs are not the fill
 * either. It fails loudly on a popup that drew nothing rather than returning a sentinel.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.popupFill(): Color =
    chromeOf(onNode(isPopup()).captureToImage().toPixelMap()).fill

private const val TEXT = "reload the unit before restarting it"
