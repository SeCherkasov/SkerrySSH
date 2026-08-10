package app.skerry.ui.design

/**
 * "No press has been seen yet" for the hand-rolled press-counting loops (host rows, SFTP file rows).
 *
 * Those loops pair two presses by comparing `uptimeMillis` against the previous one, so the value
 * they start from — and reset to after a double click — has to be far enough in the past that no
 * press can pair with it. `0L` is not: a clock that starts near zero (the Compose test clock, a
 * freshly booted host) puts the very first press inside the window, and a single click opens what
 * only a double click should.
 */
internal const val NO_PRESS = Long.MIN_VALUE / 2
