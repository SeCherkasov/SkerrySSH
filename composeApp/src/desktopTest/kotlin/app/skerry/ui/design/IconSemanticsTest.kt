package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test

/**
 * [Sym] draws a Material Symbol as a font ligature, which means the icon's *text* is the icon's
 * name. Left in the semantics tree that is what a screen reader says out loud: "vpn_key", "dns",
 * "more_horiz". It also rides along into anything that reads a control's label.
 *
 * So an icon says nothing by default — it is decoration, and the control around it carries the
 * name — and says exactly what the caller gives it when the icon *is* the only label there is.
 */
@OptIn(ExperimentalTestApi::class)
class IconSemanticsTest {

    @Test
    fun `an icon does not read out its ligature name`() = runComposeUiTest {
        icons { Sym("vpn_key") }
        onNodeWithText("vpn_key").assertDoesNotExist()
    }

    @Test
    fun `an icon given a description announces that instead`() = runComposeUiTest {
        icons { Sym("vpn_key", contentDescription = "Vault") }
        onNodeWithContentDescription("Vault").assertExists()
        onNodeWithText("vpn_key").assertDoesNotExist()
    }

    /** The label beside an icon is the whole label: the glyph must not prepend its own name to it. */
    @Test
    fun `an icon beside a label leaves the label alone`() = runComposeUiTest {
        icons {
            Sym("dns")
            Txt("Hosts")
        }
        onNodeWithText("Hosts").assertExists()
        onNodeWithText("dns").assertDoesNotExist()
    }

    private fun ComposeUiTest.icons(body: @Composable () -> Unit) {
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                ) {
                    body()
                }
            }
        }
        waitForIdle()
    }
}
