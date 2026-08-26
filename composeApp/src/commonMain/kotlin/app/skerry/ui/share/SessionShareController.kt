package app.skerry.ui.share

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.share.SessionShareCodec
import app.skerry.shared.share.SessionShareHost
import app.skerry.shared.share.ShareChannel
import app.skerry.shared.share.ShareFrame
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.share.shareMetaAad
import app.skerry.shared.sync.SyncException
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.VaultCrypto
import app.skerry.ui.sync.ShareLink
import kotlinx.coroutines.CancellationException
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
    /** How often a viewer may raise "wants control" (#343); injected so tests don't wait a minute. */
    private val controlGate: ControlRequestGate = ControlRequestGate(),
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

    // Every read-modify-write of [state] runs under this monitor, and so does every read and write
    // of [generation]. The socket coroutine (Dispatchers.Default) and the host's own toggle (the UI
    // thread) each build a copy from a snapshot they read a moment earlier: unguarded, the viewer
    // count of a frame that arrived while the host was taking input back writes `inputAllowed` true
    // again, and the viewer keeps typing into a shell the host already closed to them.
    private val stateLock = SynchronizedObject()

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
        controlGate.reset() // a new share: nothing asked in it, nothing refused
        val shareId = newShareId()
        val codec = SessionShareCodec(crypto, shareId)
        val mine = synchronized(stateLock) {
            state = ShareUiState.Starting
            sharedPaneId = paneId
            ++generation
        }
        job = scope.launch {
            try {
                val meta = crypto.seal(key, label.take(MAX_LABEL_CHARS).encodeToByteArray(), shareMetaAad(shareId))
                shareClient.hostShare(syncSession, teamId, shareId, meta) { channel ->
                    val host = hostFor(codec, key, channel, source, mine)
                    // `stop()` only asks the coroutine to cancel, and this stretch does not suspend:
                    // a share the host ended while the relay handshake was in flight can still get
                    // here, and publishing would put its team and share id over the one the user
                    // actually started.
                    val published = synchronized(stateLock) {
                        if (mine != generation) {
                            false
                        } else {
                            live = host
                            state = ShareUiState.Live(
                                teamId = teamId,
                                teamName = teamName,
                                shareId = shareId,
                                viewers = 0,
                                inputAllowed = false,
                                inputLocked = readOnlyOnly,
                            )
                            true
                        }
                    }
                    if (!published) return@hostShare
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
                synchronized(stateLock) {
                    if (mine == generation && state !is ShareUiState.Failed) state = ShareUiState.Off
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(stateLock) {
                    if (mine == generation) state = ShareUiState.Failed(shareFailure(e))
                }
            } finally {
                // Only the current share owns these fields: a later share() has already published
                // its own host and pane, and clearing them here would orphan its live socket.
                synchronized(stateLock) {
                    if (mine == generation) {
                        live = null
                        sharedPaneId = null
                    }
                }
            }
        }
    }

    /**
     * The host side of one share. Split out of [share] so the callbacks — which is what the
     * generation guard is about — read on their own rather than as a wall inside the socket block.
     * [mine] is the generation [share] took when it opened this socket.
     */
    private fun hostFor(
        codec: SessionShareCodec,
        key: DataKey,
        channel: ShareChannel,
        source: ShareSource,
        mine: Long,
    ): SessionShareHost {
        // Every callback below belongs to *this* share and to no other, which is what [mine] is
        // checked against. A relay that stops reading leaves the goodbye frame suspended, so the
        // old socket stays parked in its read loop — and its callbacks close over no share of their
        // own. Unguarded, a viewer of a share the host ended would be answered against whatever
        // share came next: their frames typed into its shell the moment its host allowed input,
        // their accounts drawn beside its name, their questions raised over it.
        return SessionShareHost(
            codec = codec,
            teamKey = key,
            channel = channel,
            output = source.output,
            toShell = source.toShell,
            geometry = source.geometry,
            allowInput = {
                synchronized(stateLock) { mine == generation && (state as? ShareUiState.Live)?.inputAllowed == true }
            },
            onViewers = { accounts ->
                updateOurs(mine) {
                    val asked = it.controlRequestBy
                    // A question from someone who has stopped watching is not a question
                    // any more: granting it would let whoever is watching now type, and
                    // the name over the Grant button would be of a colleague who left.
                    val gone = asked != null && asked !in accounts
                    if (gone) controlGate.withdrawn()
                    it.copy(
                        viewers = accounts.size,
                        viewerAccounts = accounts,
                        controlRequestPending = it.controlRequestPending && !gone,
                        controlRequestBy = if (gone) null else asked,
                    )
                }
            },
            onTyping = { account ->
                updateOurs(mine) {
                    typingSince = nowMillis()
                    it.copy(typingBy = account)
                }
            },
            // A request is never granted on its own: the host answers it, exactly like
            // the toggle. Until then the viewer stays read-only. What reaches the panel
            // is bounded by [controlGate] — one question at a time, and an answer the
            // host has already given is not asked again straight away (#343).
            onControlRequest = { account ->
                updateOurs(mine) {
                    // Read and decided in one step, off the same snapshot the copy is
                    // built from: this runs on the socket coroutine while the host's own
                    // toggle runs on the UI thread, and a verdict taken from an earlier
                    // read would put a "wants control" row over a session the viewer was
                    // let into in the meantime. Nothing to decide either when the session
                    // is locked read-only — no answer the host could give would change it.
                    if (it.inputAllowed || it.inputLocked || !controlGate.admits(account)) it
                    else it.copy(controlRequestPending = true, controlRequestBy = account)
                }
            },
        )
    }

    /**
     * Lets viewers type, or takes that back, and tells them so — a viewer that isn't told keeps
     * offering a keyboard that goes nowhere. Ignored while the session is locked to read-only.
     */
    fun setInputAllowed(allowed: Boolean) {
        var announce = false
        synchronized(stateLock) {
            val current = state as? ShareUiState.Live ?: return@synchronized
            if (current.inputLocked) {
                // A locked session cannot grant, but the press is still an answer: leaving it
                // unanswered keeps the row over the shell and holds the gate's one slot for the
                // rest of the share.
                controlGate.answered(granted = false)
                state = current.copy(controlRequestPending = false, controlRequestBy = null)
                return@synchronized
            }
            // Taking input back with a request on screen answers it too, and answers it with a no.
            controlGate.answered(granted = allowed)
            state = current.copy(inputAllowed = allowed, controlRequestPending = false, controlRequestBy = null)
            announce = true
        }
        if (!announce) return
        val host = live ?: return
        scope.launch { runCatching { host.announceControl(allowed) } }
    }

    /**
     * Answers a viewer's request for control: grant lets everyone watching type, deny just clears
     * it — and holds the asker off, so the row the host dismissed does not come back on the next
     * frame (#343).
     */
    fun answerControlRequest(grant: Boolean) {
        if (grant) {
            setInputAllowed(true)
        } else {
            controlGate.answered(granted = false)
            updateLive { it.copy(controlRequestPending = false, controlRequestBy = null) }
        }
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
        var stopping: SessionShareHost? = null
        var running: Job? = null
        synchronized(stateLock) {
            generation++
            stopping = live
            running = job
            job = null
            live = null
            sharedPaneId = null
            if (state !is ShareUiState.Failed) state = ShareUiState.Off
        }
        val host = stopping
        val previous = running
        if (host == null) {
            previous?.cancel()
            return
        }
        // Say goodbye first, then let the socket's own coroutine finish: cancelling outright would
        // leave viewers staring at a frozen screen until the relay noticed the socket was gone. A
        // relay that stops reading never takes that goodbye, so it is bounded — otherwise one
        // wedged relay parks a coroutine, a socket and a subscription on the pane's output for as
        // long as the app runs, once per share the user stops.
        scope.launch {
            runCatching { withTimeout(GOODBYE_TIMEOUT_MS) { host.stop() } }
            previous?.cancel()
        }
    }

    /** Clears a failure notice so the button returns to its idle state. */
    fun clearFailure() = synchronized(stateLock) {
        if (state is ShareUiState.Failed) state = ShareUiState.Off
    }

    /** Relay/network failure -> a typed reason the UI can put a sentence to. */
    private fun shareFailure(e: Exception): ShareFailure = when ((e as? SyncException)?.kind) {
        SyncException.Kind.FORBIDDEN, SyncException.Kind.NOT_FOUND, SyncException.Kind.CONFLICT,
        SyncException.Kind.UNAUTHORIZED, SyncException.Kind.TOO_MANY_REQUESTS -> ShareFailure.Rejected
        SyncException.Kind.SERVER_ERROR -> ShareFailure.ServerError
        else -> ShareFailure.Network
    }

    /** Read and write the live state as one step; nothing happens when nothing is being shared. */
    private inline fun updateLive(block: (ShareUiState.Live) -> ShareUiState.Live) = synchronized(stateLock) {
        val current = state as? ShareUiState.Live ?: return@synchronized
        state = block(current)
    }

    /** [updateLive] for a callback of the share [mine]: a share the host ended writes nothing. */
    private inline fun updateOurs(mine: Long, block: (ShareUiState.Live) -> ShareUiState.Live) =
        synchronized(stateLock) {
            if (mine != generation) return@synchronized
            val current = state as? ShareUiState.Live ?: return@synchronized
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
 * How long the goodbye gets before the share is cut loose. Long enough for a healthy relay to take
 * the End frame, short enough that a wedged one does not keep the pane's output subscribed and the
 * socket's coroutine alive for the rest of the session.
 */
internal const val GOODBYE_TIMEOUT_MS = 3_000L

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
