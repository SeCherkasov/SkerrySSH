package app.skerry.ui.design

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * [ClaimKeyboard] on its own, for the rules that need no session behind them.
 *
 * The one covered here is the retry: a widget saying "I am the one to type into" ([enabled] turning
 * true) can be saying it at a moment when nobody can be given the keyboard — the window is away, a
 * modal is up. The claim is dropped then, and something has to ask again once the way is clear;
 * otherwise the widget sits there wanting the keyboard and never asking, which is the dead-typing
 * bug this primitive exists to prevent.
 */
@OptIn(ExperimentalTestApi::class)
class ClaimKeyboardTest {

    @Test
    fun `a claim made while the window was away is retried when it comes back`() {
        var windowFocused by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        var focusManager: FocusManager? = null
        val window = object : WindowInfo {
            override val isWindowFocused: Boolean get() = windowFocused
        }
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    focusManager = LocalFocusManager.current
                    Widget(enabled)
                }
            }
            waitForIdle()
            onNodeWithTag(WIDGET).assertIsFocused()

            // Something else takes the keyboard (a find bar over the session), and the window goes.
            enabled = false
            waitForIdle()
            runOnIdle { focusManager?.clearFocus(force = true) }
            // Settled first, on purpose: while the window is up and nothing modal is over it, the
            // keyboard going elsewhere is recorded — this widget no longer owns it, so the restore
            // path is out and only its own claim can bring the keyboard back.
            waitForIdle()
            windowFocused = false
            waitForIdle()

            // It closes while the app is in the background: the claim has nowhere to land.
            enabled = true
            waitForIdle()

            windowFocused = true
            waitForIdle()
            onNodeWithTag(WIDGET).assertIsFocused()
        }
    }

    @Composable
    private fun Widget(enabled: Boolean) {
        val focus = remember { FocusRequester() }
        val hasFocus = remember { mutableStateOf(false) }
        ClaimKeyboard(focus, key = "widget", focused = hasFocus, enabled = enabled)
        Box(
            Modifier.size(20.dp).testTag(WIDGET)
                .focusRequester(focus)
                .onFocusChanged { hasFocus.value = it.isFocused }
                .focusable(),
        )
    }

    private companion object {
        const val WIDGET = "widget"
    }
}
