package app.skerry.ui.share

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.share.SessionShareClient
import app.skerry.shared.share.SessionShareCodec
import app.skerry.shared.share.SharedSessionInfo
import app.skerry.shared.share.SharedSessionViewer
import app.skerry.shared.share.shareMetaAad
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.VaultCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One shared session offered to a team, as the directory shows it. */
data class SharedSessionUi(
    val teamId: String,
    val teamName: String,
    val shareId: String,
    val hostAccountId: String,
    /** The session's label, opened with the team key; empty when it didn't open (stale key). */
    val label: String,
    val startedAt: Long,
    val viewers: Int,
)

/**
 * The directory of live shared sessions across the account's teams, and the act of joining one.
 *
 * The list is re-read on demand and on the server's `shares:{teamId}` push
 * ([app.skerry.ui.teams.TeamsCoordinator.onSharesChanged]) — there is no local record of it: a
 * share exists only as long as its host's socket, so a cached entry would be a lie the moment the
 * host closed their laptop.
 */
@Stable
class SharedSessionsController(
    private val client: () -> SessionShareClient?,
    private val session: () -> SyncSession?,
    /** Teams to look in, as (id, name) — the ones this account is an active member of. */
    private val teams: () -> List<Pair<String, String>>,
    private val teamKey: (String) -> DataKey?,
    private val crypto: VaultCrypto,
    private val scope: CoroutineScope,
) {
    var shares: List<SharedSessionUi> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    /** Set when the last refresh failed; cleared by the next successful one. */
    var failure: ShareFailure? by mutableStateOf(null)
        private set

    private var refreshJob: Job? = null

    // Cancelling a refresh doesn't wait for it to unwind, so a superseded one must not clear the
    // spinner or overwrite the newer listing on its way out.
    private var refreshGeneration: Long = 0

    /**
     * Viewers being watched, by the pane they were opened in — the terminal overlay reads this to
     * offer "request remote control" and to say whether typing is allowed yet.
     */
    var watching: Map<String, SharedSessionViewer> by mutableStateOf(emptyMap())
        private set

    /** Binds a joined viewer to the pane the caller opened for it. */
    fun trackWatching(paneId: String, viewer: SharedSessionViewer) {
        watching = watching + (paneId to viewer)
        scope.launch {
            viewer.state.first { it is app.skerry.shared.terminal.TerminalState.Closed }
            watching = watching - paneId
        }
    }

    /** Re-reads every team's directory. A refresh already in flight is replaced. */
    fun refresh() {
        val shareClient = client()
        val syncSession = session()
        if (shareClient == null || syncSession == null) {
            shares = emptyList()
            return
        }
        refreshJob?.cancel()
        val mine = ++refreshGeneration
        refreshJob = scope.launch {
            loading = true
            try {
                val found = mutableListOf<SharedSessionUi>()
                var failed: ShareFailure? = null
                for ((teamId, teamName) in teams()) {
                    val key = teamKey(teamId) ?: continue
                    try {
                        shareClient.listShares(syncSession, teamId).forEach { info ->
                            found += info.toUi(teamId, teamName, key)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One unreachable team must not blank the whole directory: keep what the
                        // others returned and report that something didn't answer.
                        failed = shareFailureOf(e)
                    }
                }
                if (mine == refreshGeneration) {
                    shares = found.sortedByDescending { it.startedAt }
                    failure = failed
                }
            } finally {
                if (mine == refreshGeneration) loading = false
            }
        }
    }

    /** Drops the directory (vault locked, account disconnected). */
    fun clear() {
        refreshGeneration++
        refreshJob?.cancel()
        refreshJob = null
        shares = emptyList()
        failure = null
    }

    /**
     * Joins [share] and hands the resulting live session to [onOpened] (the caller opens a tab for
     * it). The relay socket stays open until the viewer is closed — which the pane does when it is
     * closed, so watching ends with the tab.
     *
     * [onFailed] fires when the join itself failed; a viewer that ends later reports through its own
     * terminal state, exactly like a dropped connection.
     */
    fun join(
        share: SharedSessionUi,
        onOpened: (SharedSessionViewer) -> Unit,
        onFailed: (ShareFailure) -> Unit,
    ) {
        val shareClient = client()
        val syncSession = session()
        val key = teamKey(share.teamId)
        if (shareClient == null || syncSession == null) {
            reportFailure(ShareFailure.NotConnected, onFailed)
            return
        }
        if (key == null) {
            reportFailure(ShareFailure.NoTeamKey, onFailed)
            return
        }
        val codec = SessionShareCodec(crypto, share.shareId)
        scope.launch {
            // Handed out from inside the socket block: the viewer is only usable while the socket is
            // open, and the block is what keeps it open.
            val opened = CompletableDeferred<Unit>()
            try {
                shareClient.joinShare(syncSession, share.teamId, share.shareId) { channel ->
                    val viewer = SharedSessionViewer(codec, key, channel, this, syncSession.accountId)
                    // Name ourselves before anything else: the host's "… is typing" hint has no
                    // other way to tie this socket to a colleague.
                    runCatching { viewer.announce() }
                    onOpened(viewer)
                    opened.complete(Unit)
                    // Keep the socket alive until the viewer's own reader finishes (the host ended
                    // the share, or the pane closed the viewer).
                    viewer.state.collect { state ->
                        if (state is app.skerry.shared.terminal.TerminalState.Closed) throw ViewerClosed()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ViewerClosed) {
                // Normal end of watching.
            } catch (e: Exception) {
                if (!opened.isCompleted) reportFailure(shareFailureOf(e), onFailed)
            }
        }
    }

    /** A failed join is both told to the caller and left on the list, where the user is looking. */
    private fun reportFailure(reason: ShareFailure, onFailed: (ShareFailure) -> Unit) {
        failure = reason
        onFailed(reason)
    }

    private fun SharedSessionInfo.toUi(teamId: String, teamName: String, key: DataKey) = SharedSessionUi(
        teamId = teamId,
        teamName = teamName,
        shareId = shareId,
        hostAccountId = hostAccountId,
        // The label is sealed under the team key like every frame; a key that no longer opens it
        // (rotated since the share started) leaves the entry without a name, not out of the list.
        // Its own AAD domain, not the frames' one: otherwise the relay could hand back a captured
        // output frame here and have real terminal output rendered as the session's name.
        label = crypto.open(key, meta, shareMetaAad(shareId))
            ?.decodeToString()?.take(MAX_LABEL_CHARS).orEmpty(),
        startedAt = startedAt,
        viewers = viewers,
    )

    private fun shareFailureOf(e: Exception): ShareFailure = when ((e as? SyncException)?.kind) {
        SyncException.Kind.FORBIDDEN, SyncException.Kind.NOT_FOUND, SyncException.Kind.CONFLICT,
        SyncException.Kind.UNAUTHORIZED, SyncException.Kind.TOO_MANY_REQUESTS -> ShareFailure.Rejected
        SyncException.Kind.SERVER_ERROR -> ShareFailure.ServerError
        else -> ShareFailure.Network
    }

    /** Internal signal that the viewer finished, so the socket block can return. */
    private class ViewerClosed : Exception()

    private companion object {
        const val MAX_LABEL_CHARS = 120
    }
}
