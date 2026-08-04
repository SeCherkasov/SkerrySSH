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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.SecurityEvent
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.more_biometric_prompt_cancel
import app.skerry.ui.generated.resources.more_biometric_prompt_subtitle
import app.skerry.ui.generated.resources.more_biometric_prompt_title
import app.skerry.ui.generated.resources.more_biometric_verify_subtitle
import app.skerry.ui.generated.resources.more_biometric_verify_title
import app.skerry.ui.i18n.label
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.sync.accountCardModelLocalized
import app.skerry.ui.vault.VaultGateController
import app.skerry.ui.generated.resources.settings_badge_soon
import app.skerry.ui.generated.resources.settings_change
import app.skerry.ui.generated.resources.settings_manage
import app.skerry.ui.generated.resources.settings_recent_security_events
import app.skerry.ui.generated.resources.settings_security_2fa
import app.skerry.ui.generated.resources.settings_security_2fa_desc
import app.skerry.ui.generated.resources.settings_security_auto_lock
import app.skerry.ui.generated.resources.settings_security_report_team_sessions
import app.skerry.ui.generated.resources.settings_security_report_team_sessions_desc
import app.skerry.ui.generated.resources.settings_security_auto_lock_desc
import app.skerry.ui.generated.resources.settings_security_account_password
import app.skerry.ui.generated.resources.settings_security_account_password_desc
import app.skerry.ui.generated.resources.settings_security_master_password
import app.skerry.ui.generated.resources.settings_security_no_events
import app.skerry.ui.generated.resources.settings_security_subtitle
import app.skerry.ui.generated.resources.settings_security_title
import app.skerry.ui.generated.resources.settings_security_biometric
import app.skerry.ui.generated.resources.settings_security_biometric_desc
import app.skerry.ui.generated.resources.settings_security_biometric_recheck
import app.skerry.ui.generated.resources.settings_security_biometric_unsupported
import app.skerry.ui.generated.resources.settings_security_biometric_weak_binding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.Badge
import app.skerry.ui.settings.ChangeAccountPasswordDialog
import app.skerry.ui.settings.ChangeMasterPasswordDialog
import app.skerry.ui.design.HLine
import app.skerry.ui.app.LocalSecurityLog
import app.skerry.ui.app.LocalSync
import app.skerry.ui.app.LocalVault
import app.skerry.ui.app.LocalVaultBiometrics
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.settings.masterPasswordSubtitle
import app.skerry.ui.settings.securityEventLine
import app.skerry.ui.theme.Skerry

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
                HLine()

                // Biometric unlock: row shows only when the factor is available (nothing to configure
                // otherwise). A device whose enclave refuses to decrypt the vault (#23) gets the reason
                // and a re-check instead of a toggle that can't work.
                if (controller != null && controller.biometricUnsupported) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Txt(stringResource(Res.string.settings_security_biometric), color = Skerry.colors.faint, size = 14.5.sp)
                            Txt(stringResource(Res.string.settings_security_biometric_unsupported), color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        Txt(
                            stringResource(Res.string.settings_security_biometric_recheck),
                            color = Skerry.colors.cyanBright,
                            size = 13.sp,
                            weight = FontWeight.Medium,
                            modifier = Modifier.clickable { controller.recheckBiometricSupport(); reload++ },
                        )
                    }
                    HLine()
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
                            Txt(stringResource(Res.string.settings_security_biometric), color = Skerry.colors.text, size = 14.5.sp)
                            // Same subtitle slot admits a weaker key binding when the device took one.
                            val desc = if (controller.biometricReducedBinding) Res.string.settings_security_biometric_weak_binding
                            else Res.string.settings_security_biometric_desc
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
                    HLine()
                }

                // Auto-lock: real idle threshold, wired into the VaultGate idle timer via state.autoLock.
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt(stringResource(Res.string.settings_security_auto_lock), color = Skerry.colors.text, size = 14.5.sp)
                        Txt(stringResource(Res.string.settings_security_auto_lock_desc), color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Box(Modifier.width(160.dp)) { MobileAutoLockPicker(state.autoLock, onPick = state::chooseAutoLock) }
                }
                HLine()

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

                HLine()

                // Desktop parity (Settings -> Security): what this device tells a team about our
                // own sessions on hosts shared with it.
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt(stringResource(Res.string.settings_security_report_team_sessions), color = Skerry.colors.text, size = 14.5.sp)
                        Txt(stringResource(Res.string.settings_security_report_team_sessions_desc), color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Toggle(on = state.reportTeamSessions, onToggle = state::toggleReportTeamSessions)
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
internal fun LocalVaultCard() {
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
internal fun MockProfileCard() {
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
