package app.skerry.ui.teams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.sync.SyncStatus

/**
 * Pulls shared team hosts/snippets on the transition into [SyncStatus.Online].
 *
 * Team records travel a team channel, not the account channel: a share on another device does not
 * wake the account WS signal, so without an explicit pull the TEAMS sections stay empty until the
 * Teams tab is opened. Triggered on the transition into Online (not on mount): right after the
 * master password the session is still restoring, and an early `refresh()` would fail NotConnected
 * without retry. Sync errors are handled by the coordinator ([TeamsCoordinator.markError]).
 *
 * One call per screen showing team sections (mobile host list, desktop sidebar).
 */
@Composable
fun AutoPullTeamsOnOnline() {
    val teams = LocalTeams.current ?: return
    val online = LocalSync.current?.status?.collectAsState()?.value is SyncStatus.Online
    LaunchedEffect(online) { if (online) { teams.refresh(); teams.syncAll() } }
}
