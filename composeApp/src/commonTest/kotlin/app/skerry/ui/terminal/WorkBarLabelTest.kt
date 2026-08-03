package app.skerry.ui.terminal

import app.skerry.ui.session.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What the work bar says about the tab under it (see [workBarLabel]). */
class WorkBarLabelTest {

    private fun pane(title: String, subtitle: String = "root@10.0.0.1:22", status: SessionStatus = SessionStatus.Live) =
        PaneFacts(title, subtitle, status)

    @Test
    fun `a single pane names its own host`() {
        val label = workBarLabel(listOf(pane("prod-web-01", "root@192.168.1.45:22")), syncInput = false)
        assertEquals(WorkBarLabel.Solo("prod-web-01", "root@192.168.1.45:22", SessionStatus.Live), label)
    }

    @Test
    fun `a split tab counts its panes and lists their hosts`() {
        val label = workBarLabel(
            listOf(pane("prod-web-01"), pane("prod-web-02"), pane("db-master"), pane("vps-edge")),
            syncInput = false,
        )
        val split = assertIs<WorkBarLabel.Split>(label)
        assertEquals(4, split.paneCount)
        assertEquals("prod-web-01, prod-web-02, db-master, vps-edge", split.hosts)
    }

    @Test
    fun `synchronized input is carried into the split label`() {
        val label = workBarLabel(listOf(pane("a"), pane("b")), syncInput = true) as WorkBarLabel.Split
        assertTrue(label.syncInput)
    }

    /** The dot speaks for the whole tab, so one broken pane must not read as "all live". */
    @Test
    fun `a failed pane decides the dot of a split tab`() {
        val label = workBarLabel(
            listOf(pane("a"), pane("b", status = SessionStatus.Failed), pane("c")),
            syncInput = false,
        )
        assertEquals(SessionStatus.Failed, label.status)
    }

    @Test
    fun `a pane with no session outweighs one still connecting`() {
        val label = workBarLabel(
            listOf(pane("a"), pane("b", status = SessionStatus.Connecting), pane("c", status = SessionStatus.Idle)),
            syncInput = false,
        )
        assertEquals(SessionStatus.Idle, label.status)
    }

    @Test
    fun `a connecting pane outweighs a live one`() {
        val label = workBarLabel(listOf(pane("a"), pane("b", status = SessionStatus.Connecting)), syncInput = false)
        assertEquals(SessionStatus.Connecting, label.status)
    }

    @Test
    fun `all panes live reads live`() {
        val label = workBarLabel(listOf(pane("a"), pane("b")), syncInput = false)
        assertEquals(SessionStatus.Live, label.status)
    }

    /** The two loudest states meet: the pair the other severity tests never put in one list. */
    @Test
    fun `a failed pane outweighs one with no session`() {
        val label = workBarLabel(
            listOf(pane("a", status = SessionStatus.Idle), pane("b", status = SessionStatus.Failed)),
            syncInput = false,
        )
        assertEquals(SessionStatus.Failed, label.status)
    }

    @Test
    fun `a tab always has a pane`() {
        assertFailsWith<IllegalArgumentException> { workBarLabel(emptyList(), syncInput = false) }
    }
}

/** What the bar says about one pane before the label is assembled (see [paneFacts]). */
class PaneFactsTest {

    @Test
    fun `a connected pane speaks for itself`() {
        val facts = paneFacts("prod-web-01", "root@10.0.0.1:22", SessionStatus.Live, blank = false, placeholder = "Select a host…")
        assertEquals(PaneFacts("prod-web-01", "root@10.0.0.1:22", SessionStatus.Live), facts)
    }

    /** A pane with no host yet is named by the invitation to pick one — its own label is empty. */
    @Test
    fun `a blank pane is named by the placeholder and carries no address`() {
        val facts = paneFacts("", "", SessionStatus.Idle, blank = true, placeholder = "Select a host…")
        assertEquals(PaneFacts("Select a host…", "", SessionStatus.Idle), facts)
    }

    /** A blank pane keeps a stale label from the tab it was opened on; the placeholder wins. */
    @Test
    fun `a blank pane does not show a leftover label`() {
        val facts = paneFacts("New tab", "root@old:22", SessionStatus.Idle, blank = true, placeholder = "Select a host…")
        assertEquals("Select a host…", facts.title)
        assertEquals("", facts.subtitle)
    }
}
