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

// Sync section: sync engine status plus "what syncs" toggles.

@Composable
internal fun SyncSection(state: DesktopDesignState) {
    SectionTitle(stringResource(Res.string.settings_sync_title), stringResource(Res.string.settings_sync_subtitle))
    // Mock path and live path are separate composables (not a conditional remember/collectAsState in
    // one body): remember/collectAsState must be called unconditionally within their composable
    // (Compose slot table rule). LocalSync.current is stable (staticCompositionLocalOf), but the
    // strict pattern branches into separate functions, each with its own remember calls.
    val sync = LocalSync.current
    if (sync == null) {
        // Mock/preview path with no backend: static card in the connected state.
        SyncStatusCard("cloud_done", D.moss, stringResource(Res.string.settings_sync_synced_ago), stringResource(Res.string.settings_sync_summary_mock)) {
            GhostButton(stringResource(Res.string.settings_sync_now), onClick = {})
        }
    } else {
        LiveSyncStatus(sync, state)
    }
    Txt(stringResource(Res.string.settings_what_syncs), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    if (sync == null) {
        // Preview with no backend: static toggles (as in the mockup).
        SettingToggleRow(stringResource(Res.string.settings_hosts_groups), "", on = true, onToggle = {})
        SettingToggleRow(stringResource(Res.string.settings_snippets), "", on = true, onToggle = {})
    } else {
        WhatSyncsToggles(sync)
    }
}

/**
 * Live "what syncs" toggles (account level): write [SyncSettings] to the vault through the
 * coordinator; a change goes out via the same live push. "SSH keys" and "Terminal history" from the
 * mockup are omitted deliberately: keys authenticate hosts and always sync with "Hosts & groups" (a
 * separate switch would break the host-credential link), and terminal history isn't a feature yet.
 */
@Composable
private fun WhatSyncsToggles(sync: app.skerry.ui.sync.SyncCoordinator) {
    val settings = sync.syncSettings.collectAsState().value
    LaunchedEffect(Unit) { sync.refreshSyncSettings() } // vault is already open on the settings screen
    // onToggle reads the current value from the flow, not the composition snapshot: otherwise a fast
    // second tap (on the other toggle) before recomposition would revert the first (stale-closure write-write).
    SettingToggleRow(stringResource(Res.string.settings_hosts_groups), "", on = settings.syncHosts, onToggle = {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncHosts = !current.syncHosts))
    })
    SettingToggleRow(stringResource(Res.string.settings_snippets), "", on = settings.syncSnippets, onToggle = {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncSnippets = !current.syncSnippets))
    })
}

/** Live sync status: unconditional collectAsState inside its own composable (operations run on the coordinator's scope). */
@Composable
private fun LiveSyncStatus(sync: app.skerry.ui.sync.SyncCoordinator, state: DesktopDesignState) {
    // Sync owns the sync engine: status + "Sync now". Connect/unlink/devices live in the Account
    // tab and are not duplicated here; disconnected states link out to Account.
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

/** Sync status card: icon, title/subtitle, and a right-side slot for action buttons. */
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
