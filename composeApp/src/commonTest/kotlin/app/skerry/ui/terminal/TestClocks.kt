package app.skerry.ui.terminal

/**
 * Clock whose every read advances past [PUBLISH_MIN_INTERVAL_MS]: the publish-rate cap sees each
 * batch as arriving after a quiet window and publishes immediately. Tests that are not about the
 * cap keep the pre-cap emit→assert semantics without controlling virtual time; the cap itself is
 * covered by the dedicated TerminalScreenStateTest cases that inject the scheduler clock instead.
 *
 * A new emit→assert test that forgets to inject this gets the real clock: the second batch within
 * one real window parks in the publish select and the assertion reads a stale screen — usually a
 * deterministic failure, but a flake when the test thread stalls past the window. Inject this.
 *
 * The same instance also feeds the search refresh throttle (300ms): tests relying on "search stays
 * throttled" hold as long as total reads stay under ~18 per test at 16ms per read.
 */
internal fun eagerPublishClock(): () -> Long {
    var t = 0L
    return { t += PUBLISH_MIN_INTERVAL_MS; t }
}

/**
 * [eagerPublishClock] with a longer step: every read also advances past [OFFER_DWELL_MS], so a sudo
 * prompt drawn by one emit has been standing long enough by the next keystroke for the offer to be
 * takeable. Tests about the dwell itself use the plain eager clock, where it never is.
 */
internal fun dwellingClock(): () -> Long {
    var t = 0L
    return { t += OFFER_DWELL_MS + PUBLISH_MIN_INTERVAL_MS; t }
}
