package app.skerry.ui.share

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.share.SessionShareCodec
import app.skerry.shared.share.SessionShareHost
import app.skerry.shared.share.ShareFrame
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.share.shareMetaAad
import app.skerry.shared.sync.SyncException
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.VaultCrypto
import app.skerry.ui.sync.ShareLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the share button shows about this session. */
sealed interface ShareUiState {
    /** Not shared. */
    data object Off : ShareUiState

    /** Opening the relay socket. */
    data object Starting : ShareUiState

    /**
     * Live: the team's members can join [shareId]. [inputAllowed] is the host's own toggle, and
     * [inputLocked] means it cannot be turned on at all — a production-tagged session is watched
     * read-only, since a viewer's keystrokes would bypass the confirmation the guard exists for.
     */
    data class Live(
        val teamId: String,
        val teamName: String,
        val shareId: String,
        val viewers: Int,
        /** Accounts watching right now, in join order — the avatars beside the session. */
        val viewerAccounts: List<String> = emptyList(),
        val inputAllowed: Boolean,
        val inputLocked: Boolean,
        /** Account that typed most recently — the "… is typing" hint over the terminal. */
        val typingBy: String? = null,
        /** Whether a viewer is waiting for an answer to its "request remote control". */
        val controlRequestPending: Boolean = false,
        /**
         * Who is waiting, when the relay named the socket the request arrived on. Null against a
         * relay older than the naming protocol: the request is still shown, it just names nobody
         * ([controlRequestPending] is what says there is one).
         */
        val controlRequestBy: String? = null,
    ) : ShareUiState

    /** The relay refused or the socket died; [reason] is localized by the UI (`shareFailureText`). */
    data class Failed(val reason: ShareFailure) : ShareUiState
}

/**
 * Why sharing could not start or could not continue. A typed reason rather than a message, for the
 * same reason as [app.skerry.shared.sync.SyncFailureReason]: building the sentence in the
 * controller would bake in one language.
 */
enum class ShareFailure {
    /** No sync account connected, or its vault is locked — there is nothing to share through. */
    NotConnected,

    /** The team's key hasn't arrived on this device, so frames could not be sealed. */
    NoTeamKey,

    /** The relay was unreachable or the socket dropped. */
    Network,

    /** The server refused: not a member any more, the id is taken, or the team is at its cap. */
    Rejected,

    /** The server failed on its side. */
    ServerError,
}

/**
 * Shares one live terminal with a team: streams its output to the relay and, when the host allows
 * it, applies viewers' keystrokes back into the shell.
 *
 * One share at a time per app — sharing is an explicit act about the session in front of you, and a
 * second silent stream from another tab is exactly what a user would not expect. [share] on an
 * already-live share replaces it (the previous socket is closed first).
 *
 * The controller lives on an app-level scope (the share must survive tab switches), so it owns
 * [stop]: leaving the screen must not leave a shell streaming to a team.
 */
@Stable
class SessionShareController(
    /**
     * The relay and the session it belongs to, as ONE value — two suppliers can be read either side of a
     * connect to another server and pair one server's client with the other's session (issue #240).
     */
    private val liveLink: () -> ShareLink?,
    private val teamKey: (String) -> DataKey?,
    private val crypto: VaultCrypto,
    private val newShareId: () -> String,
    private val scope: CoroutineScope,
    /** Monotonic milliseconds for the typing hint's lifetime; injected so tests don't wait. */
    private val nowMillis: () -> Long = { 0 },
) {
    var state: ShareUiState by mutableStateOf(ShareUiState.Off)
        private set

    private var job: Job? = null
    private var live: SessionShareHost? = null

    // Which share() call the state belongs to. Stopping a share is asynchronous (the goodbye is sent
    // before the socket coroutine unwinds), so a re-share can start while the previous one is still
    // finishing; without this the old coroutine's teardown would clear the new share's state and
    // leave a live, unstoppable stream nobody can see.
    private var generation: Long = 0

    // When the last keystroke arrived, so the hint can fade (see [expireTypingHint]).
    private var typingSince: Long = 0

    /** The pane whose output is being shared, so the UI can mark it and refuse a second share. */
    var sharedPaneId: String? by mutableStateOf(null)
        private set

    /**
     * Starts sharing [source] with team [teamId]. [label] names the session for the team (sealed
     * under the team key — the server never learns which host it is). [readOnlyOnly] locks input off
     * for a production-tagged session.
     */
    fun share(teamId: String, teamName: String, paneId: String, label: String, source: ShareSource, readOnlyOnly: Boolean) {
        val link = liveLink()
        val key = teamKey(teamId)
        if (link == null) {
            state = ShareUiState.Failed(ShareFailure.NotConnected)
            return
        }
        val (syncSession, shareClient) = link
        if (key == null) {
            state = ShareUiState.Failed(ShareFailure.NoTeamKey)
            return
        }
        stop()
        val mine = ++generation
        val shareId = newShareId()
        val codec = SessionShareCodec(crypto, shareId)
        state = ShareUiState.Starting
        sharedPaneId = paneId
        job = scope.launch {
            try {
                val meta = crypto.seal(key, label.take(MAX_LABEL_CHARS).encodeToByteArray(), shareMetaAad(shareId))
                shareClient.hostShare(syncSession, teamId, shareId, meta) { channel ->
                    val host = SessionShareHost(
                        codec = codec,
                        teamKey = key,
                        channel = channel,
                        output = source.output,
                        toShell = source.toShell,
                        geometry = source.geometry,
                        allowInput = { (state as? ShareUiState.Live)?.inputAllowed == true },
                        onViewers = { accounts ->
                            updateLive { it.copy(viewers = accounts.size, viewerAccounts = accounts) }
                        },
                        onTyping = { account ->
                            typingSince = nowMillis()
                            updateLive { it.copy(typingBy = account) }
                        },
                        // A request is never granted on its own: the host answers it, exactly like
                        // the toggle. Until then the viewer stays read-only.
                        onControlRequest = { account ->
                            updateLive { it.copy(controlRequestPending = true, controlRequestBy = account) }
                        },
                    )
                    live = host
                    state = ShareUiState.Live(
                        teamId = teamId,
                        teamName = teamName,
                        shareId = shareId,
                        viewers = 0,
                        inputAllowed = false,
                        inputLocked = readOnlyOnly,
                    )
                    // The shell can end while the socket is perfectly healthy (exit, dropped
                    // connection): end the broadcast with it instead of streaming a dead screen.
                    // Cancelled with the socket coroutine when the share stops for any other reason.
                    val shellWatch = launch {
                        source.sessionState.first { it is TerminalState.Closed }
                        runCatching { host.stop() }
                    }
                    try {
                        host.run()
                    } finally {
                        shellWatch.cancel()
                    }
                }
                // The socket closed on its own (host stopped, session gone, server restart).
                if (mine == generation && state !is ShareUiState.Failed) state = ShareUiState.Off
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == generation) state = ShareUiState.Failed(shareFailure(e))
            } finally {
                // Only the current share owns these fields: a later share() has already published
                // its own host and pane, and clearing them here would orphan its live socket.
                if (mine == generation) {
                    live = null
                    sharedPaneId = null
                }
            }
        }
    }

    /**
     * Lets viewers type, or takes that back, and tells them so — a viewer that isn't told keeps
     * offering a keyboard that goes nowhere. Ignored while the session is locked to read-only.
     */
    fun setInputAllowed(allowed: Boolean) {
        val current = state as? ShareUiState.Live ?: return
        if (current.inputLocked) return
        state = current.copy(inputAllowed = allowed, controlRequestPending = false, controlRequestBy = null)
        val host = live ?: return
        scope.launch { runCatching { host.announceControl(allowed) } }
    }

    /** Answers a viewer's request for control: grant lets everyone watching type, deny just clears it. */
    fun answerControlRequest(grant: Boolean) {
        if (grant) setInputAllowed(true) else updateLive { it.copy(controlRequestPending = false, controlRequestBy = null) }
    }

    /**
     * Clears the "… is typing" hint once it has been up for [TYPING_HINT_MS] with nothing new. Driven
     * by the UI's own ticker rather than a timer here: the controller has no clock of its own, and a
     * coroutine per keystroke would be a lot of coroutines.
     */
    fun expireTypingHint(now: Long) {
        if (now - typingSince < TYPING_HINT_MS) return
        updateLive { if (it.typingBy == null) it else it.copy(typingBy = null) }
    }

    /** Re-announces the host's grid after a resize, so viewers keep rendering the same screen. */
    fun announceGeometry() {
        val host = live ?: return
        scope.launch { runCatching { host.announceGeometry() } }
    }

    /** Stops sharing; viewers are told the session ended. Safe to call when nothing is shared. */
    fun stop() {
        generation++
        val host = live
        val running = job
        job = null
        live = null
        sharedPaneId = null
        if (state !is ShareUiState.Failed) state = ShareUiState.Off
        if (host == null) {
            running?.cancel()
            return
        }
        // Say goodbye first, then let the socket's own coroutine finish: cancelling outright would
        // leave viewers staring at a frozen screen until the relay noticed the socket was gone.
        scope.launch {
            runCatching { host.stop() }
            running?.cancel()
        }
    }

    /** Clears a failure notice so the button returns to its idle state. */
    fun clearFailure() {
        if (state is ShareUiState.Failed) state = ShareUiState.Off
    }

    /** Relay/network failure -> a typed reason the UI can put a sentence to. */
    private fun shareFailure(e: Exception): ShareFailure = when ((e as? SyncException)?.kind) {
        SyncException.Kind.FORBIDDEN, SyncException.Kind.NOT_FOUND, SyncException.Kind.CONFLICT,
        SyncException.Kind.UNAUTHORIZED, SyncException.Kind.TOO_MANY_REQUESTS -> ShareFailure.Rejected
        SyncException.Kind.SERVER_ERROR -> ShareFailure.ServerError
        else -> ShareFailure.Network
    }

    private inline fun updateLive(block: (ShareUiState.Live) -> ShareUiState.Live) {
        val current = state as? ShareUiState.Live ?: return
        state = block(current)
    }

    private companion object {
        /** The session label is a name, not a payload; the relay caps what it will store anyway. */
        const val MAX_LABEL_CHARS = 120

        /** How long "… is typing" stays up after the last keystroke from a viewer. */
        const val TYPING_HINT_MS = 2_000L
    }
}

/**
 * The terminal being shared, as the controller needs it: its live PTY output, a way to type into
 * it, and its current grid. Kept as a plain holder so the controller doesn't depend on the terminal
 * UI state and stays testable.
 */
class ShareSource(
    val output: kotlinx.coroutines.flow.Flow<ByteArray>,
    val toShell: suspend (ByteArray) -> Unit,
    val geometry: () -> ShareFrame.Resize,
    /**
     * Liveness of the shell behind the share. [output] is a hot stream that never ends, so a shell
     * that exits (or a connection that drops) is silent rather than finished: without this the
     * broadcast would stay up, showing the team the last frame of a session that is gone.
     */
    val sessionState: kotlinx.coroutines.flow.StateFlow<app.skerry.shared.terminal.TerminalState>,
)
