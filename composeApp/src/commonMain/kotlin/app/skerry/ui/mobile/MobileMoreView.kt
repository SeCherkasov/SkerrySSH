package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.SecurityEvent
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_badge_active
import app.skerry.ui.generated.resources.appearance_default_value
import app.skerry.ui.generated.resources.appearance_font
import app.skerry.ui.generated.resources.appearance_font_size
import app.skerry.ui.generated.resources.appearance_language
import app.skerry.ui.generated.resources.appearance_theme
import app.skerry.ui.generated.resources.theme_dark
import app.skerry.ui.generated.resources.theme_light
import app.skerry.ui.generated.resources.theme_system
import app.skerry.ui.generated.resources.appearance_letter_spacing
import app.skerry.ui.generated.resources.appearance_line_height
import app.skerry.ui.generated.resources.appearance_section_interface
import app.skerry.ui.generated.resources.appearance_section_terminal
import app.skerry.ui.generated.resources.settings_terminal_clipboard_write
import app.skerry.ui.generated.resources.settings_terminal_clipboard_write_desc
import app.skerry.ui.generated.resources.settings_terminal_cursor_style
import app.skerry.ui.generated.resources.settings_terminal_scrollback
import app.skerry.ui.generated.resources.appearance_title
import app.skerry.ui.generated.resources.more_about
import app.skerry.ui.generated.resources.more_ai_privacy
import app.skerry.ui.generated.resources.more_ai_subtitle_byok
import app.skerry.ui.generated.resources.more_ai_subtitle_off
import app.skerry.ui.generated.resources.more_ai_subtitle_local
import app.skerry.ui.generated.resources.more_biometric_prompt_cancel
import app.skerry.ui.generated.resources.more_biometric_prompt_subtitle
import app.skerry.ui.generated.resources.more_biometric_prompt_title
import app.skerry.ui.generated.resources.more_biometric_verify_subtitle
import app.skerry.ui.generated.resources.more_biometric_verify_title
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
import app.skerry.ui.generated.resources.more_title
import app.skerry.ui.i18n.UiLanguage
import app.skerry.ui.i18n.label
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.sync.accountCardModelLocalized
import app.skerry.ui.terminal.DEFAULT_TERMINAL_FONT_SIZE
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LETTER_SPACING
import app.skerry.ui.terminal.DEFAULT_TERMINAL_LINE_HEIGHT
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MAX
import app.skerry.ui.terminal.TERMINAL_FONT_SIZE_MIN
import app.skerry.ui.terminal.TERMINAL_SCROLLBACK_OPTIONS
import app.skerry.ui.terminal.TerminalCursorStyle
import app.skerry.ui.terminal.TerminalFont
import app.skerry.ui.terminal.TerminalTheme
import app.skerry.ui.terminal.TerminalThemes
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.vault.MIN_MASTER_PASSWORD_LENGTH
import app.skerry.ui.vault.VaultGateController
import app.skerry.ui.generated.resources.settings_badge_soon
import app.skerry.ui.generated.resources.settings_change
import app.skerry.ui.generated.resources.settings_manage
import app.skerry.ui.generated.resources.settings_recent_security_events
import app.skerry.ui.generated.resources.settings_security_2fa
import app.skerry.ui.generated.resources.settings_security_2fa_desc
import app.skerry.ui.generated.resources.settings_security_auto_lock
import app.skerry.ui.generated.resources.settings_security_auto_lock_desc
import app.skerry.ui.generated.resources.settings_security_account_password
import app.skerry.ui.generated.resources.settings_security_account_password_desc
import app.skerry.ui.generated.resources.settings_security_master_password
import app.skerry.ui.generated.resources.settings_security_no_events
import app.skerry.ui.generated.resources.settings_security_subtitle
import app.skerry.ui.generated.resources.settings_security_title
import app.skerry.ui.generated.resources.settings_update_status
import app.skerry.ui.generated.resources.settings_security_touch_id
import app.skerry.ui.generated.resources.settings_security_touch_id_desc
import app.skerry.ui.generated.resources.settings_security_touch_id_recheck
import app.skerry.ui.generated.resources.settings_security_touch_id_unsupported
import app.skerry.ui.generated.resources.settings_security_touch_id_weak_binding
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.Badge
import app.skerry.ui.settings.ChangeAccountPasswordDialog
import app.skerry.ui.settings.ChangeMasterPasswordDialog
import app.skerry.ui.generated.resources.term_player_open
import app.skerry.ui.terminal.openCastFile
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.skerry.ui.app.AppVersion
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalKnownHosts
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.settings.autoLockLabel
import app.skerry.ui.settings.cursorStyleLabel
import app.skerry.ui.settings.formatDecimal
import app.skerry.ui.settings.formatScrollback
import app.skerry.ui.settings.masterPasswordSubtitle
import app.skerry.ui.settings.securityEventLine
import app.skerry.ui.theme.Skerry
import app.skerry.ui.theme.ThemeMode

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
            // About: subtitle is the current version, or an amber "Update x.y.z" when a newer
            // release is known (the passive mobile counterpart of the desktop status-bar notice).
            val updateVersion = LocalUpdates.current?.available?.versionLabel
            MoreRow(
                "info", Skerry.colors.cyanBright, stringResource(Res.string.more_about),
                updateVersion?.let { stringResource(Res.string.settings_update_status, it) } ?: AppVersion.VERSION,
                if (updateVersion != null) Skerry.colors.amber else Skerry.colors.dim,
                onClick = { state.push(MobileRoute.About) },
            )
            // Recording player: opens a .cast picker. Lives here because watching a recording needs no
            // session — the terminal menu would hide it behind a live connection.
            val playerScope = rememberCoroutineScope()
            MoreRow(
                "play_circle", Skerry.colors.cyanBright, stringResource(Res.string.term_player_open), null, Skerry.colors.dim,
                onClick = { playerScope.launch { state.showCast(openCastFile()) } },
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

/**
 * Hub section row: leading icon + name + subtitle on the right + chevron. [onClick] == null
 * makes the row inert (no action). [divider] is the bottom line (absent on the last row).
 */
@Composable
private fun MoreRow(
    icon: String,
    iconColor: Color,
    label: String,
    subtitle: String?,
    subtitleColor: Color,
    labelColor: Color = Skerry.colors.text,
    divider: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) {
        base.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        base
    }
    Row(
        clickable.padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Sym(icon, size = 21.sp, color = iconColor)
        Txt(label, color = labelColor, size = 14.5.sp, modifier = Modifier.weight(1f))
        if (!subtitle.isNullOrEmpty()) Txt(subtitle, color = subtitleColor, size = 11.sp)
        if (onClick != null && labelColor != Skerry.colors.sunset) Sym("chevron_right", size = 20.sp, color = Skerry.colors.faint)
    }
    if (divider) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(Skerry.colors.cyan.copy(alpha = 0.05f)))
}

// Security (More -> Security push screen).

/**
 * More -> Security push screen (parity with the desktop [SecuritySection]). Master password
 * change (dialog -> [VaultGateController.changePassword]), a real biometric-unlock toggle (hidden
 * when the factor/hardware is unavailable), auto-lock threshold picker (wired into the gate's idle
 * timer via `state.autoLock`), event log, and a "last password change" subtitle from the real
 * [SecurityLog]. Two-factor shows a SOON badge (not implemented). Its own controller sits over the
 * shared vault/biometrics/log composition-locals: events go to the same file as desktop.
 * Without a vault (preview) it renders a neutral view with no live actions.
 */
@Composable
fun MobileSecurityScreen(state: MobileDesignState) {
    val vault = LocalVault.current
    val biometrics = LocalVaultBiometrics.current
    val log = LocalSecurityLog.current
    val controller = remember(vault, biometrics, log) {
        vault?.let { VaultGateController(it, biometrics, securityLog = log) }
    }
    val sync = LocalSync.current
    // Sync configured → the password is the account password (issue #32): the account-aware rotation
    // replaces the local-only master-password change (which would diverge this device, issue #28).
    // Derived from the status flow (reactive, no per-recomposition disk read).
    val syncStatus = sync?.status?.collectAsState()?.value
    val syncConfigured = syncStatus != null && syncStatus !is app.skerry.ui.sync.SyncStatus.Disabled
    var reload by remember { mutableStateOf(0) }
    var changePwOpen by remember { mutableStateOf(false) }
    var changeAccountPwOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobilePushHeader(stringResource(Res.string.settings_security_title), onBack = state::pop)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                Txt(stringResource(Res.string.settings_security_subtitle), color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))

                // Master password: subtitle is the real "last changed" from the log (or neutral text).
                // Reading the log is file I/O + JSON parsing, moved off the composition thread.
                val lastChange by produceState<String?>(null, controller, reload) {
                    value = withContext(Dispatchers.Default) { controller?.lastPasswordChangeAt() }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt(
                            stringResource(if (syncConfigured) Res.string.settings_security_account_password else Res.string.settings_security_master_password),
                            color = Skerry.colors.text,
                            size = 14.5.sp,
                        )
                        Txt(
                            if (syncConfigured) stringResource(Res.string.settings_security_account_password_desc) else masterPasswordSubtitle(lastChange),
                            color = Skerry.colors.dim,
                            size = 11.5.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    // Changing the password requires a live vault; without one it's dimmed/inert.
                    Txt(
                        stringResource(Res.string.settings_change),
                        color = if (controller != null) Skerry.colors.cyanBright else Skerry.colors.faint,
                        size = 13.sp,
                        weight = FontWeight.Medium,
                        modifier = if (controller != null) {
                            Modifier.clickable { if (syncConfigured) changeAccountPwOpen = true else changePwOpen = true }
                        } else {
                            Modifier
                        },
                    )
                }
                MobileSecurityDivider()

                // Biometric unlock: row shows only when the factor is available (nothing to configure
                // otherwise). A device whose enclave refuses to decrypt the vault (#23) gets the reason
                // and a re-check instead of a toggle that can't work.
                if (controller != null && controller.biometricUnsupported) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Txt(stringResource(Res.string.settings_security_touch_id), color = Skerry.colors.faint, size = 14.5.sp)
                            Txt(stringResource(Res.string.settings_security_touch_id_unsupported), color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        Txt(
                            stringResource(Res.string.settings_security_touch_id_recheck),
                            color = Skerry.colors.cyanBright,
                            size = 13.sp,
                            weight = FontWeight.Medium,
                            modifier = Modifier.clickable { controller.recheckBiometricSupport(); reload++ },
                        )
                    }
                    MobileSecurityDivider()
                } else if (controller != null && controller.canEnableBiometric()) {
                    // Prompt strings resolved in composable scope (stringResource can't be called in the onToggle lambda).
                    val enablePrompt = BiometricPrompt(
                        title = stringResource(Res.string.more_biometric_prompt_title),
                        cancelLabel = stringResource(Res.string.more_biometric_prompt_cancel),
                        subtitle = stringResource(Res.string.more_biometric_prompt_subtitle),
                    )
                    val verifyPrompt = BiometricPrompt(
                        title = stringResource(Res.string.more_biometric_verify_title),
                        cancelLabel = stringResource(Res.string.more_biometric_prompt_cancel),
                        subtitle = stringResource(Res.string.more_biometric_verify_subtitle),
                    )
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Txt(stringResource(Res.string.settings_security_touch_id), color = Skerry.colors.text, size = 14.5.sp)
                            // Same subtitle slot admits a weaker key binding when the device took one.
                            val desc = if (controller.biometricReducedBinding) Res.string.settings_security_touch_id_weak_binding
                            else Res.string.settings_security_touch_id_desc
                            Txt(stringResource(desc), color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        Toggle(
                            on = controller.biometricEnabled,
                            onToggle = {
                                if (controller.biometricInFlight) return@Toggle
                                scope.launch {
                                    if (controller.biometricEnabled) controller.disableBiometric()
                                    else controller.enableBiometric(enablePrompt, verifyPrompt)
                                    reload++
                                }
                            },
                        )
                    }
                    MobileSecurityDivider()
                }

                // Auto-lock: real idle threshold, wired into the VaultGate idle timer via state.autoLock.
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt(stringResource(Res.string.settings_security_auto_lock), color = Skerry.colors.text, size = 14.5.sp)
                        Txt(stringResource(Res.string.settings_security_auto_lock_desc), color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Box(Modifier.width(160.dp)) { MobileAutoLockPicker(state.autoLock, onPick = state::chooseAutoLock) }
                }
                MobileSecurityDivider()

                // Two-factor is not implemented yet: an honest SOON badge instead of a fake "enabled".
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // weight(fill=false): the long label wraps on its own without shrinking the badge.
                            Txt(stringResource(Res.string.settings_security_2fa), color = Skerry.colors.dim, size = 14.5.sp, modifier = Modifier.weight(1f, fill = false))
                            Badge(stringResource(Res.string.settings_badge_soon), bg = Skerry.colors.amber.copy(alpha = 0.10f), fg = Skerry.colors.amber, radius = 3, size = 9.sp)
                        }
                        Txt(stringResource(Res.string.settings_security_2fa_desc), color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Txt(stringResource(Res.string.settings_manage), color = Skerry.colors.faint, size = 13.sp)
                }

                // Recent security events from the real log (or "no events yet").
                Txt(stringResource(Res.string.settings_recent_security_events), color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                val events by produceState(emptyList<SecurityEvent>(), controller, reload) {
                    value = withContext(Dispatchers.Default) { controller?.recentSecurityEvents(8) ?: emptyList() }
                }
                if (events.isEmpty()) {
                    Txt(stringResource(Res.string.settings_security_no_events), color = Skerry.colors.faint, size = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
                } else {
                    events.forEach { event ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Txt("●", color = Skerry.colors.moss, size = 9.sp)
                            Txt(securityEventLine(event), color = Skerry.colors.dim, size = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
        // Change-master-password dialog is a modal overlay over the screen; back closes it first.
        if (changePwOpen && controller != null) {
            PlatformBackHandler(enabled = true) { changePwOpen = false }
            ChangeMasterPasswordDialog(
                controller = controller,
                onClose = { changePwOpen = false },
                onChanged = { reload++ },
            )
        }
        // Change-account-password dialog (issue #32) — same modal treatment, shown when sync is configured.
        if (changeAccountPwOpen && sync != null) {
            PlatformBackHandler(enabled = true) { changeAccountPwOpen = false }
            ChangeAccountPasswordDialog(
                sync = sync,
                onClose = { changeAccountPwOpen = false },
                onChanged = { reload++ },
            )
        }
    }
}

/** Divider line between Security section rows (layout tone). */
@Composable
private fun MobileSecurityDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Skerry.colors.cyan.copy(alpha = 0.05f)))
}

/** Auto-lock threshold dropdown (mobile) — reuses the Appearance trigger/menu. */
@Composable
private fun MobileAutoLockPicker(current: AutoLockDuration, onPick: (AutoLockDuration) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.autoLockLabel(), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                AutoLockDuration.entries.forEach { option ->
                    MobileDropdownOption(option.autoLockLabel(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

// Appearance (More -> Appearance push screen).

/**
 * More -> Appearance push screen: terminal theme picker (cards), font, and size. Both fonts
 * render without ligatures (see [app.skerry.ui.terminal.TerminalAppearance]).
 */
@Composable
fun MobileAppearanceScreen(state: MobileDesignState) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobilePushHeader(stringResource(Res.string.appearance_title), onBack = state::pop)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Txt(stringResource(Res.string.appearance_section_terminal), color = Skerry.colors.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            // Theme cards in a 2xN grid from the [TerminalThemes] catalog; selection applies to the terminal live.
            TerminalThemes.all.chunked(2).forEach { rowThemes ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (theme in rowThemes) {
                        MobileThemeCard(
                            theme = theme,
                            active = theme.id == state.terminalTheme.id,
                            onClick = { state.chooseTerminalTheme(theme) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowThemes.size == 1) Box(Modifier.weight(1f))
                }
            }
            FontSettingRow(stringResource(Res.string.appearance_font)) {
                MobileFontPicker(state.terminalFont, onPick = state::chooseTerminalFont)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Skerry.colors.cyan.copy(alpha = 0.05f)))
            MobileStepperRow(
                label = stringResource(Res.string.appearance_font_size),
                isDefault = state.terminalFontSize == DEFAULT_TERMINAL_FONT_SIZE,
                defaultText = "$DEFAULT_TERMINAL_FONT_SIZE px",
                onReset = { state.chooseTerminalFontSize(DEFAULT_TERMINAL_FONT_SIZE) },
            ) {
                NumberStepper(
                    value = state.terminalFontSize.toFloat(),
                    onValueChange = { state.chooseTerminalFontSize(it.roundToInt().coerceIn(TERMINAL_FONT_SIZE_MIN, TERMINAL_FONT_SIZE_MAX)) },
                    step = 1f,
                    format = { it.roundToInt().toString() },
                    parse = { it.trim().toIntOrNull()?.toFloat() },
                    suffix = "px",
                )
            }
            MobileStepperRow(
                label = stringResource(Res.string.appearance_line_height),
                isDefault = formatDecimal(state.terminalLineHeight, 2) == formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
                defaultText = formatDecimal(DEFAULT_TERMINAL_LINE_HEIGHT, 2),
                onReset = { state.chooseTerminalLineHeight(DEFAULT_TERMINAL_LINE_HEIGHT) },
            ) {
                NumberStepper(
                    value = state.terminalLineHeight,
                    onValueChange = state::chooseTerminalLineHeight,
                    step = 0.05f,
                    format = { formatDecimal(it, 2) },
                    parse = { it.trim().replace(',', '.').toFloatOrNull() },
                    fieldWidth = 52.dp,
                )
            }
            MobileStepperRow(
                label = stringResource(Res.string.appearance_letter_spacing),
                isDefault = formatDecimal(state.terminalLetterSpacing, 1) == formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1),
                defaultText = "${formatDecimal(DEFAULT_TERMINAL_LETTER_SPACING, 1)} px",
                onReset = { state.chooseTerminalLetterSpacing(DEFAULT_TERMINAL_LETTER_SPACING) },
            ) {
                NumberStepper(
                    value = state.terminalLetterSpacing,
                    onValueChange = state::chooseTerminalLetterSpacing,
                    step = 0.1f,
                    format = { formatDecimal(it, 1) },
                    parse = { it.trim().replace(',', '.').toFloatOrNull() },
                    suffix = "px",
                    fieldWidth = 52.dp,
                )
            }
            // Scrollback depth and default cursor style for new sessions (behaviour, desktop parity).
            // Both apply to new sessions at connect and are pushed live into already-open ones.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Skerry.colors.cyan.copy(alpha = 0.05f)))
            FontSettingRow(stringResource(Res.string.settings_terminal_scrollback)) {
                MobileScrollbackPicker(state.terminalScrollback, onPick = state::chooseTerminalScrollback)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Skerry.colors.cyan.copy(alpha = 0.05f)))
            FontSettingRow(stringResource(Res.string.settings_terminal_cursor_style)) {
                MobileCursorStylePicker(state.terminalCursorStyle, onPick = state::chooseTerminalCursorStyle)
            }
            // OSC 52 clipboard-write gate (default off, like xterm/kitty): keeps an untrusted host
            // from silently overwriting the system clipboard. Applies to new and already-open sessions.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Skerry.colors.cyan.copy(alpha = 0.05f)))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Txt(stringResource(Res.string.settings_terminal_clipboard_write), color = Skerry.colors.text, size = 13.5.sp, weight = FontWeight.Medium)
                    Txt(stringResource(Res.string.settings_terminal_clipboard_write_desc), color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Toggle(on = state.allowServerClipboardWrite, onToggle = state::toggleAllowServerClipboardWrite)
            }
            Txt(stringResource(Res.string.appearance_section_interface), color = Skerry.colors.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            FontSettingRow(stringResource(Res.string.appearance_theme)) {
                MobileThemePicker(state.themeMode, onPick = state::chooseThemeMode)
            }
            FontSettingRow(stringResource(Res.string.appearance_language)) {
                MobileLanguagePicker(state.uiLanguage, onPick = state::chooseUiLanguage)
            }
        }
    }
}

/**
 * Mobile terminal theme picker card: a mini `ls -la` preview in [theme]'s real colors; click
 * selects, active shows a cyan border + ACTIVE badge. Mirrors the desktop card from SettingsPanel.
 */
@Composable
private fun MobileThemeCard(
    theme: TerminalTheme,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = LocalFonts.current.mono
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (active) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().background(theme.background).padding(8.dp)) {
            Row { Txt("~ ", color = theme.ansi[2], size = 9.sp, font = mono); Txt("ls -la", color = theme.foreground, size = 9.sp, font = mono) }
            Row { Txt("drwxr-xr-x ", color = theme.ansi[6], size = 9.sp, font = mono); Txt("src", color = theme.ansi[4], size = 9.sp, font = mono) }
            Row { Txt("-rw-r--r-- ", color = theme.ansi[8], size = 9.sp, font = mono); Txt(".env", color = theme.ansi[3], size = 9.sp, font = mono) }
        }
        Row(
            Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(theme.displayName, color = Skerry.colors.text, size = 11.sp, weight = FontWeight.Medium, maxLines = 1)
            if (active) Badge(stringResource(Res.string.appearance_badge_active), bg = Skerry.colors.cyan14, fg = Skerry.colors.cyanBright, radius = 3, size = 8.sp)
        }
    }
}

/** UI language dropdown (System / English / Russian). */
@Composable
private fun MobileLanguagePicker(current: UiLanguage, onPick: (UiLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.label(), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                UiLanguage.entries.forEach { option ->
                    MobileDropdownOption(option.label(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

@Composable
private fun MobileThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.themeLabel(), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                ThemeMode.entries.forEach { option ->
                    MobileDropdownOption(option.themeLabel(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

@Composable
private fun ThemeMode.themeLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> Res.string.theme_system
        ThemeMode.LIGHT -> Res.string.theme_light
        ThemeMode.DARK -> Res.string.theme_dark
    }
)

/** Setting row: label on the left + a fixed-width control (dropdown) on the right. */
@Composable
private fun FontSettingRow(label: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt(label, color = Skerry.colors.text, size = 14.5.sp, modifier = Modifier.weight(1f))
        Box(Modifier.width(180.dp)) { control() }
    }
}

/** Terminal font dropdown (Hack / JetBrains Mono) — both without ligatures. */
@Composable
private fun MobileFontPicker(current: TerminalFont, onPick: (TerminalFont) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.displayName, onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                TerminalFont.entries.forEach { option ->
                    MobileDropdownOption(option.displayName, selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Scrollback-depth dropdown ([TERMINAL_SCROLLBACK_OPTIONS], lines; formatted as "10 000"). */
@Composable
private fun MobileScrollbackPicker(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(formatScrollback(current), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                TERMINAL_SCROLLBACK_OPTIONS.forEach { option ->
                    MobileDropdownOption(formatScrollback(option), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Cursor-style dropdown (shape × blink, [TerminalCursorStyle.entries]). */
@Composable
private fun MobileCursorStylePicker(current: TerminalCursorStyle, onPick: (TerminalCursorStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.cursorStyleLabel(), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                TerminalCursorStyle.entries.forEach { option ->
                    MobileDropdownOption(option.cursorStyleLabel(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Setting row with a stepper (mobile): label + default-value hint on the left, [NumberStepper] on the right. */
@Composable
private fun MobileStepperRow(
    label: String,
    isDefault: Boolean,
    defaultText: String,
    onReset: () -> Unit,
    stepper: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Txt(label, color = Skerry.colors.text, size = 14.5.sp)
            MobileDefaultValueHint(isDefault, defaultText, onReset)
        }
        stepper()
    }
}

/** Default-value hint (mobile): gray text when at default, cyan clickable reset when changed. */
@Composable
private fun MobileDefaultValueHint(isDefault: Boolean, defaultText: String, onReset: () -> Unit) {
    val text = stringResource(Res.string.appearance_default_value, defaultText)
    if (isDefault) {
        Txt(text, color = Skerry.colors.faint, size = 12.sp, modifier = Modifier.padding(top = 3.dp))
    } else {
        Row(
            Modifier.padding(top = 3.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onReset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Sym("restart_alt", size = 14.sp, color = Skerry.colors.cyan)
            Txt(text, color = Skerry.colors.cyan, size = 12.sp)
        }
    }
}

/** Select trigger: value on the left, chevron on the right. */
@Composable
private fun MobileSelectTrigger(value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = Skerry.colors.text, size = 13.sp)
        Sym("expand_more", size = 18.sp, color = Skerry.colors.faint)
    }
}

/** Dropdown menu column (surface + border per layout). */
@Composable
private fun MobileDropdownMenu(width: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(8.dp)).background(Skerry.colors.surface2).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp)),
    ) { content() }
}

/** Dropdown option; the selected one is highlighted cyan. */
@Composable
private fun MobileDropdownOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Txt(
        label,
        color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text,
        size = 13.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

// Profile.

/**
 * Live profile card: reflects the real sync state — not configured shows a local vault
 * ("Encrypted on this device"), connected shows accountId + server host. Sync management
 * (set up / reconnect / disconnect / devices) lives on the "Sync" screen.
 *
 * Branching into a separate [LiveLocalVaultCard] (instead of a conditional `collectAsState` in one
 * body) keeps composable calls unconditional — the Compose slot-table rule, same as on desktop
 * ([LocalSync] is stable, but the strict pattern is safer for future refactors).
 */
@Composable
private fun LocalVaultCard() {
    when (val sync = LocalSync.current) {
        null -> AccountProfileCard(accountCardModelLocalized(null))
        else -> LiveLocalVaultCard(sync)
    }
}

@Composable
private fun LiveLocalVaultCard(sync: app.skerry.ui.sync.SyncCoordinator) {
    AccountProfileCard(accountCardModelLocalized(sync.status.collectAsState().value, sync.savedConfig?.serverUrl))
}

@Composable
private fun AccountProfileCard(model: app.skerry.ui.sync.AccountCardModel) {
    ProfileCard(initials = model.initials, avatarBg = Skerry.colors.cyan, title = model.title, subtitle = model.subtitle, badge = null)
}

/** Static profile card (preview/offscreen). */
@Composable
private fun MockProfileCard() {
    ProfileCard(initials = "MK", avatarBg = Skerry.colors.cyan, title = "Maya Kovac", subtitle = "maya@skerry.dev", badge = "PRO")
}

@Composable
private fun ProfileCard(initials: String, avatarBg: Color, title: String, subtitle: String, badge: String?) {
    Row(
        Modifier
            .padding(horizontal = 18.dp)
            .padding(bottom = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(avatarBg), contentAlignment = Alignment.Center) {
            Txt(initials, color = Skerry.colors.ink, size = 16.sp, weight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold)
            Txt(subtitle, color = Skerry.colors.dim, size = 12.sp)
        }
        if (badge != null) {
            Badge(badge, bg = Skerry.colors.amber.copy(alpha = 0.14f), fg = Skerry.colors.amber, radius = 20, size = 9.5.sp)
        }
    }
}
