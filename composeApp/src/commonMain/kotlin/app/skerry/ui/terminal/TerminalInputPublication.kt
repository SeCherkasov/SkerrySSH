package app.skerry.ui.terminal

import kotlin.concurrent.Volatile

/** A short feedback window for this terminal's delivered input; retains no input bytes. */
internal class TerminalInputPublication(private val nowMillis: () -> Long) {
    @Volatile
    private var lastInputAt = Long.MIN_VALUE / 2

    // Called by the sole PTY writer before and after a user write, including slow writes.
    fun onUserInputWrite() {
        lastInputAt = nowMillis()
    }

    fun intervalMillis(visible: Boolean): Long {
        val age = nowMillis() - lastInputAt
        return if (visible && age in 0 until INPUT_FEEDBACK_WINDOW_MS) INPUT_PUBLISH_INTERVAL_MS
        else PUBLISH_MIN_INTERVAL_MS
    }
}

private const val INPUT_PUBLISH_INTERVAL_MS = 4L
private const val INPUT_FEEDBACK_WINDOW_MS = 40L
