package app.skerry.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The startup wait for `Xft.dpi`. Timings are simulated: the fake clock only advances inside
 * [Session.sleep], so a test never depends on wall-clock scheduling.
 */
class DisplayScaleReadinessTest {

    /** Resource-database reads scripted by elapsed time: `at` ms -> what the X server reports from then on. */
    private class Session(vararg timeline: Pair<Long, String?>) {
        private val script = timeline.sortedBy { it.first }
        var now = 0L
            private set
        var slept = 0L
            private set

        fun read(): String? = script.lastOrNull { it.first <= now }?.second

        fun sleep(ms: Long) {
            slept += ms
            now += ms
        }
    }

    private fun database(dpi: String?): String =
        "Xcursor.size:\t24\nXft.antialias:\t1\n" + if (dpi != null) "Xft.dpi:\t$dpi\n" else ""

    private fun Session.await(
        budgetMs: Long = 1000L,
        appearanceBudgetMs: Long = 500L,
        quietMs: Long = 250L,
        pollMs: Long = 20L,
    ) = DisplayScaleReadiness.awaitSettledDpi(
        readResources = ::read,
        sleep = ::sleep,
        elapsedMs = { now },
        budgetMs = budgetMs,
        appearanceBudgetMs = appearanceBudgetMs,
        quietMs = quietMs,
        pollMs = pollMs,
    )

    @Test
    fun `reads Xft dpi out of the resource database`() {
        val resources = "Xcursor.size:\t24\nXft.antialias:\t1\nXft.dpi:\t192\nXft.hinting:\t1\n"

        assertEquals("192", DisplayScaleReadiness.parseXftDpi(resources))
    }

    @Test
    fun `reports no value when the resource database has no dpi`() {
        assertNull(DisplayScaleReadiness.parseXftDpi(""))
        assertNull(DisplayScaleReadiness.parseXftDpi(null))
        assertNull(DisplayScaleReadiness.parseXftDpi("Xft.antialias:\t1\n"))
        // A key that merely starts the same is not the one we want, and neither is an empty value.
        assertNull(DisplayScaleReadiness.parseXftDpi("Xft.dpiFoo:\t192\n"))
        assertNull(DisplayScaleReadiness.parseXftDpi("Xft.dpi:\t\n"))
    }

    @Test
    fun `recognises a Wayland session even without the Wayland socket`() {
        // A sandbox granted the X11 socket only leaves WAYLAND_DISPLAY empty.
        assertTrue(DisplayScaleReadiness.isWaylandSession("wayland", null))
        assertTrue(DisplayScaleReadiness.isWaylandSession("wayland", ""))
        assertTrue(DisplayScaleReadiness.isWaylandSession(null, "wayland-0"))
        assertFalse(DisplayScaleReadiness.isWaylandSession("x11", null))
        assertFalse(DisplayScaleReadiness.isWaylandSession(null, null))
    }

    @Test
    fun `never opens a display outside a Wayland session`() {
        var opened = 0

        val settled = DisplayScaleReadiness.settleDisplayScale(
            sessionType = "x11",
            waylandDisplay = null,
            openDatabase = { opened++; null },
        )

        assertNull(settled)
        assertEquals(0, opened, "an X11 session has no race to wait out")
    }

    @Test
    fun `gives up when the display cannot be opened`() {
        var opened = 0

        val settled = DisplayScaleReadiness.settleDisplayScale(
            sessionType = "wayland",
            waylandDisplay = null,
            openDatabase = { opened++; null },
        )

        assertNull(settled)
        assertEquals(1, opened)
    }

    @Test
    fun `hands startup back on the deadline when the X calls block`() {
        // The budgets only count between reads; this is the guard for a compositor that accepts the
        // socket and then never answers, which would otherwise hang main() before any window exists.
        val started = System.nanoTime()

        DisplayScaleReadiness.awaitDisplayScale(
            sessionType = "wayland",
            waylandDisplay = null,
            deadlineMs = 200L,
            openDatabase = { Thread.sleep(30_000); null },
        )

        val elapsed = (System.nanoTime() - started) / 1_000_000
        // Under the default deadline (1500 ms), so falling back to it instead of honouring the one
        // passed in fails here too — not just removing the bound altogether.
        assertTrue(elapsed < 1_000, "startup waited $elapsed ms on a display that never answers")
    }

    @Test
    fun `waits out a database that arrives without its dpi yet`() {
        // The property is already there when we look, but the dpi entry lands a moment later — the
        // fast path must not read that as "this session has no scale".
        val session = Session(0L to database(null), 60L to database("192"))

        assertEquals("192", session.await())
    }

    @Test
    fun `returns the value already published without waiting`() {
        val session = Session(0L to database("192"))

        assertEquals("192", session.await())
        assertEquals(0L, session.slept, "a session that already has a database must not delay startup")
    }

    @Test
    fun `waits for a database that is not there yet`() {
        val session = Session(0L to null, 200L to database("192"))

        assertEquals("192", session.await())
        assertTrue(session.slept >= 200L, "expected to wait for the database, slept ${session.slept} ms")
    }

    @Test
    fun `outlasts the default the daemon publishes first`() {
        // What GNOME 50 actually does on a fresh XWayland at 166%: nothing, then 96, then 192.
        val session = Session(0L to null, 220L to database("96"), 262L to database("192"))

        assertEquals("192", session.await())
    }

    @Test
    fun `picks up a dpi that lands after the rest of the database`() {
        val session = Session(0L to null, 200L to database(null), 260L to database("192"))

        assertEquals("192", session.await())
    }

    @Test
    fun `keeps following a value that changes again`() {
        val session = Session(0L to null, 100L to database("96"), 300L to database("144"), 500L to database("192"))

        assertEquals("192", session.await())
    }

    @Test
    fun `gives up on the shorter appearance budget when nothing is published`() {
        // A compositor with no settings daemon (sway, Hyprland) pays this on every start, so it
        // must be the short budget, not the full one.
        val session = Session(0L to null)

        assertNull(session.await(budgetMs = 1000L, appearanceBudgetMs = 400L))
        assertTrue(session.slept <= 400L + 20L, "overshot the appearance budget: slept ${session.slept} ms")
    }

    @Test
    fun `settles a late dpi past the appearance budget once the database is in hand`() {
        // The appearance budget only caps the wait for the database itself; chasing the value it
        // then publishes is the full budget's job.
        val session = Session(0L to null, 300L to database("96"), 500L to database("192"))

        assertEquals("192", session.await(budgetMs = 1000L, appearanceBudgetMs = 400L))
        assertTrue(session.slept > 400L, "stopped at the appearance budget: slept ${session.slept} ms")
    }

    @Test
    fun `stops at the budget even while the value is still moving`() {
        val session = Session(0L to null, 100L to database("96"), 380L to database("192"))

        // The budget is the hard stop: a value in hand beats waiting for a quiet period that may
        // never come.
        assertEquals("96", session.await(budgetMs = 300L))
        assertTrue(session.slept <= 320L, "overshot the budget: slept ${session.slept} ms")
    }
}
