package app.skerry.ui.desktop

/**
 * Waits for the X resource database to report a settled `Xft.dpi` before anything reads the display
 * scale.
 *
 * Skiko decides the UI scale exactly once, at startup: `Setup.init` reads `Xft.dpi` from the X
 * resource database and writes it into `sun.java2d.uiScale`, which AWT then caches for the lifetime
 * of the JVM. On a Wayland session GNOME starts XWayland on demand, so the first X11 client of the
 * session is the one that brings it up — and `gsd-xsettings` only fills RESOURCE_MANAGER in
 * afterwards. Measured on GNOME 50 at 166%: the database is missing for ~220 ms, arrives carrying
 * the 96 dpi default, and reaches the display's real 192 some 40 ms later. An app that reads it
 * inside that window runs the whole session unscaled — which is why only the first launch after
 * login was wrong, and every later one was fine.
 */
internal object DisplayScaleReadiness {
    /**
     * Overall wait, ms — the cap once the database is in sight and only its dpi is still moving.
     * Reached only on a session that keeps rewriting the property.
     */
    const val DEFAULT_BUDGET_MS = 1000L

    /**
     * How long to wait for an absent database to show up at all, ms. Separate from (and much
     * shorter than) [DEFAULT_BUDGET_MS] because this is the wait a compositor with no settings
     * daemon at all — sway, Hyprland, river — pays on **every** start, for a value that will never
     * arrive. GNOME publishes at ~220 ms, so this is roughly double the measured need.
     */
    const val DEFAULT_APPEARANCE_BUDGET_MS = 500L

    /** How long the whole wait may take, including the native calls that ignore the budgets above. */
    private const val HARD_DEADLINE_MS = DEFAULT_BUDGET_MS + 500L

    /** How long the value has to hold still before it counts as final, ms. */
    const val DEFAULT_QUIET_MS = 250L

    /** Interval between reads, ms. */
    const val DEFAULT_POLL_MS = 20L

    // Entries are "name:<whitespace>value" lines; the name is matched whole so that a longer key
    // starting with the same text can't be mistaken for it.
    private val XFT_DPI = Regex("""^Xft\.dpi:[ \t]*(\S+)[ \t]*$""", RegexOption.MULTILINE)

    /**
     * Blocks until the session's display scale is readable, so that Skiko's one and only look at
     * `Xft.dpi` sees the real value. Call before anything touches AWT, Skiko or Compose — after
     * that, the scale is already frozen into `sun.java2d.uiScale`.
     *
     * Costs nothing on a session that already published the database, and nothing at all outside a
     * Wayland session: with a real X server the display and its settings daemon both outlive every
     * app, so there is no window to lose the race in.
     */
    fun awaitDisplayScale(
        sessionType: String? = System.getenv("XDG_SESSION_TYPE"),
        waylandDisplay: String? = System.getenv("WAYLAND_DISPLAY"),
        deadlineMs: Long = HARD_DEADLINE_MS,
        openDatabase: () -> XResourceDatabase? = XResourceDatabase::open,
    ) {
        // On a daemon thread with a deadline: `XOpenDisplay` and `XGetWindowProperty` block on the
        // X socket, and the budgets below only count between calls — a compositor that never
        // answers would otherwise hang startup before a window ever exists. An unscaled UI beats an
        // app that doesn't come up, so the deadline wins and the abandoned thread dies with the JVM.
        val worker = Thread(
            { runCatching { settleDisplayScale(sessionType, waylandDisplay, openDatabase) } },
            "skerry-display-scale",
        )
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(deadlineMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * The wait itself, with its inputs handed in. Returns the settled `Xft.dpi`, or null when there
     * was nothing to wait for (not a Wayland session, no display) or nothing was published.
     */
    fun settleDisplayScale(
        sessionType: String?,
        waylandDisplay: String?,
        openDatabase: () -> XResourceDatabase?,
    ): String? {
        if (!isWaylandSession(sessionType, waylandDisplay)) return null
        val database = openDatabase() ?: return null
        return database.use {
            val start = System.nanoTime()
            awaitSettledDpi(
                readResources = it::read,
                sleep = Thread::sleep,
                elapsedMs = { (System.nanoTime() - start) / 1_000_000 },
            )
        }
    }

    /**
     * Whether this is a Wayland session, where XWayland starts on demand and can therefore be
     * younger than the app reading its settings.
     *
     * Both signals are needed: a sandbox that grants the X11 socket only leaves `WAYLAND_DISPLAY`
     * empty, while `XDG_SESSION_TYPE` is passed through.
     */
    fun isWaylandSession(sessionType: String?, waylandDisplay: String?): Boolean =
        sessionType.equals("wayland", ignoreCase = true) || !waylandDisplay.isNullOrEmpty()

    /** The `Xft.dpi` entry of an X resource database dump, or null when it carries no usable value. */
    fun parseXftDpi(resources: String?): String? =
        resources?.let { XFT_DPI.find(it)?.groupValues?.get(1) }

    /**
     * Returns the settled `Xft.dpi`, or null when no database showed up inside [appearanceBudgetMs]
     * (a session with no settings daemon at all, or a headless display).
     *
     * A database that is already there is returned as-is without waiting: that's the common case
     * (XWayland already running), and startup must not pay for the rare one. It does leave a narrow
     * exposure — if some other client started XWayland 40-odd ms before us, the database exists but
     * still carries the 96 dpi default — accepted deliberately, because closing it would mean every
     * launch on every machine waiting out a [quietMs] period for a value that is already correct.
     *
     * A database that appears while we watch is instead followed until its dpi stops changing for
     * [quietMs], bounded by [budgetMs]: the first thing the daemon publishes is that same 96 dpi
     * default rather than the display's real scale, and the dpi entry can even trail the rest of
     * the database.
     */
    fun awaitSettledDpi(
        readResources: () -> String?,
        sleep: (Long) -> Unit,
        elapsedMs: () -> Long,
        budgetMs: Long = DEFAULT_BUDGET_MS,
        appearanceBudgetMs: Long = DEFAULT_APPEARANCE_BUDGET_MS,
        quietMs: Long = DEFAULT_QUIET_MS,
        pollMs: Long = DEFAULT_POLL_MS,
    ): String? {
        var resources = readResources()
        // A database that already carries a dpi is the answer. One without it isn't: the entry can
        // trail the rest of the property by tens of ms, so fall through to the settle loop, which
        // gives it a quiet period to arrive instead of declaring the session unscaled.
        parseXftDpi(resources)?.let { return it }
        while (resources == null && elapsedMs() < appearanceBudgetMs) {
            sleep(pollMs)
            resources = readResources()
        }
        if (resources == null) return null
        var dpi = parseXftDpi(resources)
        var lastChange = elapsedMs()
        while (elapsedMs() - lastChange < quietMs && elapsedMs() < budgetMs) {
            sleep(pollMs)
            val next = parseXftDpi(readResources())
            if (next != null && next != dpi) {
                dpi = next
                lastChange = elapsedMs()
            }
        }
        return dpi
    }
}
