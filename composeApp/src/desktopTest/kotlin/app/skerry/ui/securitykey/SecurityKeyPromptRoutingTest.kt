package app.skerry.ui.securitykey

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.skerry.ui.desktop.WithWindowInfo
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises physical-key routing, including a prompt over an already focused remote surface. */
@OptIn(ExperimentalTestApi::class)
class SecurityKeyPromptRoutingTest {
    @Test
    fun localPinKeysNeverReachFocusedRemoteSurfaceIncludingWindowReturn() {
        var ask by mutableStateOf(false)
        var windowFocused by mutableStateOf(true)
        val window = object : WindowInfo { override val isWindowFocused get() = windowFocused }
        val remoteFocus = FocusRequester()
        val controller = SecurityKeyPromptController()
        var remoteKeys = 0
        var clearFocus: () -> Unit = {}
        runForm({
            WithWindowInfo(window) {
                val manager = LocalFocusManager.current
                clearFocus = { manager.clearFocus(force = true) }
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.focusRequester(remoteFocus).onPreviewKeyEvent { remoteKeys++; true }.focusable())
                    if (ask) {
                        LaunchedEffect(Unit) { controller.requestPin() }
                        SecurityKeyPromptHost(controller)
                    }
                }
            }
        }) {
            runOnIdle { remoteFocus.requestFocus() }
            onRoot().performKeyInput { pressKey(Key.A) }
            val before = remoteKeys
            assertTrue(before > 0, "The underlying remote surface must own the initial keyboard focus")
            runOnIdle { ask = true }
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.One); pressKey(Key.Two) }
            assertEquals(before, remoteKeys, "Local PIN was routed to the remote surface")
            runOnIdle { windowFocused = false; clearFocus() }
            waitForIdle()
            runOnIdle { windowFocused = true }
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.Three) }
            assertEquals(before, remoteKeys, "Window focus restoration exposed the local PIN")
        }
    }
}
