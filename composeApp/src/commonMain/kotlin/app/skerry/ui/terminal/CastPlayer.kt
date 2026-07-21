package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.Asciicast
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Playback speeds offered by the player UI. */
val CAST_SPEEDS = listOf(0.5f, 1f, 2f, 4f)

/** RIS (full terminal reset) — clears the screen before replaying from a position. */
private const val RESET = "\u001bc"

/** `m:ss` (or `h:mm:ss` past an hour) for the player's clock. */
fun formatCastTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val s = total % 60
    val m = (total / 60) % 60
    val h = total / 3600
    val ss = if (s < 10) "0$s" else "$s"
    if (h == 0L) return "$m:$ss"
    val mm = if (m < 10) "0$m" else "$m"
    return "$h:$mm:$ss"
}

/**
 * Replays a parsed recording ([Asciicast]) into a terminal.
 *
 * It is a [TerminalSession], so the recording renders through the very same [TerminalScreen] and
 * emulator a live session uses — colours, cursor movement and full-screen apps come out right
 * without a second renderer. The session is one-way: [send] and [resize] do nothing, because there
 * is nothing behind the screen to receive them.
 *
 * Seeking replays rather than rewinds: terminal output is a stream of state changes, so the only
 * way to know the screen at second N is to feed everything up to it. A seek therefore emits a RIS
 * and every event before the target as one write, then playback continues from there.
 */
@Stable
class CastPlayer(val cast: Asciicast, private val scope: CoroutineScope) : TerminalSession {

    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val output: Flow<ByteArray> = _output.asSharedFlow()

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state.asStateFlow()

    private var job: Job? = null

    /** Index of the next event to emit. */
    private var next = 0

    /** Length of the recording in seconds. */
    val duration: Double = cast.duration

    /** Where playback stands, in recording seconds. */
    var position: Double by mutableStateOf(0.0)
        private set

    var playing: Boolean by mutableStateOf(false)
        private set

    var speed: Float by mutableStateOf(1f)
        private set

    /** Whether the last event has been played (Play then starts over). */
    var finished: Boolean by mutableStateOf(false)
        private set

    /** Fraction played, 0..1 — for the progress bar. */
    val progress: Float get() = if (duration <= 0.0) 0f else (position / duration).toFloat().coerceIn(0f, 1f)

    fun play() {
        if (playing) return
        if (finished) { seekTo(0.0); return }
        playing = true
        job?.cancel()
        job = scope.launch {
            while (next < cast.events.size) {
                val event = cast.events[next]
                val waitMillis = ((event.at - position) * 1000 / speed).roundToLong()
                if (waitMillis > 0) delay(waitMillis)
                position = event.at
                next++
                emit(event.data)
            }
            position = duration
            finished = true
            playing = false
        }
    }

    fun pause() {
        job?.cancel()
        job = null
        playing = false
    }

    fun toggle() = if (playing) pause() else play()

    fun changeSpeed(value: Float) {
        if (value == speed) return
        val wasPlaying = playing
        pause()
        speed = value
        if (wasPlaying) play()
    }

    /** Jump to [seconds] and show the screen as it was at that moment. */
    fun seekTo(seconds: Double) {
        val target = seconds.coerceIn(0.0, duration)
        val wasPlaying = playing
        pause()
        val catchUp = StringBuilder(RESET)
        next = 0
        while (next < cast.events.size && cast.events[next].at <= target) {
            catchUp.append(cast.events[next].data)
            next++
        }
        position = target
        finished = false
        scope.launch { emit(catchUp.toString()) }
        if (wasPlaying) play()
    }

    /** Back to the beginning, screen cleared, playing again — the button says "replay". */
    fun restart() {
        seekTo(0.0)
        play()
    }

    private suspend fun emit(data: String) {
        _output.emit(data.encodeToByteArray())
    }

    // A recording is watched, not driven: nothing is sent anywhere and there is no channel to close.
    override suspend fun send(data: ByteArray) = Unit
    override suspend fun resize(size: PtySize) = Unit
    override suspend fun close() { pause() }
}
