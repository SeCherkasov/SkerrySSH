package app.skerry.shared.share

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.vault.DataKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * A viewer's side of a shared session: a [TerminalSession] fed by the relay instead of a PTY, so
 * the whole terminal UI renders a colleague's shell unchanged.
 *
 * The stream is untrusted (the relay is), so a frame that doesn't authenticate under the team key
 * is dropped and the session continues — one bad frame must not end a live view.
 *
 * Typing is always sent; whether it lands is the host's decision (see [SessionShareHost.allowInput]),
 * because only the host knows whether the share is read-only. [resize] is deliberately a no-op: a
 * viewer renders the host's grid ([geometry]), and resizing someone else's shell to fit a watcher's
 * window would move their cursor under their hands.
 */
class SharedSessionViewer(
    private val codec: SessionShareCodec,
    private val teamKey: DataKey,
    private val channel: ShareChannel,
    scope: CoroutineScope,
    /**
     * This account, announced to the host once. Only a host older than #312 reads it: this one takes
     * the name from the relay, which reads it off the socket's own JWT. Kept because a viewer on
     * this version still has to be nameable by a host on an older one.
     */
    private val accountId: String = "",
) : TerminalSession {

    // Keystrokes can be sent from more than one coroutine (typing, paste, key repeat); the counter
    // must not hand two frames the same number, or the host would drop the second as a replay.
    private val sendLock = Mutex()

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val output: Flow<ByteArray> = _output.asSharedFlow()

    // Identifies this viewer's socket to the host and counts its keystroke frames, so a relay that
// replays a captured frame is caught by the host (see [SessionShareHost]). Not a secret: the
    // relay already knows which socket a frame came from — it is a freshness marker, not a token.
    private val sender: Long = Random.nextLong()
    private var seq: Long = 0

    private val _geometry = MutableStateFlow<ShareFrame.Resize?>(null)

    private val _controlGranted = MutableStateFlow(false)

    /** Whether the host currently lets viewers type. Until then this view is read-only. */
    val controlGranted: StateFlow<Boolean> = _controlGranted.asStateFlow()

    /** The host's current grid; the UI resizes its emulator to this rather than to its own window. */
    val geometry: StateFlow<ShareFrame.Resize?> = _geometry.asStateFlow()

    init {
        scope.launch {
            // Same reason as [app.skerry.shared.terminal.ShellTerminalSession]: with replay=0, frames
            // read before the UI subscribes are dropped — and the first thing the relay sends is the
            // catch-up buffer, i.e. the screen the viewer came to see.
            _output.subscriptionCount.first { it > 0 }
            var cleanExit = false
            try {
                while (true) {
                    val event = channel.receive() ?: break
                    if (event !is ShareEvent.Data) continue
                    when (val frame = codec.open(teamKey, event.frame, ShareDirection.HOST_TO_GUEST)) {
                        is ShareFrame.Output -> _output.emit(frame.bytes)
                        is ShareFrame.Resize -> _geometry.value = frame
                        is ShareFrame.ControlState -> _controlGranted.value = frame.granted
                        ShareFrame.End -> {
                            cleanExit = true
                            return@launch
                        }
                        // Guest input echoed back by the relay, or a frame from a newer client.
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A dead socket ends the view (see finally); it must not crash the hosting scope.
            } finally {
                markClosed(cleanExit)
            }
        }
    }

    /**
     * Names this viewer to the host, so its hints can say who is typing. Sent once, by the caller,
     * as soon as the socket is up.
     *
     * Backward compatibility only: a host on this version ignores it, because the name inside is
     * written by whoever sealed the frame and every team member holds the key (#312). A host older
     * than that has no other way to tie a socket to a colleague, so it is still sent.
     */
    suspend fun announce() {
        if (accountId.isBlank()) return
        channel.send(codec.seal(teamKey, ShareFrame.Hello(sender, accountId), ShareDirection.GUEST_TO_HOST))
    }

    /** Asks the host for permission to type ("request remote control"). */
    suspend fun requestControl() {
        channel.send(codec.seal(teamKey, ShareFrame.ControlRequest(sender), ShareDirection.GUEST_TO_HOST))
    }

    /** Sends keystrokes to the host; they land only if the host allows viewer input. */
    override suspend fun send(data: ByteArray) {
        val frame = sendLock.withLock { ShareFrame.Input(data, sender, ++seq) }
        channel.send(codec.seal(teamKey, frame, ShareDirection.GUEST_TO_HOST))
    }

    /** No-op: a viewer follows the host's [geometry] instead of resizing the host's shell. */
    override suspend fun resize(size: PtySize) = Unit

    /** Stops watching and releases the relay socket. */
    override suspend fun close() {
        markClosed(cleanExit = false)
        channel.close()
    }

    /**
     * `cleanExit = true` only for a host that said goodbye ([ShareFrame.End]): the UI then reports a
     * session that ended rather than one that dropped. Never downgrades a value already set — if the
     * host's End arrived first, a later socket close must not rewrite it.
     */
    private fun markClosed(cleanExit: Boolean) {
        _state.update { current ->
            if (current == TerminalState.Open) TerminalState.Closed(cleanExit) else current
        }
    }
}
