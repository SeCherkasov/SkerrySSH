package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Modal stack semantics: only the topmost open modal may reclaim keyboard focus. A scrim that
 * reclaims while a later-opened sibling dialog is on top steals the caret from that dialog's
 * fields on every click (the sync setup dialog over the settings panel).
 */
class ModalPresenceTest {

    @Test
    fun single_modal_is_top_until_closed() {
        val base = ModalPresence.openCount
        val token = ModalPresence.opened()
        assertEquals(base + 1, ModalPresence.openCount)
        assertTrue(ModalPresence.isTop(token))
        ModalPresence.closed(token)
        assertEquals(base, ModalPresence.openCount)
        assertFalse(ModalPresence.isTop(token))
    }

    @Test
    fun later_modal_takes_top_from_earlier_one() {
        val settings = ModalPresence.opened()
        val dialog = ModalPresence.opened()
        // The settings scrim must not reclaim focus while the sync dialog is above it.
        assertFalse(ModalPresence.isTop(settings))
        assertTrue(ModalPresence.isTop(dialog))
        ModalPresence.closed(dialog)
        // Dialog closed — settings is top again and may restore Esc focus.
        assertTrue(ModalPresence.isTop(settings))
        ModalPresence.closed(settings)
    }

    @Test
    fun out_of_order_close_keeps_top_consistent() {
        val a = ModalPresence.opened()
        val b = ModalPresence.opened()
        val c = ModalPresence.opened()
        // The middle modal closing (its state flag flipped externally) must not disturb the top.
        ModalPresence.closed(b)
        assertTrue(ModalPresence.isTop(c))
        ModalPresence.closed(c)
        assertTrue(ModalPresence.isTop(a))
        ModalPresence.closed(a)
    }

    @Test
    fun tokens_are_not_reused_across_reopen() {
        val first = ModalPresence.opened()
        ModalPresence.closed(first)
        val second = ModalPresence.opened()
        // A stale token from a disposed scrim must never match the freshly opened one.
        assertFalse(ModalPresence.isTop(first))
        assertTrue(ModalPresence.isTop(second))
        ModalPresence.closed(second)
    }
}
