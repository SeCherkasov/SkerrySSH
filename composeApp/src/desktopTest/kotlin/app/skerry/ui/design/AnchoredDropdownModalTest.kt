package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A focusable dropdown holds the keyboard while it is up, so it has to count as a modal: the
 * session it opened over would otherwise claim focus back a frame later (the button that opened it
 * hands the keyboard back on its press), leaving the menu open and dead — Esc would go to the
 * remote shell instead of closing it.
 *
 * The type-ahead pickers pass `focusable = false` precisely so the field beside them keeps the
 * caret; those must not register, or every keystroke would be blocked from the field they serve.
 */
@OptIn(ExperimentalTestApi::class)
class AnchoredDropdownModalTest {

    @Test
    fun `an open focusable dropdown counts as a modal`() {
        runComposeUiTest {
            val base = ModalPresence.openCount
            setContent { Themed { Dropdown(expanded = true, focusable = true) } }
            waitForIdle()
            assertTrue(ModalPresence.openCount > base, "a focusable dropdown does not count as a modal")
        }
    }

    @Test
    fun `a type-ahead dropdown does not`() {
        runComposeUiTest {
            val base = ModalPresence.openCount
            setContent { Themed { Dropdown(expanded = true, focusable = false) } }
            waitForIdle()
            assertEquals(base, ModalPresence.openCount, "a non-focusable picker blocked the field it serves")
        }
    }

    @Test
    fun `a closed dropdown does not`() {
        runComposeUiTest {
            val base = ModalPresence.openCount
            setContent { Themed { Dropdown(expanded = false, focusable = true) } }
            waitForIdle()
            assertEquals(base, ModalPresence.openCount, "a closed dropdown counted as a modal")
        }
    }

    @Composable
    private fun Dropdown(expanded: Boolean, focusable: Boolean) {
        AnchoredDropdown(
            expanded = expanded,
            onDismiss = {},
            focusable = focusable,
            trigger = { Txt("anchor") },
            menu = { Txt("item") },
        )
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                content = content,
            )
        }
    }
}
