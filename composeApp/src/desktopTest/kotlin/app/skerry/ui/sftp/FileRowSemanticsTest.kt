package app.skerry.ui.sftp

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a listing row tells anything that is not a mouse.
 *
 * Both properties here are invisible in a screenshot and invisible to a click test: marking is drawn
 * as a tint and a bold name, and opening a row is a hand-parsed double click that publishes no
 * action of its own. Without them a screen-reader or switch-access user can neither tell which files
 * are marked nor open one at all — and nothing else in the suite would notice if they were dropped.
 */
@OptIn(ExperimentalTestApi::class)
class FileRowSemanticsTest {

    @Test
    fun `a marked row says it is marked`() = runComposeUiTest {
        row(isSelected = true)
        onNodeWithText(ROW_NAME).assertIsSelected()
    }

    /** Asserted the other way round too: a row that always claimed to be marked would pass one. */
    @Test
    fun `an unmarked row says it is not`() = runComposeUiTest {
        row(isSelected = false)
        onNodeWithText(ROW_NAME).assertIsNotSelected()
    }

    @Test
    fun `activating the row opens it`() = runComposeUiTest {
        var opens = 0
        row(onDoubleClick = { opens++ })
        onNodeWithText(ROW_NAME).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(1, opens, "the row published no way to open it without a mouse")
    }

    private fun ComposeUiTest.row(isSelected: Boolean = false, onDoubleClick: () -> Unit = {}) {
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                ) {
                    Box(Modifier) {
                        LiveFileRow(
                            icon = "folder",
                            iconColor = Color.Cyan,
                            name = ROW_NAME,
                            columns = FileRowColumns(permissions = null, modified = null, size = ""),
                            isSelected = isSelected,
                            cursored = false,
                            active = true,
                            mono = FontFamily.Monospace,
                            onPress = {},
                            onDoubleClick = onDoubleClick,
                            directory = true,
                            rubberBand = null,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }
}

private const val ROW_NAME = "html"
