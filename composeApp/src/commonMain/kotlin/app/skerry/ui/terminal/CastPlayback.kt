package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import app.skerry.shared.terminal.Asciicast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A recording open for playback: the [CastPlayer] and the terminal it feeds, kept together so a
 * player tab ([app.skerry.ui.session.Session.playback]) can own them for as long as the tab lives.
 *
 * The scope is owned here, not by the composable: switching to another tab unmounts the player's UI
 * and must not tear the replay down (nor restart it on the way back). [stop] is therefore mandatory
 * when the tab closes, or the replay coroutine keeps feeding a screen nobody looks at.
 *
 * Default, not Main, exactly like a live session ([app.skerry.ui.connection.ConnectionController]):
 * a seek hands the emulator every event up to the target in one chunk, and on the main dispatcher
 * that parse freezes the UI (an ANR on Android) for a long recording.
 */
@Stable
class CastPlayback(
    val cast: Asciicast,
    // Injected only by tests; the playback owns whatever scope it is given and cancels it in [stop].
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val player = CastPlayer(cast, scope)
    val terminal = TerminalScreenState(player, scope)

    /** Whether [stop] has been called (scope cancelled, nothing left to render). */
    var stopped: Boolean = false
        private set

    private var started = false

    /**
     * Start playing the first time the recording is shown — opening it is the request to watch it.
     * Re-entering the tab resumes what was there instead of replaying from the top.
     */
    fun start() {
        if (started || stopped) return
        started = true
        player.play()
    }

    /** Cancel the replay coroutine; called when the player tab closes. */
    fun stop() {
        if (stopped) return
        stopped = true
        scope.cancel()
    }
}
