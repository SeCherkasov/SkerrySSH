package app.skerry.ui.runbook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.terminal.epochMillis

/** One host the user picked for a run in the start dialog. */
sealed interface RunbookLaunchTarget {

    /** How the target names itself in the dialog and in the run. */
    val label: String

    /** A session that is already open — the run can start on it as it stands. */
    data class Session(val paneId: String, override val label: String) : RunbookLaunchTarget

    /** A profile from the catalog: a session has to be opened before the run can touch it. */
    data class CatalogHost(val hostId: String, override val label: String) : RunbookLaunchTarget
}

/** Where getting a run's hosts ready stands. */
sealed interface RunbookLaunchState {

    /** Nothing being launched. */
    data object Idle : RunbookLaunchState

    /** Sessions are being opened; [pending] names the hosts not up yet. */
    data class Connecting(val pending: List<String>) : RunbookLaunchState

    /** Every target is live: [paneIds] in the order the user picked them. */
    data class Ready(val paneIds: List<String>) : RunbookLaunchState

    /**
     * The wait ran out with [unreachable] hosts still down. [ready] is what did come up — the dialog
     * offers to run on those rather than deciding for the user.
     */
    data class Unreachable(val unreachable: List<String>, val ready: List<String>) : RunbookLaunchState
}

/**
 * Gets a run's hosts ready: the sessions already open are usable as they are, the catalog hosts are
 * connected first, and the run starts only once all of them are up.
 *
 * The wait is bounded. A host that is down, behind a dead jump, or waiting on a password nobody is
 * going to type would otherwise hold the whole procedure indefinitely with nothing on screen saying
 * why — so after [timeoutMillis] the launch says which hosts never answered and leaves the decision
 * to run on the rest to the user.
 *
 * Order is kept exactly as picked: with
 * [app.skerry.shared.runbook.RunbookParallelism.ONE_HOST_AT_A_TIME] that order *is* the rollout
 * sequence.
 */
@Stable
class RunbookLaunchController(
    private val now: () -> Long = ::epochMillis,
    /**
     * How long a catalog host is given to come up. Generous: it covers the password prompt, the
     * production-guard confirmation and a slow jump host, all of which are the user's own doing.
     */
    private val timeoutMillis: Long = 60_000L,
) {
    var state: RunbookLaunchState by mutableStateOf(RunbookLaunchState.Idle)
        private set

    private var targets: List<RunbookLaunchTarget> = emptyList()
    private var startedAt: Long = 0L

    /**
     * Starts getting [targets] ready, dialling every catalog host among them exactly once through
     * [openHost] (which carries the app's own connect path: stored secret or password prompt,
     * production guard, ProxyJump). An empty pick changes nothing.
     */
    fun begin(targets: List<RunbookLaunchTarget>, openHost: (hostId: String) -> Unit) {
        if (targets.isEmpty()) {
            state = RunbookLaunchState.Idle
            return
        }
        this.targets = targets
        this.startedAt = now()
        state = RunbookLaunchState.Connecting(targets.filterIsInstance<RunbookLaunchTarget.CatalogHost>().map { it.label })
        targets.filterIsInstance<RunbookLaunchTarget.CatalogHost>().forEach { openHost(it.hostId) }
        // A pick of already-open sessions is ready immediately; this also settles the state above.
        refresh { null }
    }

    /**
     * Re-reads where the launch stands. [connectedPaneFor] resolves a catalog host to a connected
     * pane of its own, or `null` while it isn't up. Called whenever the session list changes.
     */
    fun refresh(connectedPaneFor: (hostId: String) -> String?) {
        if (state is RunbookLaunchState.Idle || state is RunbookLaunchState.Ready) return
        val resolved = targets.map { target ->
            when (target) {
                is RunbookLaunchTarget.Session -> target to target.paneId
                is RunbookLaunchTarget.CatalogHost -> target to connectedPaneFor(target.hostId)
            }
        }
        val pending = resolved.filter { (_, pane) -> pane == null }
        if (pending.isEmpty()) {
            state = RunbookLaunchState.Ready(resolved.mapNotNull { (_, pane) -> pane })
            return
        }
        if (now() - startedAt >= timeoutMillis) {
            state = RunbookLaunchState.Unreachable(
                unreachable = pending.map { (target, _) -> target.label },
                ready = resolved.mapNotNull { (_, pane) -> pane },
            )
            return
        }
        state = RunbookLaunchState.Connecting(pending.map { (target, _) -> target.label })
    }

    /** Abandons the launch; sessions already opened for it are left alone, as any other tab would be. */
    fun cancel() {
        targets = emptyList()
        state = RunbookLaunchState.Idle
    }
}
