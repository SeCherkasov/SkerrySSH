package app.skerry.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.SettingsTab
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_hosts_groups
import app.skerry.ui.generated.resources.settings_open_account
import app.skerry.ui.generated.resources.settings_snippets
import app.skerry.ui.generated.resources.settings_sync_connected
import app.skerry.ui.generated.resources.settings_sync_error
import app.skerry.ui.generated.resources.settings_sync_linked
import app.skerry.ui.generated.resources.settings_sync_linked_desc
import app.skerry.ui.generated.resources.settings_sync_not_connected
import app.skerry.ui.generated.resources.settings_sync_not_connected_desc
import app.skerry.ui.generated.resources.settings_sync_now
import app.skerry.ui.generated.resources.settings_sync_pushed_pulled
import app.skerry.ui.generated.resources.settings_sync_subtitle
import app.skerry.ui.generated.resources.settings_sync_summary_mock
import app.skerry.ui.generated.resources.settings_sync_synced_ago
import app.skerry.ui.generated.resources.settings_sync_syncing
import app.skerry.ui.generated.resources.settings_sync_syncing_desc
import app.skerry.ui.generated.resources.settings_sync_title
import app.skerry.ui.generated.resources.settings_what_syncs
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.syncFailureText
import org.jetbrains.compose.resources.stringResource

// Секция Sync: статус движка синхронизации + тумблеры «что синхронизировать».

@Composable
internal fun SyncSection(state: DesktopDesignState) {
    SectionTitle(stringResource(Res.string.settings_sync_title), stringResource(Res.string.settings_sync_subtitle))
    // Мок-путь и живой путь — разные composable (а не условный remember/collectAsState в одном теле):
    // rememberCoroutineScope/collectAsState должны вызываться безусловно в своём composable (правило
    // слотовой таблицы Compose). LocalSync.current стабилен (staticCompositionLocalOf), но строгий
    // паттерн — ветвление на отдельные функции, каждая со своими remember-вызовами.
    val sync = LocalSync.current
    if (sync == null) {
        // Мок-путь/превью без бэкенда: статичная карточка макета (подключённое состояние).
        SyncStatusCard("cloud_done", D.moss, stringResource(Res.string.settings_sync_synced_ago), stringResource(Res.string.settings_sync_summary_mock)) {
            GhostButton(stringResource(Res.string.settings_sync_now), onClick = {})
        }
    } else {
        LiveSyncStatus(sync, state)
    }
    Txt(stringResource(Res.string.settings_what_syncs), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    if (sync == null) {
        // Превью без бэкенда: статичные тумблеры (как в макете).
        SettingToggleRow(stringResource(Res.string.settings_hosts_groups), "", on = true, onToggle = {})
        SettingToggleRow(stringResource(Res.string.settings_snippets), "", on = true, onToggle = {})
    } else {
        WhatSyncsToggles(sync)
    }
}

/**
 * Живые тумблеры «что синхронизировать» (уровень аккаунта): пишут [SyncSettings] в vault через
 * координатор, изменение уезжает тем же live-push. «SSH keys» и «Terminal history» из макета убраны
 * сознательно: ключи нужны для аутентификации хостов и синкаются всегда вместе с «Hosts & groups»
 * (отдельный выключатель сломал бы связки host→credential), а истории терминала как фичи ещё нет.
 */
@Composable
private fun WhatSyncsToggles(sync: app.skerry.ui.sync.SyncCoordinator) {
    val settings = sync.syncSettings.collectAsState().value
    LaunchedEffect(Unit) { sync.refreshSyncSettings() } // vault уже открыт на экране настроек
    // В onToggle читаем АКТУАЛЬНОЕ значение из flow, не снимок композиции: иначе быстрый второй тап
    // (по другому тумблеру) до перерисовки откатил бы первый (stale-closure write-write).
    SettingToggleRow(stringResource(Res.string.settings_hosts_groups), "", on = settings.syncHosts, onToggle = {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncHosts = !current.syncHosts))
    })
    SettingToggleRow(stringResource(Res.string.settings_snippets), "", on = settings.syncSnippets, onToggle = {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncSnippets = !current.syncSnippets))
    })
}

/** Живой статус sync: безусловный collectAsState внутри своего composable (операции — на scope координатора). */
@Composable
private fun LiveSyncStatus(sync: app.skerry.ui.sync.SyncCoordinator, state: DesktopDesignState) {
    // Sync владеет ДВИЖКОМ синхронизации: статус + «Sync now». Подключение/отвязка/устройства живут
    // во вкладке Account — здесь их НЕ дублируем; в несоединённых состояниях ведём в Account.
    val toAccount = { state.showSettingsTab(SettingsTab.Account) }
    when (val status = sync.status.collectAsState().value) {
        is SyncStatus.Online -> SyncStatusCard(
            "cloud_done", D.moss,
            stringResource(Res.string.settings_sync_connected, status.accountId),
            stringResource(Res.string.settings_sync_pushed_pulled, status.lastPushed, status.lastPulled),
        ) {
            GhostButton(stringResource(Res.string.settings_sync_now), onClick = { sync.syncNow() })
        }
        SyncStatus.Busy -> SyncStatusCard("sync", D.cyanBright, stringResource(Res.string.settings_sync_syncing), stringResource(Res.string.settings_sync_syncing_desc)) {}
        is SyncStatus.Configured -> SyncStatusCard("cloud_off", D.amber, stringResource(Res.string.settings_sync_linked, status.accountId), stringResource(Res.string.settings_sync_linked_desc)) {
            GhostButton(stringResource(Res.string.settings_open_account), onClick = toAccount)
        }
        is SyncStatus.Failed -> SyncStatusCard("cloud_off", D.sunset, stringResource(Res.string.settings_sync_error), syncFailureText(status)) {
            GhostButton(stringResource(Res.string.settings_open_account), onClick = toAccount)
        }
        SyncStatus.Disabled -> SyncStatusCard("cloud_off", D.faint, stringResource(Res.string.settings_sync_not_connected), stringResource(Res.string.settings_sync_not_connected_desc)) {
            GhostButton(stringResource(Res.string.settings_open_account), onClick = toAccount)
        }
    }
}

/** Карточка статуса sync: иконка + заголовок/подпись + правый слот (кнопки действий). */
@Composable
private fun SyncStatusCard(icon: String, iconColor: Color, title: String, subtitle: String, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym(icon, size = 20.sp, color = iconColor)
        Column(Modifier.weight(1f)) {
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.faint, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        action()
    }
}
