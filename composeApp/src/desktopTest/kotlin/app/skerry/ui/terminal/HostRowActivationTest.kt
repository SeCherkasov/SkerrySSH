package app.skerry.ui.terminal

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.ui.app.HostClickConnectMode
import app.skerry.ui.app.LocalHostClickConnectMode
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Connecting to a host row without a mouse.
 *
 * In double-click mode the row's `clickable` exists only for the ripple — its `onClick` is an empty
 * lambda, and connecting is done by a hand-written press counter that publishes no action of its
 * own. Anything that activates a control through its semantics rather than by pointer (a screen
 * reader, switch access) invokes the published action, so if the empty one is what got published the
 * row is inert to all of them while looking perfectly clickable.
 *
 * Which of two modifiers publishing the same key wins is decided by nesting, not by reading order,
 * so this is not something the chain can be eyeballed for.
 */
@OptIn(ExperimentalTestApi::class)
class HostRowActivationTest {

    @Test
    fun `a row activated through its semantics connects in double-click mode`() = runComposeUiTest {
        var connects = 0
        row(HostClickConnectMode.DoubleClick) { connects++ }
        onNodeWithText(LABEL).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(1, connects, "the published action was bound to something other than connect")
    }

    /** Single-click mode publishes the real action directly; pinned so the two modes cannot drift. */
    @Test
    fun `and in single-click mode`() = runComposeUiTest {
        var connects = 0
        row(HostClickConnectMode.SingleClick) { connects++ }
        onNodeWithText(LABEL).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(1, connects)
    }

    private fun ComposeUiTest.row(mode: HostClickConnectMode, onConnect: () -> Unit) {
        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    LocalHostClickConnectMode provides mode,
                ) {
                    HostEntryRow(
                        label = LABEL, selected = false, dot = Color.Green, badge = null,
                        onClick = onConnect, mono = FontFamily.Monospace, icon = "dns",
                    )
                }
            }
        }
        waitForIdle()
    }
}

private const val LABEL = "alpha"
