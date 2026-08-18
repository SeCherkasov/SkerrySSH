package app.skerry.ui.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a code block may do while the reply it belongs to is still arriving (see [actionsFor]). The
 * fence is parsed on every delta, so the "command" on screen mid-stream is a prefix of the real one
 * — running that would send a truncated line to the shell.
 */
class AssistantStreamingActionsTest {

    private val live = AssistantCommandActions(run = {}, copy = { _, _ -> }, edit = {}, runnable = true)

    @Test
    fun `a finished reply keeps its actions`() {
        assertTrue(actionsFor(live, streaming = false).runnable)
    }

    @Test
    fun `a streaming reply cannot be run`() {
        assertFalse(actionsFor(live, streaming = true).runnable)
    }

    @Test
    fun `a streaming reply with no session stays unrunnable`() {
        val offline = live.copy(runnable = false)
        assertFalse(actionsFor(offline, streaming = true).runnable)
        assertFalse(actionsFor(offline, streaming = false).runnable)
    }
}
