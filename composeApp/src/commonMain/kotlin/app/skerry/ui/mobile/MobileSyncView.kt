package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.ui.vault.VaultGateController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.sync.RemoteDevice
import app.skerry.ui.sync.AccountIdentityBlock
import app.skerry.ui.sync.PasswordReplaceConfirm
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncSetupBody
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.syncFailureText
import app.skerry.ui.sync.SyncStatusNotice
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_title
import app.skerry.ui.generated.resources.sync_mobile_intro
import app.skerry.ui.generated.resources.sync_unavailable_title
import app.skerry.ui.generated.resources.sync_unavailable_subtitle
import app.skerry.ui.generated.resources.sync_connecting_sub
import app.skerry.ui.generated.resources.sync_syncing
import app.skerry.ui.generated.resources.sync_reenroll_title
import app.skerry.ui.generated.resources.sync_reenroll_desc
import app.skerry.ui.generated.resources.sync_reenroll_action
import app.skerry.ui.generated.resources.sync_not_now
import app.skerry.ui.generated.resources.sync_connected_title
import app.skerry.ui.generated.resources.sync_session_stats
import app.skerry.ui.generated.resources.sync_sync_now
import app.skerry.ui.generated.resources.sync_disconnect
import app.skerry.ui.generated.resources.sync_linked_title
import app.skerry.ui.generated.resources.sync_reconnect_password
import app.skerry.ui.generated.resources.sync_what_syncs
import app.skerry.ui.generated.resources.sync_what_hosts
import app.skerry.ui.generated.resources.sync_what_snippets
import app.skerry.ui.generated.resources.sync_link_device
import app.skerry.ui.generated.resources.sync_hide
import app.skerry.ui.generated.resources.sync_linked_devices
import app.skerry.ui.generated.resources.sync_loading_devices
import app.skerry.ui.generated.resources.sync_load_devices_failed
import app.skerry.ui.generated.resources.sync_only_this_device
import app.skerry.ui.generated.resources.sync_this_device_badge
import app.skerry.ui.generated.resources.sync_confirm
import app.skerry.ui.generated.resources.sync_cancel
import app.skerry.ui.generated.resources.sync_revoke
import app.skerry.ui.generated.resources.stail_reenroll_prompt_title
import app.skerry.ui.generated.resources.stail_reenroll_prompt_cancel
import app.skerry.ui.generated.resources.stail_reenroll_prompt_subtitle
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.sync.PairingOfferContent
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

/**
 * More -> Sync push screen: self-hosted sync. Mobile idiom (like Appearance): the setup form is
 * inline on the screen rather than a modal. Connected shows status + Sync now/Disconnect;
 * disconnected/error shows the form (server + accountId + master password, single Connect action).
 * Zero-knowledge: the password goes into [SyncCoordinator] as a CharArray and is wiped there; here
 * it's held as a String until send and cleared right after.
 */
@Composable
fun MobileSyncScreen(state: MobileDesignState) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobilePushHeader(stringResource(Res.string.sync_title), onBack = state::pop)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(
                stringResource(Res.string.sync_mobile_intro),
                color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            val sync = LocalSync.current
            if (sync == null) {
                SyncStatusNotice("cloud_off", Skerry.colors.faint, stringResource(Res.string.sync_unavailable_title), stringResource(Res.string.sync_unavailable_subtitle))
            } else {
                SyncBody(sync)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * Prompt to re-enroll biometrics after the account connection accepted its key and reset enabled
 * biometrics (see [SyncCoordinator.biometricResetNeeded]). Shown only when the flag is set and the
 * device has biometrics; Re-enable runs the system prompt and re-wraps dataKey under the new key,
 * Not now just dismisses the prompt. If the device has no biometrics, there's nothing to re-enroll,
 * so the flag is cleared silently.
 */
@Composable
private fun BiometricReenrollCard(sync: SyncCoordinator) {
    val needed by sync.biometricResetNeeded.collectAsState()
    if (!needed) return
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    if (vault == null || biometrics == null) return
    val controller = remember(vault, biometrics) { VaultGateController(vault, biometrics) }
    if (!controller.canEnableBiometric()) {
        LaunchedEffect(Unit) { sync.acknowledgeBiometricReset() }
        return
    }
    val scope = rememberCoroutineScope()
    // Prompt strings are resolved in composable scope (stringResource can't be called in an
    // onClick lambda) and held ready for passing into enableBiometric.
    val reenrollPrompt = BiometricPrompt(
        title = stringResource(Res.string.stail_reenroll_prompt_title),
        cancelLabel = stringResource(Res.string.stail_reenroll_prompt_cancel),
        subtitle = stringResource(Res.string.stail_reenroll_prompt_subtitle),
    )

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Skerry.colors.surfaceDeep)
            .border(1.dp, Skerry.colors.cyan.copy(alpha = 0.45f), RoundedCornerShape(11.dp)).padding(15.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Sym("fingerprint", size = 22.sp, color = Skerry.colors.cyanBright)
            Column(Modifier.weight(1f)) {
                Txt(stringResource(Res.string.sync_reenroll_title), color = Skerry.colors.text, size = 14.sp, weight = FontWeight.Medium)
                Txt(
                    stringResource(Res.string.sync_reenroll_desc),
                    color = Skerry.colors.faint, size = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                stringResource(Res.string.sync_reenroll_action),
                icon = "fingerprint",
                enabled = !controller.biometricInFlight,
                onClick = {
                    if (controller.biometricInFlight) return@PrimaryButton
                    // enable re-wraps dataKey under the new key; the flag is cleared either way
                    // (enabled or dismissed the prompt) - shown again only after the next key acceptance.
                    scope.launch {
                        controller.enableBiometric(reenrollPrompt)
                        sync.acknowledgeBiometricReset()
                    }
                },
            )
            GhostButton(stringResource(Res.string.sync_not_now), onClick = { sync.acknowledgeBiometricReset() }, fg = Skerry.colors.dim)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SyncBody(sync: SyncCoordinator) {
    BiometricReenrollCard(sync)
    val status = sync.status.collectAsState().value
    // Busy is transient (a connect attempt or one sync cycle) and used to replace the whole body,
    // which destroyed the setup form's remember state — a failed connect against a down server
    // wiped the typed server/account. Render the last stable status underneath instead (the form
    // stays composed, submit disabled) with the busy notice on top.
    val busy = status == SyncStatus.Busy
    var stable by remember { mutableStateOf<SyncStatus?>(if (busy) null else status) }
    if (!busy) stable = status
    if (busy) SyncStatusNotice("sync", Skerry.colors.cyanBright, stringResource(Res.string.sync_syncing), stringResource(Res.string.sync_connecting_sub))
    when (val s = stable) {
        // Opened mid-operation (e.g. session restore right after unlock): nothing stable to show
        // yet besides the notice above. Busy itself never lands in [stable].
        null, SyncStatus.Busy -> {}
        is SyncStatus.Online -> if (!busy) {
            SyncStatusNotice("cloud_done", Skerry.colors.moss, stringResource(Res.string.sync_connected_title, s.accountId), stringResource(Res.string.sync_session_stats, s.lastPushed, s.lastPulled))
            // Identifiers for Teams invites (accountId + share-key fingerprint) - mobile merges
            // Account+Sync into one screen, so this block lives here (desktop has a separate Account tab).
            AccountIdentityBlock(s.accountId, Modifier.padding(top = 12.dp))
            // Same style as desktop (ghost, not filled primary) for platform parity.
            // Mobile merges the desktop Account+Sync tabs into one screen, so Sync now,
            // Disconnect and Link a device sit together (separated by tabs on desktop).
            MobileAccountActions(sync)
            MobileWhatSyncs(sync)
            MobileLinkedDevices(sync)
        }
        // Connecting hit an existing account under a different password → confirm re-keying this device
        // to the account password before adopting it (issue #28).
        is SyncStatus.NeedsPasswordReplaceConfirm -> {
            // Mobile shows the confirmation inline, so there's no dismiss callback to hang the decline on
            // (desktop declines on Esc). Any exit — header back, system back/gesture, tab switch — must
            // decline it: a pending replace left behind keeps the typed password in memory and strands the
            // status on "Syncing…". Leaving on confirm/cancel is a no-op — both clear the pending first.
            DisposableEffect(Unit) { onDispose { sync.cancelPasswordReplace() } }
            PasswordReplaceConfirm(sync, s.accountId)
        }
        // One shared SyncSetupBody call site for all form states: separate when-branches would be
        // separate composition slots, and a Disabled → Failed transition would still reset the
        // typed fields even though the form never visually left the screen.
        is SyncStatus.Configured, is SyncStatus.Failed, SyncStatus.Disabled -> {
            if (s is SyncStatus.Configured) {
                SyncStatusNotice("cloud_off", Skerry.colors.amber, stringResource(Res.string.sync_linked_title, s.accountId), stringResource(Res.string.sync_reconnect_password))
                Spacer(Modifier.height(16.dp))
            }
            SyncSetupBody(
                sync,
                errorMessage = if (s is SyncStatus.Failed && !busy) syncFailureText(s) else null,
                busy = busy,
            )
        }
    }
}

/**
 * What syncs, matching desktop (Settings -> Sync, WHAT SYNCS section). Live account-level toggles:
 * write [SyncSettings] to the vault via the coordinator, change ships out through the same live-push.
 * "SSH keys" and "Terminal history" are deliberately omitted (as on desktop): keys
 * always sync together with "Hosts & groups", and terminal history isn't a feature yet.
 */
@Composable
private fun MobileWhatSyncs(sync: SyncCoordinator) {
    val settings = sync.syncSettings.collectAsState().value
    LaunchedEffect(Unit) { sync.refreshSyncSettings() }
    // Section framed by lines above the header and below the toggles, matching desktop WhatSyncsHeader.
    HLine(modifier = Modifier.padding(top = 20.dp))
    Txt(stringResource(Res.string.sync_what_syncs), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
    // onToggle reads the current value from the flow, not a composition snapshot (stale-closure write-write).
    MobileSyncToggleRow(stringResource(Res.string.sync_what_hosts), null, on = settings.syncHosts) {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncHosts = !current.syncHosts))
    }
    MobileSyncToggleRow(stringResource(Res.string.sync_what_snippets), null, on = settings.syncSnippets) {
        val current = sync.syncSettings.value
        sync.setSyncSettings(current.copy(syncSnippets = !current.syncSnippets))
    }
    HLine()
}

/**
 * Connected-account actions stretched to the full screen width so the edges line up: Sync now and
 * Disconnect split the first row in half, Link a device takes the whole second row (content-hugging
 * buttons left the block ragged). "Link a device" expands an inline card with the pairing QR/code
 * (shared [PairingOfferContent]) — inline rather than a dialog, the mobile idiom of the Sync screen.
 */
@Composable
private fun MobileAccountActions(sync: SyncCoordinator) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton(stringResource(Res.string.sync_sync_now), onClick = { sync.syncNow() }, modifier = Modifier.weight(1f))
        GhostButton(stringResource(Res.string.sync_disconnect), onClick = { sync.disconnect() }, fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.4f), modifier = Modifier.weight(1f))
    }
    if (!show) {
        GhostButton(stringResource(Res.string.sync_link_device), onClick = { show = true }, icon = "qr_code", modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        return
    }
    Spacer(Modifier.height(16.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Skerry.colors.surfaceDeep)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(13.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(Res.string.sync_link_device), color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable { show = false }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Txt(stringResource(Res.string.sync_hide), color = Skerry.colors.dim, size = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        PairingOfferContent(sync)
    }
}

@Composable
private fun MobileSyncToggleRow(label: String, sub: String?, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(label, color = Skerry.colors.text, size = 13.5.sp, weight = FontWeight.Medium)
            if (sub != null) Txt(sub, color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Toggle(on = on, onToggle = onToggle)
    }
}

/**
 * Real account devices — parity with desktop (Settings → Account, LINKED DEVICES). On an active
 * session the server always returns at least the current device, so an empty list means listDevices
 * swallowed an error: report that honestly, not "only you". Revoke removes another device (confirmed
 * on a second click) and rereads the list.
 */
@Composable
private fun MobileLinkedDevices(sync: SyncCoordinator) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<RemoteDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(sync, reload) {
        loading = true
        // Revoked devices are no longer linked — don't show them (the server keeps a row with revoked=true).
        // Current device first (sortedByDescending is stable — order of the rest is preserved).
        devices = sync.listDevices().filter { !it.revoked }.sortedByDescending { it.current }
        loading = false
    }

    Txt(stringResource(Res.string.sync_linked_devices), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 26.dp, bottom = 6.dp))
    when {
        loading -> Txt(stringResource(Res.string.sync_loading_devices), color = Skerry.colors.faint, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        devices.isEmpty() -> Txt(stringResource(Res.string.sync_load_devices_failed), color = Skerry.colors.amber, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        devices.size == 1 && devices.first().current -> Txt(stringResource(Res.string.sync_only_this_device), color = Skerry.colors.faint, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        else -> devices.forEach { d ->
            MobileDeviceRow(
                device = d,
                onRevoke = if (d.current || d.revoked) null else {
                    { scope.launch { if (sync.revokeDevice(d.id)) reload++ } }
                },
            )
        }
    }
}

@Composable
private fun MobileDeviceRow(device: RemoteDevice, onRevoke: (() -> Unit)?) {
    // Revoke is irreversible from the UI (the device reconnects with the master password) — confirm on a second click.
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym("devices", size = 20.sp, color = Skerry.colors.dim)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(device.name, color = Skerry.colors.text, size = 13.5.sp, weight = FontWeight.Medium)
            if (device.current) Txt(stringResource(Res.string.sync_this_device_badge), color = Skerry.colors.moss, size = 10.sp)
        }
        if (onRevoke != null) {
            if (confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MobileRevokeChip(stringResource(Res.string.sync_confirm), Skerry.colors.sunset) { confirming = false; onRevoke() }
                    MobileRevokeChip(stringResource(Res.string.sync_cancel), Skerry.colors.dim) { confirming = false }
                }
            } else {
                MobileRevokeChip(stringResource(Res.string.sync_revoke), Skerry.colors.dim) { confirming = true }
            }
        }
    }
}

@Composable
private fun MobileRevokeChip(label: String, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Txt(label, color = fg, size = 12.sp)
    }
}
