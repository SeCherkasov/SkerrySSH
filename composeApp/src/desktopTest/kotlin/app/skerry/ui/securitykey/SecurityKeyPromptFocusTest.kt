package app.skerry.ui.securitykey

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import app.skerry.ui.design.ModalPresence
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SecurityKeyPromptFocusTest {
    @Test
    fun localPinPromptClaimsTheCaretAndBlocksTerminalReclaim() {
        val baseline = ModalPresence.openCount
        val controller = SecurityKeyPromptController()
        runForm({
            LaunchedEffect(Unit) { controller.requestPin() }
            SecurityKeyPromptHost(controller)
        }) {
            assertTrue(ModalPresence.openCount > baseline, "Local PIN must block terminal keyboard reclaim")
            onNode(hasSetTextAction()).assertIsFocused()
        }
    }

    @Test
    fun touchPromptAlsoBlocksTerminalReclaim() {
        val baseline = ModalPresence.openCount
        val controller = SecurityKeyPromptController()
        runForm({
            LaunchedEffect(Unit) { controller.onTouch() }
            SecurityKeyPromptHost(controller)
        }) {
            assertTrue(ModalPresence.openCount > baseline, "The touch stage must keep typed PINs away from the terminal")
        }
    }
}
