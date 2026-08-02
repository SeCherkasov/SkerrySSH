package app.skerry.ui.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Assistant panel visibility and the focus hand-off its chord depends on. */
class AssistantPanelStateTest {

    @Test
    fun assistant_panel_starts_closed_and_toggles() {
        val s = DesktopDesignState()
        assertFalse(s.assistantPanel)
        s.toggleAssistant()
        assertTrue(s.assistantPanel)
        s.toggleAssistant()
        assertFalse(s.assistantPanel)
    }

    @Test
    fun openAssistant_opens_and_never_closes_an_open_panel() {
        // The chord means "ask something": pressing it with the panel already open must not close it.
        val s = DesktopDesignState()
        s.openAssistant()
        assertTrue(s.assistantPanel)
        s.openAssistant()
        assertTrue(s.assistantPanel)
    }

    @Test
    fun openAssistant_leaves_a_focus_request_the_panel_consumes_when_it_appears() {
        // The panel is not in composition when the chord fires, so the request has to survive until
        // the ask row mounts — an event emitted into nothing would be dropped and the caret would
        // stay in the terminal on the first press.
        val s = DesktopDesignState()
        assertFalse(s.assistantFocusPending)
        s.openAssistant()
        assertTrue(s.assistantFocusPending)
        s.consumeAssistantFocus()
        assertFalse(s.assistantFocusPending)
    }
}
