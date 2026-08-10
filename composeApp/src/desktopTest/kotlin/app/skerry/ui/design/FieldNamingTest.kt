package app.skerry.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test

/**
 * How a control takes its name from the caption above it.
 *
 * [FormField] publishes the caption and the input adopts it, which is what stops a form being a
 * column of anonymous boxes. The two helpers that read it differ in one way that matters and is easy
 * to get backwards:
 *
 * - [Modifier.fieldName] gives a control the caption. Right for an input, whose own content is a
 *   separate accessibility property and is announced alongside.
 * - [Modifier.fieldValueName] gives it the caption *and* the value. Right for a picker trigger,
 *   whose value is nothing but the text it draws: a bare caption on a node that merges its children
 *   replaces that text instead of joining it, and the chosen host stops being announced at all.
 *
 * The exact composed string is asserted here because every other test finds these controls by a
 * substring of the caption, and would go on passing if the value fell out of the name.
 */
@OptIn(ExperimentalTestApi::class)
class FieldNamingTest {

    @Test
    fun `an input inside a form field is named after the caption`() = runComposeUiTest {
        form {
            FormField(CAPTION) {
                Box(Modifier.fieldName())
            }
        }
        onNodeWithContentDescription(CAPTION).assertExists()
    }

    @Test
    fun `an input outside one falls back to what it is given`() = runComposeUiTest {
        form { Box(Modifier.fieldName(fallback = PLACEHOLDER)) }
        onNodeWithContentDescription(PLACEHOLDER).assertExists()
    }

    /** The caption wins: a field under a caption is that caption, whatever placeholder it draws. */
    @Test
    fun `a caption outranks the fallback`() = runComposeUiTest {
        form {
            FormField(CAPTION) {
                Box(Modifier.fieldName(fallback = PLACEHOLDER))
            }
        }
        onNodeWithContentDescription(CAPTION).assertExists()
        onNodeWithContentDescription(PLACEHOLDER).assertDoesNotExist()
    }

    @Test
    fun `a picker trigger is named by caption and value together`() = runComposeUiTest {
        form {
            FormField(CAPTION) {
                Box(Modifier.fieldValueName(VALUE).clickable {})
            }
        }
        onNodeWithContentDescription("$CAPTION, $VALUE").assertExists()
    }

    /** Without a caption around it, the value is the whole name — better than no name at all. */
    @Test
    fun `a picker trigger outside a form field is named by its value`() = runComposeUiTest {
        form { Box(Modifier.fieldValueName(VALUE).clickable {}) }
        onNodeWithContentDescription(VALUE).assertExists()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.form(content: @Composable () -> Unit) {
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                ) {
                    content()
                }
            }
        }
        waitForIdle()
    }
}

private const val CAPTION = "Via host"
private const val PLACEHOLDER = "Filter (name or *.mask)"
private const val VALUE = "prod-web-01"
