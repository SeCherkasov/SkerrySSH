package app.skerry.ui.sync

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.stail_sync_online
import app.skerry.ui.generated.resources.stail_sync_offline
import app.skerry.ui.generated.resources.stail_syncing
import app.skerry.ui.generated.resources.stail_sync_paused
import app.skerry.ui.generated.resources.stail_sync_error
import org.jetbrains.compose.resources.stringResource

/** Semantic level of the sync indicator (UI colors: OK→moss, WARN→amber, ERROR→sunset). */
enum class SyncIndicatorLevel { OK, WARN, ERROR }

/** What to show in the sync indicator (desktop status bar / mobile header). null = hide. */
data class SyncIndicator(val icon: String, val label: String, val level: SyncIndicatorLevel)

/**
 * Testable projection of sync state onto the status indicator. The lead signal is session status
 * ([SyncStatus]); server reachability only distinguishes online/offline while a session is active.
 *
 * - [SyncStatus.Online] + REACHABLE → "Sync online" (OK); + UNREACHABLE → "Sync offline" (ERROR).
 * - [SyncStatus.Busy] → "Syncing…" (WARN).
 * - [SyncStatus.Configured] → "Sync paused" (WARN): linked but no session — not "online".
 * - [SyncStatus.Failed] → "Sync error" (ERROR).
 * - Not configured / not yet pinged ([SyncStatus.Disabled] / [ServerReachable.UNKNOWN]) → hide.
 */
fun syncIndicator(status: SyncStatus?, reachable: ServerReachable): SyncIndicator? {
    if (status == null || status == SyncStatus.Disabled || reachable == ServerReachable.UNKNOWN) return null
    return when (status) {
        is SyncStatus.Online ->
            if (reachable == ServerReachable.REACHABLE) SyncIndicator("cloud_done", "Sync online", SyncIndicatorLevel.OK)
            else SyncIndicator("cloud_off", "Sync offline", SyncIndicatorLevel.ERROR)
        SyncStatus.Busy -> SyncIndicator("sync", "Syncing…", SyncIndicatorLevel.WARN)
        is SyncStatus.Configured -> SyncIndicator("cloud_off", "Sync paused", SyncIndicatorLevel.WARN)
        is SyncStatus.Failed -> SyncIndicator("cloud_off", "Sync error", SyncIndicatorLevel.ERROR)
        SyncStatus.Disabled -> null // covered by the early return; branch for exhaustive when
    }
}

/**
 * UI wrapper of [syncIndicator] with a localized label. Icon/level come from the testable projection;
 * only [SyncIndicator.label] is resolved via [stringResource] for the same status.
 */
@Composable
fun syncIndicatorLocalized(status: SyncStatus?, reachable: ServerReachable): SyncIndicator? {
    val base = syncIndicator(status, reachable) ?: return null
    val label = when (status) {
        is SyncStatus.Online ->
            if (reachable == ServerReachable.REACHABLE) stringResource(Res.string.stail_sync_online)
            else stringResource(Res.string.stail_sync_offline)
        SyncStatus.Busy -> stringResource(Res.string.stail_syncing)
        is SyncStatus.Configured -> stringResource(Res.string.stail_sync_paused)
        is SyncStatus.Failed -> stringResource(Res.string.stail_sync_error)
        // base != null rules out Disabled/null, but the when on status must be exhaustive.
        null, SyncStatus.Disabled -> base.label
    }
    return base.copy(label = label)
}
