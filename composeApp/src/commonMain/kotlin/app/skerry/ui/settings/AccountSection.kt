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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_account_subtitle
import app.skerry.ui.generated.resources.settings_account_title
import app.skerry.ui.generated.resources.settings_cancel
import app.skerry.ui.generated.resources.settings_confirm
import app.skerry.ui.generated.resources.settings_device_sub_current
import app.skerry.ui.generated.resources.settings_device_sub_other
import app.skerry.ui.generated.resources.settings_devices_load_failed
import app.skerry.ui.generated.resources.settings_disconnect
import app.skerry.ui.generated.resources.settings_link_device
import app.skerry.ui.generated.resources.settings_linked_devices
import app.skerry.ui.generated.resources.settings_loading_devices
import app.skerry.ui.generated.resources.settings_only_this_device
import app.skerry.ui.generated.resources.settings_reconnect
import app.skerry.ui.generated.resources.settings_revoke
import app.skerry.ui.generated.resources.settings_set_up_sync
import app.skerry.ui.generated.resources.settings_this_device
import app.skerry.ui.app.LocalSync
import app.skerry.ui.sync.AccountCardModel
import app.skerry.ui.sync.AccountIdentityBlock
import app.skerry.ui.sync.accountCardModelLocalized
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// Account section: sync account profile card + linked devices.

@Composable
internal fun AccountSection(state: DesktopDesignState) {
    SectionTitle(stringResource(Res.string.settings_account_title), stringResource(Res.string.settings_account_subtitle))
    // The card reflects live state from the sync coordinator; without a backend, it shows a local
    // vault with "Set up sync".
    val sync = LocalSync.current
    if (sync == null) {
        AccountCard(accountCardModelLocalized(null), sync = null, state = state)
    } else {
        LiveAccountSection(sync, state)
    }
}

/** Live account card: unconditional collectAsState inside its own composable. */
@Composable
private fun LiveAccountSection(sync: app.skerry.ui.sync.SyncCoordinator, state: DesktopDesignState) {
    val status = sync.status.collectAsState().value
    val model = accountCardModelLocalized(status, sync.savedConfig?.serverUrl)
    AccountCard(model, sync, state)
    // Identifiers for Teams invites (accountId + sharing-key fingerprint); model.title == accountId
    // while a session is active. Lives here (account property), not on the Sync tab (sync engine).
    if (model.connected) AccountIdentityBlock(model.title, Modifier.padding(top = 12.dp))
    // The device list is only known server-side while a session is active (Online).
    if (model.connected) LinkedDevices(sync, onLink = state::openPairing)
}

/** Profile card: avatar + title/subtitle + state-dependent actions (set up / reconnect / disconnect). */
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
        // Account owns the connection lifecycle (set up / reconnect / disconnect); "Sync now" is
        // not duplicated here, it belongs to the Sync tab.
        when {
            model.connected && sync != null -> GhostButton(stringResource(Res.string.settings_disconnect), onClick = { sync.disconnect() }, fg = D.sunset, border = D.sunset.copy(alpha = 0.4f))
            model.linked -> PrimaryButton(stringResource(Res.string.settings_reconnect), onClick = state::openSyncSetup, icon = "cloud_sync")
            else -> PrimaryButton(stringResource(Res.string.settings_set_up_sync), onClick = state::openSyncSetup, icon = "cloud_sync")
        }
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
        // Revoked devices are hidden (server keeps the row with revoked=true); current device first
        // (sortedByDescending is stable, so the rest keep their order).
        devices = sync.listDevices().filter { !it.revoked }.sortedByDescending { it.current }
        loading = false
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
    GhostButton(stringResource(Res.string.settings_link_device), onClick = onLink, icon = "qr_code", modifier = Modifier.padding(top = 12.dp))
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
