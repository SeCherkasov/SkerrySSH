package app.skerry.shared.terminal

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
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
import kotlin.coroutines.cancellation.CancellationException

/** Session lifecycle. */
sealed interface TerminalState {
    /** Channel is open, session is live. */
    data object Open : TerminalState

    /**
     * Channel is closed. [cleanExit] = true if the server ended the shell normally (EOF, e.g.
     * `exit`): the session closes without auto-reconnect. false means the transport dropped or
     * [TerminalSession.close] closed it; the caller triggers auto-reconnect on a drop.
     */
    data class Closed(val cleanExit: Boolean = false) : TerminalState
}

/**
 * Interactive terminal session over a [ShellChannel].
 *
 * Removes two constraints of the raw channel from the UI: the session owns the single allowed
 * collector of [ShellChannel.output] and re-emits it as a hot [output] flow for any number of
 * subscribers (the UI resubscribes on recomposition). The session keeps no scrollback history —
 * that is the terminal emulator's responsibility in the UI.
 */
interface TerminalSession {
    val state: StateFlow<TerminalState>

    /**
     * Hot PTY output flow. Subscribers get bytes from the moment they subscribe; no replay.
     * Carries no completion signal — [state] reports when the session ends.
     */
    val output: Flow<ByteArray>

    /**
     * Whether the server currently suppresses echo (password entry / line-mode) — see
     * [ShellChannel.echoSuppressed]. The UI uses this to skip writing typed input into
     * autocomplete history. Defaults to `false` (fakes/tests).
     */
    val echoSuppressed: Boolean get() = false

    /** @throws app.skerry.shared.ssh.SshConnectionException channel closed or transport dropped */
    suspend fun send(data: ByteArray)

    suspend fun resize(size: PtySize)

    suspend fun close()
}

/**
 * Implementation over an open [channel]. Output collection lives in [scope]: its completion
 * (EOF, cancellation, exception) moves the session to [TerminalState.Closed]. A clean channel
 * EOF (shell did `exit`) gives `cleanExit=true` — see [ShellChannel.endedWithEof]. Cancelling
 * [scope] externally stops the session along with collection.
 */
class ShellTerminalSession(
    private val channel: ShellChannel,
    scope: CoroutineScope,
) : TerminalSession {

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val output: Flow<ByteArray> = _output.asSharedFlow()

    // Forward the channel's echo status (Telnet reports password entry / line-mode; SSH is always false).
    override val echoSuppressed: Boolean get() = channel.echoSuppressed

    init {
        scope.launch {
            // Start collecting the channel only after the first subscriber appears. Otherwise
            // `init` would read the channel immediately and emit the startup output (shell
            // banner/first prompt) into [_output], which has replay=0 and drops emissions with no
            // subscriber. The UI collector subscribes a few milliseconds later, missing it.
            _output.subscriptionCount.first { it > 0 }
            try {
                channel.output.collect { _output.emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Transport drop ends the session (see finally) but must not crash the scope
                // that hosts output collection.
            } finally {
                // Clean channel EOF (server closed the shell itself, `exit`) gives cleanExit=true:
                // the caller does not reconnect. Transport drop/cancellation leave endedWithEof=false.
                _state.value = TerminalState.Closed(cleanExit = channel.endedWithEof)
            }
        }
    }

    override suspend fun send(data: ByteArray) = channel.write(data)

    override suspend fun resize(size: PtySize) = channel.resize(size)

    override suspend fun close() {
        // Set state explicitly: collection may not have started yet (no subscriber), so the
        // collector's finally block would never run even though the session is already closed.
        channel.close()
        // Our own close is not a clean shell exit: cleanExit=false (the caller initiated it).
        // Don't overwrite a [Closed] already set by collection: if the server sent a clean EOF
        // first (cleanExit=true), that value must reach observers. Only transition from Open.
        _state.update { current ->
            if (current == TerminalState.Open) TerminalState.Closed(cleanExit = false) else current
        }
    }
}
