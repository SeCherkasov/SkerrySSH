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

/** Where getting a run's session ready stands. */
sealed interface RunbookLaunchState {

    /** Nothing being launched. */
    data object Idle : RunbookLaunchState

    /** The session is being opened; [host] is the one being dialled. */
    data class Connecting(val host: String) : RunbookLaunchState

    /** The session is live and the run can start in [paneId]. */
    data class Ready(val paneId: String) : RunbookLaunchState

    /** The wait ran out and [host] never answered. */
    data class Unreachable(val host: String) : RunbookLaunchState
}

/**
 * Gets a run's session ready: one already open is usable as it stands, a catalog host is connected
 * first and the run starts once it is up.
 *
 * The wait is bounded. A host that is down, behind a dead jump, or waiting on a password nobody is
 * going to type would otherwise hold the procedure indefinitely with nothing on screen saying why —
 * so after [timeoutMillis] the launch says so and the user decides what to do next.
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

    private var target: RunbookLaunchTarget? = null
    private var startedAt: Long = 0L

    /**
     * Starts getting [target] ready, dialling a catalog host exactly once through [openHost] (which
     * carries the app's own connect path: stored secret or password prompt, production guard,
     * ProxyJump).
     */
    fun begin(target: RunbookLaunchTarget, openHost: (hostId: String) -> Unit) {
        this.target = target
        this.startedAt = now()
        when (target) {
            is RunbookLaunchTarget.Session -> state = RunbookLaunchState.Ready(target.paneId)
            is RunbookLaunchTarget.CatalogHost -> {
                state = RunbookLaunchState.Connecting(target.label)
                openHost(target.hostId)
            }
        }
    }

    /**
     * Re-reads where the launch stands. [connectedPaneFor] resolves the catalog host to a connected
     * pane of its own, or `null` while it isn't up. Called whenever the session list changes.
     */
    fun refresh(connectedPaneFor: (hostId: String) -> String?) {
        val target = target as? RunbookLaunchTarget.CatalogHost ?: return
        if (state !is RunbookLaunchState.Connecting) return
        val pane = connectedPaneFor(target.hostId)
        state = when {
            pane != null -> RunbookLaunchState.Ready(pane)
            now() - startedAt >= timeoutMillis -> RunbookLaunchState.Unreachable(target.label)
            else -> RunbookLaunchState.Connecting(target.label)
        }
    }

    /** Abandons the launch; a session already opened for it is left alone, as any other tab would be. */
    fun cancel() {
        target = null
        state = RunbookLaunchState.Idle
    }
}
