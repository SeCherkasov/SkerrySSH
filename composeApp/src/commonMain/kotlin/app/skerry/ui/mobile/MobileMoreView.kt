package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skerry.shared.ai.AiProviderKind
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_title
import app.skerry.ui.generated.resources.lib_snippets_screen_title
import app.skerry.ui.generated.resources.runbook_section
import app.skerry.ui.generated.resources.more_about
import app.skerry.ui.generated.resources.more_ai_privacy
import app.skerry.ui.generated.resources.more_ai_subtitle_byok
import app.skerry.ui.generated.resources.more_ai_subtitle_off
import app.skerry.ui.generated.resources.more_ai_subtitle_local
import app.skerry.ui.generated.resources.more_known_hosts
import app.skerry.ui.generated.resources.more_lock
import app.skerry.ui.generated.resources.more_port_forwarding
import app.skerry.ui.generated.resources.more_sync
import app.skerry.ui.generated.resources.more_sync_error
import app.skerry.ui.generated.resources.more_sync_linked_locked
import app.skerry.ui.generated.resources.more_sync_local_only
import app.skerry.ui.generated.resources.more_sync_synced
import app.skerry.ui.generated.resources.more_sync_syncing
import app.skerry.ui.generated.resources.more_team
import app.skerry.ui.generated.resources.more_trash
import app.skerry.ui.generated.resources.more_title
import app.skerry.ui.generated.resources.settings_security_title
import app.skerry.ui.generated.resources.settings_update_status
import app.skerry.ui.generated.resources.vault_item_count
import app.skerry.ui.generated.resources.vault_title
import app.skerry.ui.generated.resources.keepalive_title
import app.skerry.ui.generated.resources.keepalive_subtitle_optimal
import app.skerry.ui.generated.resources.keepalive_subtitle_warning
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalKeepAliveBridge
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.term_player_open
import app.skerry.ui.generated.resources.conn_import_action
import app.skerry.ui.generated.resources.conn_rdp_import_action
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.host.pickAndParseRdpFile
import app.skerry.ui.host.pickAndParseSshConfig
import app.skerry.ui.terminal.openCastFile
import app.skerry.ui.app.AppVersion
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.palette

/**
 * Root More tab: title + profile card + list of section links. Navigation hub to the
 * Port forwarding / Known hosts / Team push screens and to the "Lock Skerry" action.
 *
 * Live path ([onLock] != null, behind the vault gate): profile card shows the local vault,
 * Port forwarding/Known hosts subtitles are live counts
 * ([mobileMorePortsSubtitle]/[mobileMoreKnownSubtitle]) from [LocalSessions]/[LocalKnownHosts];
 * AI/Appearance/Security rows are inert placeholders, "Lock Skerry" actually locks the vault.
 * Preview/offscreen ([onLock] == null) shows a static mock profile card.
 */
@Composable
fun MobileMoreScreen(state: MobileDesignState, onLock: (() -> Unit)?) {
    val preview = onLock == null
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 14.dp)) {
            MobileScreenTitle(stringResource(Res.string.more_title))
        }
        if (preview) MockProfileCard() else LocalVaultCard()

        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            // Preview: same localized subtitles as the live path, just with a fixed count.
            val ports = if (preview) mobileMorePortsSubtitle(2) else portsSubtitle()
            val known = if (preview) mobileMoreKnownSubtitle(1) else knownSubtitle()
            val knownWarn = if (preview) true else knownChanged() > 0

            // Keychain: it lost its root tab to Sessions and lives here now. Subtitle is the number
            // of secrets in the open vault; without one (preview/locked) the row still opens the
            // screen, which says the same thing in full.
            val vaultCount = LocalCredentials.current?.credentials?.size
            val vaultSubtitle = if (vaultCount == null) null else {
                pluralStringResource(Res.plurals.vault_item_count, vaultCount, vaultCount)
            }
            MoreRow(
                "vpn_key", Skerry.colors.cyanBright, stringResource(Res.string.vault_title),
                vaultSubtitle, Skerry.colors.dim,
                onClick = { state.push(MobileRoute.Vault) },
            )
            // Snippet library: it lost its root tab to the remote-desktops one, so this is its home
            // in the shell (the terminal's snippet palette still reaches it mid-session).
            MoreRow("code_blocks", Skerry.colors.cyanBright, stringResource(Res.string.lib_snippets_screen_title), null, Skerry.colors.dim, onClick = { state.push(MobileRoute.Snippets) })
            MoreRow("checklist", Skerry.colors.cyanBright, stringResource(Res.string.runbook_section), null, Skerry.colors.dim, onClick = { state.push(MobileRoute.Runbooks) })
            MoreRow("lan", Skerry.colors.cyanBright, stringResource(Res.string.more_port_forwarding), ports, Skerry.colors.moss, onClick = { state.push(MobileRoute.Ports) })
            MoreRow("fingerprint", Skerry.colors.cyanBright, stringResource(Res.string.more_known_hosts), known, if (knownWarn) Skerry.colors.sunset else Skerry.colors.moss, onClick = { state.push(MobileRoute.Known) })
            MoreRow("groups", Skerry.colors.cyanBright, stringResource(Res.string.more_team), if (preview) "Platform crew" else null, Skerry.colors.dim, onClick = { state.push(MobileRoute.Team) })
            // AI: live path (controller present) pushes the AI settings screen; otherwise an inert
            // placeholder (preview). Subtitle is the currently selected provider (Local / BYOK / Off).
            val liveAi = LocalAi.current
            val aiSubtitle = when (liveAi?.settings?.provider) {
                AiProviderKind.DEVICE, null -> stringResource(Res.string.more_ai_subtitle_local)
                AiProviderKind.CLOUD -> stringResource(Res.string.more_ai_subtitle_byok)
                AiProviderKind.OFF -> stringResource(Res.string.more_ai_subtitle_off)
            }
            MoreRow("auto_awesome", Skerry.colors.amber, stringResource(Res.string.more_ai_privacy), aiSubtitle, Skerry.colors.dim, onClick = if (liveAi != null) { -> state.push(MobileRoute.Ai) } else null)
            // Appearance subtitle is the actual selected terminal theme, not static layout text.
            MoreRow("palette", Skerry.colors.cyanBright, stringResource(Res.string.appearance_title), state.terminalTheme.displayName, Skerry.colors.dim, onClick = { state.push(MobileRoute.Appearance) })
            MoreRow("sync", Skerry.colors.cyanBright, stringResource(Res.string.more_sync), if (preview) stringResource(Res.string.more_sync_synced) else syncSubtitle(), Skerry.colors.dim, onClick = if (preview) null else { -> state.push(MobileRoute.Sync) })
            // "Security" section: master password, biometrics, auto-lock, event log. Live path is
            // behind the gate (vault present); in preview the row is inert (nothing to configure without a vault).
            MoreRow("shield_lock", Skerry.colors.cyanBright, stringResource(Res.string.settings_security_title), null, Skerry.colors.dim, onClick = if (preview) null else { -> state.push(MobileRoute.Security) })
            // Trash: records deleted on any device of the account, restorable within the retention
            // window. Live path only — without a vault there is nothing to list.
            MoreRow("delete", Skerry.colors.cyanBright, stringResource(Res.string.more_trash), null, Skerry.colors.dim, onClick = if (preview) null else { -> state.push(MobileRoute.Trash) })
            // Recording player: opens a .cast picker. Lives here because watching a recording needs no
            // session — the terminal menu would hide it behind a live connection.
            val playerScope = rememberCoroutineScope()
            MoreRow(
                "play_circle", Skerry.colors.cyanBright, stringResource(Res.string.term_player_open), null, Skerry.colors.dim,
                onClick = { playerScope.launch { state.showCast(openCastFile()) } },
            )
            // Import hosts from an OpenSSH ssh_config file (desktop parity). Live path only: importing
            // needs a host store to write to. Picks + parses the file, then hands off to the sheet.
            val hostsForImport = LocalHosts.current
            MoreRow(
                "download", Skerry.colors.cyanBright, stringResource(Res.string.conn_import_action), null, Skerry.colors.dim,
                onClick = if (hostsForImport != null) {
                    { playerScope.launch { pickAndParseSshConfig()?.let(state::beginSshImport) } }
                } else {
                    null
                },
            )
            // Import a Remote Desktop Connection file (.rdp): one file is one profile, so the sheet
            // only confirms what it will create (desktop parity).
            MoreRow(
                "download", Skerry.colors.cyanBright, stringResource(Res.string.conn_rdp_import_action), null, Skerry.colors.dim,
                onClick = if (hostsForImport != null) {
                    { playerScope.launch { pickAndParseRdpFile()?.let(state::beginRdpImport) } }
                } else {
                    null
                },
            )
            // Keep-Alive settings: mobile background keep-alive, battery exemption & autostart guides.
            val keepAliveBridge = LocalKeepAliveBridge.current
            val keepAliveSupported = keepAliveBridge?.isKeepAliveConfigSupported ?: false
            if (keepAliveSupported || preview) {
                val isOptimized = keepAliveBridge?.isOptimizedForKeepAlive() ?: false
                MoreRow(
                    "battery_saver",
                    Skerry.colors.cyanBright,
                    stringResource(Res.string.keepalive_title),
                    if (isOptimized) stringResource(Res.string.keepalive_subtitle_optimal) else stringResource(Res.string.keepalive_subtitle_warning),
                    if (isOptimized) Skerry.colors.moss else Skerry.colors.amber,
                    onClick = { state.push(MobileRoute.KeepAlive) },
                )
            }
            // About: subtitle is the current version, or an amber "Update x.y.z" when a newer
            // release is known (the passive mobile counterpart of the desktop status-bar notice).
            val updateVersion = LocalUpdates.current?.available?.versionLabel
            MoreRow(
                "info", Skerry.colors.cyanBright, stringResource(Res.string.more_about),
                updateVersion?.let { stringResource(Res.string.settings_update_status, it) } ?: AppVersion.VERSION,
                if (updateVersion != null) Skerry.colors.amber else Skerry.colors.dim,
                onClick = { state.push(MobileRoute.About) },
            )
            MoreRow("lock", Skerry.colors.sunset, stringResource(Res.string.more_lock), null, Skerry.colors.dim, labelColor = Skerry.colors.sunset, divider = false, onClick = onLock)
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun portsSubtitle(): String {
    // Tunnels are a global section: the active count comes from the manager, not tied to the
    // open session. null (no manager: preview/offscreen) gives an empty subtitle.
    val manager = LocalTunnels.current ?: return mobileMorePortsSubtitle(null)
    return mobileMorePortsSubtitle(mobileActiveTunnelCount(manager.tunnels))
}

/** "Sync" row subtitle: sync coordinator status (none/local-only/connected). */
@Composable
private fun syncSubtitle(): String {
    val sync = LocalSync.current ?: return stringResource(Res.string.more_sync_local_only)
    return when (sync.status.collectAsState().value) {
        is app.skerry.ui.sync.SyncStatus.Online -> stringResource(Res.string.more_sync_synced)
        app.skerry.ui.sync.SyncStatus.Busy, is app.skerry.ui.sync.SyncStatus.NeedsPasswordReplaceConfirm -> stringResource(Res.string.more_sync_syncing)
        is app.skerry.ui.sync.SyncStatus.Configured -> stringResource(Res.string.more_sync_linked_locked)
        is app.skerry.ui.sync.SyncStatus.Failed -> stringResource(Res.string.more_sync_error)
        app.skerry.ui.sync.SyncStatus.Disabled -> stringResource(Res.string.more_sync_local_only)
    }
}

@Composable
private fun knownChanged(): Int = LocalKnownHosts.current?.mismatches?.size ?: 0

@Composable
private fun knownSubtitle(): String = mobileMoreKnownSubtitle(knownChanged())
