package app.skerry.ui.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the feed keeps following a streaming reply (see [followsTail]). Reading back through the
 * conversation while an answer arrives must not be fought by the auto-scroll.
 */
class AssistantFeedScrollTest {

    @Test
    fun `an empty feed follows the tail`() {
        assertTrue(followsTail(lastVisibleIndex = null, totalItems = 0, lastItemBottom = 0, viewportEnd = 500))
    }

    @Test
    fun `sitting at the bottom keeps following`() {
        assertTrue(followsTail(lastVisibleIndex = 4, totalItems = 5, lastItemBottom = 480, viewportEnd = 500))
    }

    @Test
    fun `a few pixels short of the bottom still counts as the bottom`() {
        // A partially drawn last line must not stop the follow, or a streaming reply would freeze
        // the viewport at the first delta that overflows it.
        assertTrue(followsTail(lastVisibleIndex = 4, totalItems = 5, lastItemBottom = 505, viewportEnd = 500))
    }

    @Test
    fun `scrolled up to read, the feed stays where it was put`() {
        assertFalse(followsTail(lastVisibleIndex = 2, totalItems = 5, lastItemBottom = 400, viewportEnd = 500))
    }

    @Test
    fun `the last item scrolled past the viewport bottom does not follow`() {
        // The last turn is on screen but its end is far below: the user is reading its start while
        // it grows, so the feed must not yank them to the end of it.
        assertFalse(followsTail(lastVisibleIndex = 4, totalItems = 5, lastItemBottom = 1200, viewportEnd = 500))
    }
}
