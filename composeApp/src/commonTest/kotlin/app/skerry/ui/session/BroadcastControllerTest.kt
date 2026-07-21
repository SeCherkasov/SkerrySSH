package app.skerry.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BroadcastControllerTest {

    private class Recorder {
        val sent = mutableListOf<String>()
    }

    private fun targets(vararg ids: String): Pair<List<BroadcastTarget>, Map<String, Recorder>> {
        val recorders = ids.associateWith { Recorder() }
        val list = ids.map { id ->
            BroadcastTarget(id = id, label = "host-$id", send = { text -> recorders.getValue(id).sent += text })
        }
        return list to recorders
    }

    @Test
    fun nothing_is_selected_initially() {
        val c = BroadcastController()
        val (all, _) = targets("a", "b")

        assertFalse(c.isSelected("a"))
        assertEquals(0, c.send("ls", all))
    }

    @Test
    fun sends_to_every_selected_target_with_a_trailing_newline() {
        val c = BroadcastController()
        val (all, recorders) = targets("a", "b", "c")
        c.toggle("a")
        c.toggle("c")

        assertEquals(2, c.send("uptime", all))

        assertEquals(listOf("uptime\n"), recorders.getValue("a").sent)
        assertEquals(listOf("uptime\n"), recorders.getValue("c").sent)
        assertEquals(emptyList(), recorders.getValue("b").sent)
    }

    @Test
    fun toggle_deselects() {
        val c = BroadcastController()
        val (all, recorders) = targets("a")
        c.toggle("a")
        c.toggle("a")

        assertEquals(0, c.send("ls", all))
        assertEquals(emptyList(), recorders.getValue("a").sent)
    }

    @Test
    fun blank_commands_are_never_sent() {
        val c = BroadcastController()
        val (all, recorders) = targets("a")
        c.toggle("a")

        assertEquals(0, c.send("   ", all))
        assertEquals(emptyList(), recorders.getValue("a").sent)
    }

    @Test
    fun a_selected_session_that_disappeared_is_skipped_not_crashed() {
        val c = BroadcastController()
        val (all, recorders) = targets("a", "b")
        c.toggle("a")
        c.toggle("b")

        // "b" closed since selection: only the live target is addressed.
        assertEquals(1, c.send("ls", all.filter { it.id == "a" }))
        assertEquals(listOf("ls\n"), recorders.getValue("a").sent)
    }

    @Test
    fun selectAll_and_clear_cover_the_live_targets() {
        val c = BroadcastController()
        val (all, _) = targets("a", "b")

        c.selectAll(all)
        assertTrue(c.isSelected("a") && c.isSelected("b"))
        assertEquals(2, c.selectedCount(all))

        c.clear()
        assertEquals(0, c.selectedCount(all))
    }

    @Test
    fun selectedCount_ignores_stale_ids() {
        val c = BroadcastController()
        val (all, _) = targets("a", "b")
        c.selectAll(all)

        assertEquals(1, c.selectedCount(all.filter { it.id == "a" }))
    }

    @Test
    fun a_failing_target_does_not_stop_the_rest() {
        val c = BroadcastController()
        val recorder = Recorder()
        val all = listOf(
            BroadcastTarget("bad", "host-bad", send = { error("channel is gone") }),
            BroadcastTarget("good", "host-good", send = { recorder.sent += it }),
        )
        c.selectAll(all)

        assertEquals(1, c.send("ls", all))
        assertEquals(listOf("ls\n"), recorder.sent)
    }
}
