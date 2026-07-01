package app.skerry.ui.sync

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.stail_sync_online
import app.skerry.ui.generated.resources.stail_sync_offline
import app.skerry.ui.generated.resources.stail_syncing
import app.skerry.ui.generated.resources.stail_sync_paused
import app.skerry.ui.generated.resources.stail_sync_error
import org.jetbrains.compose.resources.stringResource

/** Семантический уровень индикатора синхронизации (UI красит: OK→moss, WARN→amber, ERROR→sunset). */
enum class SyncIndicatorLevel { OK, WARN, ERROR }

/** Что показать в индикаторе sync (status-bar desktop / шапка mobile). null = скрыть. */
data class SyncIndicator(val icon: String, val label: String, val level: SyncIndicatorLevel)

/**
 * Чистая (тестируемая) проекция состояния sync на статус-индикатор. Раньше индикатор показывал ТОЛЬКО
 * доступность сервера по health-пингу ([ServerReachable]) и потому врал «Sync online», когда у ЭТОГО
 * устройства нет рабочей сессии (отозвано/только привязано/после рестарта). Теперь ведущий сигнал —
 * статус сессии ([SyncStatus]); доступность лишь различает online/offline в активном состоянии.
 *
 * - [SyncStatus.Online] + REACHABLE → «Sync online» (OK); + UNREACHABLE → «Sync offline» (ERROR).
 * - [SyncStatus.Busy] → «Syncing…» (WARN).
 * - [SyncStatus.Configured] → «Sync paused» (WARN): привязка есть, но сессии нет (заперто/после
 *   рестарта/отозвано после неудачного refresh) — НЕ «online».
 * - [SyncStatus.Failed] → «Sync error» (ERROR).
 * - Не настроен / ещё не пинговали ([SyncStatus.Disabled] / [ServerReachable.UNKNOWN]) → скрыть.
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
        SyncStatus.Disabled -> null // покрыто ранним возвратом; ветка для исчерпывающего when
    }
}

/**
 * UI-обёртка [syncIndicator] с локализованной подписью. Иконка/уровень берутся из чистой (тестируемой)
 * проекции, а [SyncIndicator.label] резолвится через [stringResource] по тому же статусу. Чистая версия
 * оставлена для тестов и любого не-composable кода; здесь мы лишь подменяем англоязычный label.
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
        // base != null исключает Disabled/null — но when по статусу должен быть исчерпывающим.
        null, SyncStatus.Disabled -> base.label
    }
    return base.copy(label = label)
}
