package app.skerry.ui.vnc

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.vnc.VncAuth
import app.skerry.shared.vnc.VncSession
import app.skerry.shared.vnc.VncTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** State of a VNC session tab — the framebuffer sibling of `ConnectionUiState`. */
sealed interface VncUiState {
    /** Connect/handshake in progress. */
    data object Connecting : VncUiState

    /** Session is live; [screen] is the framebuffer + input bridge. */
    data class Connected(val screen: VncScreenState) : VncUiState

    /**
     * Connect failed. [failure] is the localization contract — the raw [detail] (wire diagnostics,
     * always English) is for logs, never the message shown on its own.
     */
    data class Error(val failure: VncFailure, val detail: String = "") : VncUiState

    /**
     * The session closed not on our initiative (server drop / EOF). [screen] is the frozen last
     * frame; [cleanExit] true = the peer closed cleanly ("Session closed"), false = a transport drop.
     * The user reconnects manually (no silent auto-reconnect in v1).
     */
    data class Disconnected(val screen: VncScreenState, val cleanExit: Boolean) : VncUiState
}

/**
 * Binds a VNC tab to [VncTransport]: [connect] opens the session and assembles a [VncScreenState];
 * a watcher moves to [VncUiState.Disconnected] when the session closes on its own. The framebuffer
 * sibling of `ConnectionController`, reusing its lifecycle discipline (separate session scope,
 * teardown under [NonCancellable], secret reference dropped on disconnect), but without the
 * terminal/SFTP/forward machinery.
 */
@Stable
class VncSessionController(
    private val transport: VncTransport,
    private val scope: CoroutineScope,
    private val newSessionScope: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
    },
) {
    var uiState: VncUiState by mutableStateOf(VncUiState.Connecting)
        private set

    private var connectJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    private var session: VncSession? = null

    /**
     * Connect to [target] with [auth]. Ignored if a connect is already in progress or live.
     * [remoteResize] seeds the "Resize to window" toggle from the host profile;
     * [onRemoteResizeChanged] reports the user changing it (so the profile can be updated).
     */
    fun connect(
        target: SshTarget,
        auth: VncAuth,
        remoteResize: Boolean = false,
        onRemoteResizeChanged: (Boolean) -> Unit = {},
    ) {
        if (uiState is VncUiState.Connected) return
        uiState = VncUiState.Connecting
        connectJob = scope.launch {
            try {
                val opened = transport.connect(target, auth)
                val sScope = newSessionScope()
                session = opened
                sessionScope = sScope
                val screen = VncScreenState(
                    opened, sScope,
                    remoteResizeInitial = remoteResize,
                    onRemoteResizeChanged = onRemoteResizeChanged,
                )
                uiState = VncUiState.Connected(screen)
                watchForClose(screen, sScope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                releaseSession()
                // Typed reason for the UI; the wire text stays as diagnostics only.
                uiState = VncUiState.Error(vncFailureOf(e), e.message.orEmpty())
            }
        }
    }

    /**
     * Watches [screen] for closure (server drop / EOF): moves to [VncUiState.Disconnected] with the
     * frozen frame. Lives on the session scope, so our own [disconnect] (which cancels that scope)
     * kills it before it fires — this path is reached only on a server-side close.
     */
    private fun watchForClose(screen: VncScreenState, sScope: CoroutineScope) {
        sScope.launch {
            snapshotFlow { screen.closed }.first { it }
            // Dispatch onto the main scope (like ConnectionController) so the transition doesn't race disconnect.
            scope.launch {
                if (uiState is VncUiState.Connected) {
                    uiState = VncUiState.Disconnected(screen, screen.cleanExit)
                    releaseSession()
                }
            }
        }
    }

    /** Close the session (if any) and reset. */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        releaseSession()
        uiState = VncUiState.Connecting
    }

    private fun releaseSession() {
        val s = session
        sessionScope?.cancel()
        sessionScope = null
        session = null
        // Close under NonCancellable so teardown isn't lost if the main scope is cancelled.
        if (s != null) scope.launch(NonCancellable) { runCatching { s.close() } }
    }
}
