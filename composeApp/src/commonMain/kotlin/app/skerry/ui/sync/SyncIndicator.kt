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
 * Testable projection of sync state onto the status indicator. Session status ([SyncStatus]) leads
 * and shows immediately; reachability only downgrades a live session to "offline" once a ping fails
 * (UNKNOWN before the first ping stays optimistic, so the icon doesn't lag a couple seconds behind a
 * connect). Hidden only when sync isn't configured.
 */
fun syncIndicator(status: SyncStatus?, reachable: ServerReachable): SyncIndicator? {
    if (status == null || status == SyncStatus.Disabled) return null
    return when (status) {
        is SyncStatus.Online ->
            if (reachable == ServerReachable.UNREACHABLE) SyncIndicator("cloud_off", "Sync offline", SyncIndicatorLevel.ERROR)
            else SyncIndicator("cloud_done", "Sync online", SyncIndicatorLevel.OK)
        SyncStatus.Busy -> SyncIndicator("sync", "Syncing…", SyncIndicatorLevel.WARN)
        is SyncStatus.Configured -> SyncIndicator("cloud_off", "Sync paused", SyncIndicatorLevel.WARN)
        is SyncStatus.Failed -> SyncIndicator("cloud_off", "Sync error", SyncIndicatorLevel.ERROR)
        SyncStatus.Disabled -> null
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
            if (reachable == ServerReachable.UNREACHABLE) stringResource(Res.string.stail_sync_offline)
            else stringResource(Res.string.stail_sync_online)
        SyncStatus.Busy -> stringResource(Res.string.stail_syncing)
        is SyncStatus.Configured -> stringResource(Res.string.stail_sync_paused)
        is SyncStatus.Failed -> stringResource(Res.string.stail_sync_error)
        // base != null rules out Disabled/null, but the when on status must be exhaustive.
        null, SyncStatus.Disabled -> base.label
    }
    return base.copy(label = label)
}
