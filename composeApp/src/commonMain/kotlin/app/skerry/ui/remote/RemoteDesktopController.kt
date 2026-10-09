package app.skerry.ui.remote

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.rdp.RdpConnectStage
import app.skerry.shared.rdp.RdpDrop
import app.skerry.ui.vnc.VncFailure
import app.skerry.ui.vnc.rdpConnectFailureOf
import app.skerry.ui.vnc.vncFailureOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** State of a remote-desktop tab — the framebuffer sibling of `ConnectionUiState`. */
sealed interface RemoteDesktopUiState {
    /** Connect/handshake in progress. */
    data object Connecting : RemoteDesktopUiState

    /** Session is live; [screen] is the framebuffer + input bridge. */
    data class Connected(val screen: RemoteDesktopScreenState) : RemoteDesktopUiState

    /**
     * Connect failed. [failure] is the localization contract — the raw [detail] (wire diagnostics,
     * always English) is for logs, never the message shown on its own.
     */
    data class Error(
        val failure: VncFailure,
        val detail: String = "",
        /** The RDP connect step the failure happened at, when the transport named one. */
        val stage: RdpConnectStage? = null,
        /** How the network ended that step, when it was the network that ended it. */
        val drop: RdpDrop? = null,
    ) : RemoteDesktopUiState

    /**
     * The session closed not on our initiative (server drop / EOF). [screen] is the frozen last
     * frame; [cleanExit] true = the peer closed cleanly ("Session closed"), false = a transport
     * drop. [reason] carries the server's own words where it gave any. The user reconnects manually
     * (no silent auto-reconnect in v1).
     */
    data class Disconnected(
        val screen: RemoteDesktopScreenState,
        val cleanExit: Boolean,
        val reason: String = "",
    ) : RemoteDesktopUiState
}

/**
 * Binds a remote-desktop tab to a live session: [connect] opens one through [openSession] and
 * assembles a [RemoteDesktopScreenState]; a watcher moves to [RemoteDesktopUiState.Disconnected]
 * when the session closes on its own.
 *
 * The protocol arrives as a lambda rather than a transport type: VNC and RDP need different things
 * to dial (an optional password versus a user, a domain and a desktop size), and neither call
 * belongs in a controller shared by both. The framebuffer sibling of `ConnectionController`,
 * reusing its lifecycle discipline — separate session scope, teardown under [NonCancellable],
 * session reference dropped on disconnect.
 */
@Stable
class RemoteDesktopController(
    private val scope: CoroutineScope,
    private val newSessionScope: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
    },
) {
    var uiState: RemoteDesktopUiState by mutableStateOf(RemoteDesktopUiState.Connecting)
        private set

    private var connectJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    private var session: RemoteDesktopSession? = null
    private var retryConnect: (() -> Unit)? = null
    private var connectGeneration = 0L

    /**
     * Open a session through [openSession]. Ignored if one is already live.
     * [remoteResize] seeds the "Resize to window" toggle from the host profile;
     * [onRemoteResizeChanged] reports the user changing it (so the profile can be updated).
     */
    fun connect(
        remoteResize: Boolean = false,
        onRemoteResizeChanged: (Boolean) -> Unit = {},
        quality: RemoteDesktopQuality = RemoteDesktopQuality.Auto,
        onQualityChanged: (RemoteDesktopQuality) -> Unit = {},
        openSession: suspend () -> RemoteDesktopSession,
    ) = connectSession(remoteResize, onRemoteResizeChanged, quality, onQualityChanged, openSession = openSession)

    private fun connectSession(
        remoteResize: Boolean,
        onRemoteResizeChanged: (Boolean) -> Unit,
        quality: RemoteDesktopQuality,
        onQualityChanged: (RemoteDesktopQuality) -> Unit,
        clipboardShared: Boolean = true,
        viewOnly: Boolean = false,
        openSession: suspend () -> RemoteDesktopSession,
    ) {
        if (uiState is RemoteDesktopUiState.Connected || connectJob?.isActive == true) return
        retryConnect = {
            val previous = uiState as? RemoteDesktopUiState.Disconnected
            connectSession(
                previous?.screen?.remoteResize ?: remoteResize,
                onRemoteResizeChanged,
                previous?.screen?.quality ?: quality,
                onQualityChanged,
                previous?.screen?.clipboardShared ?: clipboardShared,
                previous?.screen?.viewOnly ?: viewOnly,
                openSession,
            )
        }
        val generation = ++connectGeneration
        uiState = RemoteDesktopUiState.Connecting
        connectJob = scope.launch {
            try {
                val opened = openSession()
                // A transport may finish non-cancellable setup after the tab has been closed.
                if (!isActive || generation != connectGeneration) {
                    withContext(NonCancellable) { runCatching { opened.close() } }
                    return@launch
                }
                val sScope = newSessionScope()
                session = opened
                sessionScope = sScope
                val screen = RemoteDesktopScreenState(
                    opened,
                    sScope,
                    remoteResizeInitial = remoteResize,
                    onRemoteResizeChanged = onRemoteResizeChanged,
                    qualityInitial = quality,
                    onQualityChanged = onQualityChanged,
                    clipboardSharedInitial = clipboardShared,
                    viewOnlyInitial = viewOnly,
                )
                uiState = RemoteDesktopUiState.Connected(screen)
                watchForClose(screen, sScope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive || generation != connectGeneration) return@launch
                releaseSession()
                // Typed reason for the UI; the wire text stays as diagnostics only.
                val step = rdpConnectFailureOf(e)
                uiState = RemoteDesktopUiState.Error(vncFailureOf(e), e.message.orEmpty(), step?.stage, step?.drop)
            }
        }
    }

    /** Retry in this tab only on an explicit user action. Credentials are released by disconnect. */
    fun reconnect() {
        if (uiState is RemoteDesktopUiState.Disconnected || uiState is RemoteDesktopUiState.Error) {
            retryConnect?.invoke()
        }
    }

    /**
     * Watches [screen] for closure (server drop / EOF): moves to [RemoteDesktopUiState.Disconnected]
     * with the frozen frame. Lives on the session scope, so our own [disconnect] (which cancels that
     * scope) kills it before it fires — this path is reached only on a server-side close.
     */
    private fun watchForClose(screen: RemoteDesktopScreenState, sScope: CoroutineScope) {
        sScope.launch {
            val closed = screen.close.filterNotNull().first()
            // Dispatch onto the main scope (like ConnectionController) so the transition doesn't race disconnect.
            scope.launch {
                if ((uiState as? RemoteDesktopUiState.Connected)?.screen === screen && sessionScope === sScope) {
                    uiState = RemoteDesktopUiState.Disconnected(screen, closed.cleanExit, closed.reason)
                    releaseSession()
                }
            }
        }
    }

    /** Close the session (if any) and reset. */
    fun disconnect() {
        connectGeneration++
        retryConnect = null
        connectJob?.cancel()
        connectJob = null
        releaseSession()
        uiState = RemoteDesktopUiState.Connecting
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
