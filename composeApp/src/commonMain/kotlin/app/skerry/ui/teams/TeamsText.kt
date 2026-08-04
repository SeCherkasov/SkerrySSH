package app.skerry.ui.teams

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_runbook_steps
import app.skerry.ui.generated.resources.lib_teams_seen_never
import app.skerry.ui.generated.resources.lib_teams_seen_now
import app.skerry.ui.generated.resources.lib_teams_seen_today
import app.skerry.ui.generated.resources.lib_teams_seen_yesterday
import app.skerry.ui.generated.resources.lib_teams_synced_day
import app.skerry.ui.generated.resources.lib_teams_synced_hour
import app.skerry.ui.generated.resources.lib_teams_synced_min
import app.skerry.ui.generated.resources.lib_teams_synced_sec
import org.jetbrains.compose.resources.stringResource

// Localized wording for the Teams screen's numbers. The rules that decide *which* wording applies
// live in TeamsScreenModel (and are tested there); this file only puts words to them.

/** "synced 12 s ago" for an elapsed time since the last completed team sync (see [syncedAgo]). */
@Composable
internal fun syncedAgoText(elapsedMs: Long): String = when (val ago = syncedAgo(elapsedMs)) {
    is SyncedAgo.Seconds -> stringResource(Res.string.lib_teams_synced_sec, ago.value)
    is SyncedAgo.Minutes -> stringResource(Res.string.lib_teams_synced_min, ago.value)
    is SyncedAgo.Hours -> stringResource(Res.string.lib_teams_synced_hour, ago.value)
    is SyncedAgo.Days -> stringResource(Res.string.lib_teams_synced_day, ago.value)
}

/** Second line of a runbook in the share lists — its length is what distinguishes two of them. */
@Composable
internal fun runbookSummary(steps: Int): String = stringResource(Res.string.lib_teams_runbook_steps, steps)

/** Localized "last seen" cell for [at]; see [lastSeen] for how the case is chosen. */
@Composable
internal fun lastSeenText(at: Long?, now: Long): String = when (val seen = lastSeen(at, now)) {
    LastSeen.Never -> stringResource(Res.string.lib_teams_seen_never)
    LastSeen.Now -> stringResource(Res.string.lib_teams_seen_now)
    is LastSeen.Today -> stringResource(Res.string.lib_teams_seen_today, seen.time)
    is LastSeen.Yesterday -> stringResource(Res.string.lib_teams_seen_yesterday, seen.time)
    is LastSeen.Earlier -> seen.stamp
}
