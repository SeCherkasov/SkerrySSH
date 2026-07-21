package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalSync
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_cancel
import app.skerry.ui.generated.resources.settings_confirm
import app.skerry.ui.generated.resources.settings_device_sub_current
import app.skerry.ui.generated.resources.settings_device_sub_other
import app.skerry.ui.generated.resources.settings_devices_load_failed
import app.skerry.ui.generated.resources.settings_disconnect
import app.skerry.ui.generated.resources.settings_hosts_groups
import app.skerry.ui.generated.resources.settings_link_device
import app.skerry.ui.generated.resources.settings_linked_devices
import app.skerry.ui.generated.resources.settings_loading_devices
import app.skerry.ui.generated.resources.settings_only_this_device
import app.skerry.ui.generated.resources.settings_reconnect
import app.skerry.ui.generated.resources.settings_revoke
import app.skerry.ui.generated.resources.settings_set_up_sync
import app.skerry.ui.generated.resources.settings_snippets
import app.skerry.ui.generated.resources.settings_sync_connected
import app.skerry.ui.generated.resources.settings_sync_error
import app.skerry.ui.generated.resources.settings_sync_now
import app.skerry.ui.generated.resources.settings_sync_pushed_pulled
import app.skerry.ui.generated.resources.settings_sync_subtitle
import app.skerry.ui.generated.resources.settings_sync_syncing
import app.skerry.ui.generated.resources.settings_sync_syncing_desc
import app.skerry.ui.generated.resources.settings_sync_title
import app.skerry.ui.generated.resources.settings_this_device
import app.skerry.ui.generated.resources.settings_what_syncs
import app.skerry.ui.sync.AccountCardModel
import app.skerry.ui.sync.AccountIdentityBlock
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.accountCardModelLocalized
import app.skerry.ui.sync.syncFailureText
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// Sync section: the whole sync story in one tab — account/profile card with the connection
// lifecycle, account identifiers for Teams invites, "what syncs" toggles and linked devices.
// (The former separate Account tab was merged here: the app has no account besides sync.)

@Composable
internal fun SyncSection(state: DesktopDesignState) {
    SectionTitle(stringResource(Res.string.settings_sync_title), stringResource(Res.string.settings_sync_subtitle))
    // Mock path and live path are separate composables (not a conditional remember/collectAsState in
    // one body): remember/collectAsState must be called unconditionally within their composable
    // (Compose slot table rule). LocalSync.current is stable (staticCompositionLocalOf), but the
    // strict pattern branches into separate functions, each with its own remember calls.
    val sync = LocalSync.current
    if (sync == null) {
        // Mock/preview path with no backend: local vault card with "Set up sync" + static toggles.
        AccountCard(accountCardModelLocalized(null), sync = null, state = state)
        WhatSyncsHeader()
        SettingToggleRow(stringResource(Res.string.settings_hosts_groups), "", on = true, onToggle = {})
        SettingToggleRow(stringResource(Res.string.settings_snippets), "", on = true, onToggle = {})
    } else {
        LiveSyncSection(sync, state)
    }
}

/** Live sync section: unconditional collectAsState inside its own composable. */
@Composable
private fun LiveSyncSection(sync: app.skerry.ui.sync.SyncCoordinator, state: DesktopDesignState) {
    val status = sync.status.collectAsState().value
    when (status) {
        // Transient states keep the status-card look; everything else is the account card.
        SyncStatus.Busy -> SyncStatusCard("sync", D.cyanBright, stringResource(Res.string.settings_sync_syncing), stringResource(Res.string.settings_sync_syncing_desc)) {}
        is SyncStatus.Failed -> SyncStatusCard("cloud_off", D.sunset, stringResource(Res.string.settings_sync_error), syncFailureText(status)) {
            GhostButton(stringResource(Res.string.settings_reconnect), onClick = state::openSyncSetup, icon = "cloud_sync")
        }
        else -> {
            val model = accountCardModelLocalized(status, sync.savedConfig?.serverUrl)
            AccountCard(model, sync, state)
            // Session status card below the profile: traffic counters + "Sync now". A separate card
            // (not extra lines in the profile card) keeps each card to one line and one action.
            if (status is SyncStatus.Online) {
                SyncStatusCard(
                    "cloud_done", D.moss,
                    stringResource(Res.string.settings_sync_connected),
                    stringResource(Res.string.settings_sync_pushed_pulled, status.lastPushed, status.lastPulled),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    GhostButton(stringResource(Res.string.settings_sync_now), onClick = { sync.syncNow() })
                }
            }
            // Identifiers for Teams invites (accountId + sharing-key fingerprint); model.title ==
            // accountId while a session is active.
            if (model.connected) AccountIdentityBlock(model.title, Modifier.padding(top = 12.dp))
        }
    }
    WhatSyncsHeader()
    WhatSyncsToggles(sync)
    // The device list is only known server-side while a session is active (Online).
    if (status is SyncStatus.Online) LinkedDevices(sync, onLink = state::openPairing)
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

@Composable
private fun WhatSyncsHeader() {
    Txt(stringResource(Res.string.settings_what_syncs), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

/**
 * Profile card: avatar + title/subtitle + the connection-lifecycle action
 * (set up / reconnect / disconnect). "Sync now" lives on the session status card below.
 */
@Composable
private fun AccountCard(model: AccountCardModel, sync: app.skerry.ui.sync.SyncCoordinator?, state: DesktopDesignState) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(D.cyan), contentAlignment = Alignment.Center) {
            Txt(model.initials, color = D.ink, size = 14.sp, weight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f)) {
            Txt(model.title, color = D.text, size = 13.5.sp, weight = FontWeight.Medium)
            Txt(model.subtitle, color = D.faint, size = 11.5.sp)
        }
        when {
            model.connected && sync != null -> GhostButton(stringResource(Res.string.settings_disconnect), onClick = { sync.disconnect() }, fg = D.sunset, border = D.sunset.copy(alpha = 0.4f))
            model.linked -> PrimaryButton(stringResource(Res.string.settings_reconnect), onClick = state::openSyncSetup, icon = "cloud_sync")
            else -> PrimaryButton(stringResource(Res.string.settings_set_up_sync), onClick = state::openSyncSetup, icon = "cloud_sync")
        }
    }
}

/** Sync status card (online/busy/error): icon, title/subtitle, and a right-side slot for action buttons. */
@Composable
private fun SyncStatusCard(icon: String, iconColor: Color, title: String, subtitle: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(14.dp),
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

/** Account's real devices ([SyncCoordinator.listDevices]); Revoke revokes and reloads the list. */
@Composable
private fun LinkedDevices(sync: app.skerry.ui.sync.SyncCoordinator, onLink: () -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<app.skerry.shared.sync.RemoteDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // reload++ makes LaunchedEffect reload the list after a device is revoked.
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(sync, reload) {
        loading = true
        try {
            // Revoked devices are hidden (server keeps the row with revoked=true); current device first
            // (sortedByDescending is stable, so the rest keep their order).
            devices = sync.listDevices().filter { !it.revoked }.sortedByDescending { it.current }
        } finally {
            loading = false // never strand the spinner, whichever way the load ends
        }
    }

    Txt(stringResource(Res.string.settings_linked_devices), color = D.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
    when {
        loading -> Txt(stringResource(Res.string.settings_loading_devices), color = D.faint, size = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        // An active session always returns at least the current device; an empty list means
        // listDevices swallowed an error, so report that rather than "only you".
        devices.isEmpty() -> Txt(stringResource(Res.string.settings_devices_load_failed), color = D.amber, size = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        devices.size == 1 && devices.first().current -> Txt(stringResource(Res.string.settings_only_this_device), color = D.faint, size = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        else -> devices.forEach { d ->
            DeviceRow(
                icon = "devices",
                name = d.name,
                sub = if (d.current) stringResource(Res.string.settings_device_sub_current) else stringResource(Res.string.settings_device_sub_other),
                thisDevice = d.current,
                onRevoke = if (d.current || d.revoked) null else {
                    { scope.launch { if (sync.revokeDevice(d.id)) reload++ } }
                },
            )
        }
    }
    // Quick pairing: show a new device a QR/code to link it without the account's master password.
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        GhostButton(stringResource(Res.string.settings_link_device), onClick = onLink, icon = "qr_code")
    }
}

@Composable
private fun DeviceRow(icon: String, name: String, sub: String, onRevoke: (() -> Unit)? = null, thisDevice: Boolean = false) {
    // Revoke is irreversible from the UI, so require a second-click confirmation to guard against
    // an accidental misclick logging out a working device.
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym(icon, size = 18.sp, color = D.dim)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(name, color = D.text, size = 13.sp, weight = FontWeight.Medium)
                if (thisDevice) Txt(stringResource(Res.string.settings_this_device), color = D.moss, size = 10.sp)
            }
            Txt(sub, color = D.faint, size = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (onRevoke != null) {
            if (confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipButton(stringResource(Res.string.settings_confirm), color = D.sunset, onClick = { confirming = false; onRevoke() })
                    ChipButton(stringResource(Res.string.settings_cancel), color = D.dim, onClick = { confirming = false })
                }
            } else {
                ChipButton(stringResource(Res.string.settings_revoke), color = D.dim, onClick = { confirming = true })
            }
        }
    }
}
