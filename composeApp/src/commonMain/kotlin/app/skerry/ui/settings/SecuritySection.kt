package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.SecurityEvent
import app.skerry.shared.vault.SecurityEventType
import app.skerry.shared.vault.securityMoment
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.Badge
import app.skerry.ui.design.DropdownField
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.more_biometric_prompt_cancel
import app.skerry.ui.generated.resources.more_biometric_prompt_subtitle
import app.skerry.ui.generated.resources.more_biometric_prompt_title
import app.skerry.ui.generated.resources.more_biometric_verify_subtitle
import app.skerry.ui.generated.resources.more_biometric_verify_title
import app.skerry.ui.generated.resources.settings_autolock_15m
import app.skerry.ui.generated.resources.settings_autolock_1m
import app.skerry.ui.generated.resources.settings_autolock_30m
import app.skerry.ui.generated.resources.settings_autolock_5m
import app.skerry.ui.generated.resources.settings_autolock_never
import app.skerry.ui.generated.resources.settings_badge_soon
import app.skerry.ui.generated.resources.settings_cancel
import app.skerry.ui.generated.resources.settings_change
import app.skerry.ui.generated.resources.settings_change_pw_confirm
import app.skerry.ui.generated.resources.settings_change_pw_current
import app.skerry.ui.generated.resources.settings_change_pw_err_wrong
import app.skerry.ui.generated.resources.settings_change_pw_new
import app.skerry.ui.generated.resources.settings_change_pw_submit
import app.skerry.ui.generated.resources.settings_change_pw_title
import app.skerry.ui.generated.resources.settings_event_biometric_disabled
import app.skerry.ui.generated.resources.settings_event_biometric_enabled
import app.skerry.ui.generated.resources.settings_event_device_paired
import app.skerry.ui.generated.resources.settings_event_line
import app.skerry.ui.generated.resources.settings_event_password_changed
import app.skerry.ui.generated.resources.settings_event_unlocked_biometric
import app.skerry.ui.generated.resources.settings_event_vault_created
import app.skerry.ui.generated.resources.settings_event_with_detail
import app.skerry.ui.generated.resources.settings_manage
import app.skerry.ui.generated.resources.settings_recent_security_events
import app.skerry.ui.generated.resources.settings_security_2fa
import app.skerry.ui.generated.resources.settings_security_2fa_desc
import app.skerry.ui.generated.resources.settings_security_auto_lock
import app.skerry.ui.generated.resources.settings_security_auto_lock_desc
import app.skerry.ui.generated.resources.settings_security_master_password
import app.skerry.ui.generated.resources.settings_security_master_password_desc
import app.skerry.ui.generated.resources.settings_security_no_events
import app.skerry.ui.generated.resources.settings_security_pw_changed_days
import app.skerry.ui.generated.resources.settings_security_pw_changed_today
import app.skerry.ui.generated.resources.settings_security_pw_changed_yesterday
import app.skerry.ui.generated.resources.settings_security_biometric
import app.skerry.ui.generated.resources.settings_security_biometric_desc
import app.skerry.ui.generated.resources.settings_security_biometric_recheck
import app.skerry.ui.generated.resources.settings_security_biometric_unsupported
import app.skerry.ui.generated.resources.settings_security_biometric_weak_binding
import app.skerry.ui.generated.resources.settings_time_days_ago
import app.skerry.ui.generated.resources.settings_time_today
import app.skerry.ui.generated.resources.settings_time_yesterday
import app.skerry.ui.generated.resources.vtail_error_password_mismatch
import app.skerry.ui.generated.resources.vtail_error_password_too_short
import app.skerry.ui.generated.resources.settings_change_account_pw_err_rewrap
import app.skerry.ui.generated.resources.settings_change_account_pw_note
import app.skerry.ui.generated.resources.settings_change_account_pw_submit
import app.skerry.ui.generated.resources.settings_change_account_pw_title
import app.skerry.ui.generated.resources.settings_security_account_password
import app.skerry.ui.generated.resources.settings_security_account_password_desc
import app.skerry.ui.sync.AccountPasswordChange
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.sync.SyncFailureReason
import app.skerry.ui.sync.SyncField
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.syncFailureText
import app.skerry.ui.vault.AutoLockDuration
import app.skerry.ui.vault.MIN_MASTER_PASSWORD_LENGTH
import app.skerry.ui.vault.VaultGateController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

// Security section: master password, biometrics, auto-lock, event log.

/**
 * Live Security section: master password change ([VaultGateController.changePassword] via a
 * dialog), a real biometric-unlock toggle (hidden when hardware/factor is unavailable), auto-lock
 * threshold (applied to the gate's idle timer), event log, and "last password change" subtitle
 * from the real security log. Two-factor auth is marked SOON (not implemented). [controller] ==
 * null means mock/preview without a vault: a neutral view with no live actions.
 */
@Composable
internal fun SecuritySection(
    state: DesktopDesignState,
    controller: VaultGateController?,
    reload: Int,
    // When sync is configured the password IS the account password (one password model, issue #28):
    // the local-only "Change master password" would diverge this device from the account, so we swap
    // it for the account-aware "Change account password" (issue #32) instead of showing both.
    syncConfigured: Boolean,
    onChangeMasterPassword: () -> Unit,
    onChangeAccountPassword: () -> Unit,
    onBiometricToggled: () -> Unit,
) {

    // Master password subtitle is the real "last changed" from the log (or a neutral fallback).
    // Reading the log is file I/O + JSON parsing, so it runs off the composition thread.
    val lastChange by produceState<String?>(null, controller, reload) {
        value = withContext(Dispatchers.Default) { controller?.lastPasswordChangeAt() }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(
                stringResource(if (syncConfigured) Res.string.settings_security_account_password else Res.string.settings_security_master_password),
                color = Skerry.colors.text,
                size = 13.sp,
                weight = FontWeight.Medium,
            )
            Txt(
                if (syncConfigured) stringResource(Res.string.settings_security_account_password_desc) else masterPasswordSubtitle(lastChange),
                color = Skerry.colors.dim,
                size = 11.5.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        // Change is only available with a live vault; in mock/preview the button is inert.
        if (controller != null) {
            GhostButton(stringResource(Res.string.settings_change), onClick = if (syncConfigured) onChangeAccountPassword else onChangeMasterPassword)
        } else {
            GhostButton(stringResource(Res.string.settings_change), onClick = {}, fg = Skerry.colors.faint, border = Skerry.colors.line)
        }
    }
    HLine()

    // Biometric unlock row only shown when biometrics are available on the device (e.g. hidden on
    // headless desktop Linux — nothing to configure). A device whose enclave refuses to decrypt the
    // vault (#23) keeps the row, off and inert, with the reason and a re-check — dropping it silently
    // would read as "Skerry has no biometrics".
    val scope = rememberCoroutineScope()
    if (controller != null && controller.biometricUnsupported) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(stringResource(Res.string.settings_security_biometric), color = Skerry.colors.faint, size = 13.sp, weight = FontWeight.Medium)
                Txt(
                    stringResource(Res.string.settings_security_biometric_unsupported),
                    color = Skerry.colors.dim,
                    size = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            GhostButton(
                stringResource(Res.string.settings_security_biometric_recheck),
                onClick = { controller.recheckBiometricSupport() },
            )
        }
        HLine()
    } else if (controller != null && controller.canEnableBiometric()) {
        // Prompt strings are resolved here (stringResource needs composable scope) and handed to the
        // coroutine below; they share the mobile prompt wording (MobileMoreView) — the same system
        // dialog. The second one labels the round-trip check that enable() performs.
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
        SettingToggleRow(
            stringResource(Res.string.settings_security_biometric),
            // The subtitle doubles as the place to admit a weaker key binding when that's what the
            // device would take (see VaultGateController.biometricReducedBinding).
            if (controller.biometricReducedBinding) stringResource(Res.string.settings_security_biometric_weak_binding)
            else stringResource(Res.string.settings_security_biometric_desc),
            on = controller.biometricEnabled,
            onToggle = {
                if (controller.biometricInFlight) return@SettingToggleRow
                scope.launch {
                    if (controller.biometricEnabled) controller.disableBiometric()
                    else controller.enableBiometric(enablePrompt, verifyPrompt)
                    onBiometricToggled()
                }
            },
        )
        HLine()
    }

    // Auto-lock: real idle threshold, applied to VaultGate's idle timer via state.autoLock.
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(Res.string.settings_security_auto_lock), color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(stringResource(Res.string.settings_security_auto_lock_desc), color = Skerry.colors.dim, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.width(170.dp)) { AutoLockPicker(state.autoLock, onPick = state::chooseAutoLock) }
    }
    HLine()

    // Two-factor auth isn't implemented yet: SOON badge instead of a fake "enabled" state.
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(stringResource(Res.string.settings_security_2fa), color = Skerry.colors.dim, size = 13.sp, weight = FontWeight.Medium)
                Badge(stringResource(Res.string.settings_badge_soon), bg = Skerry.colors.amber.copy(alpha = 0.10f), fg = Skerry.colors.amber, radius = 3, size = 9.sp)
            }
            Txt(stringResource(Res.string.settings_security_2fa_desc), color = Skerry.colors.faint, size = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
        // Inert (dimmed) button: feature not ready yet.
        GhostButton(stringResource(Res.string.settings_manage), onClick = {}, fg = Skerry.colors.faint, border = Skerry.colors.line)
    }
    HLine()

    // Recent security events from the real log (or "no events yet").
    SectionLabel(stringResource(Res.string.settings_recent_security_events), top = 16.dp, bottom = 8.dp)
    val events by produceState(emptyList<SecurityEvent>(), controller, reload) {
        value = withContext(Dispatchers.Default) { controller?.recentSecurityEvents(8) ?: emptyList() }
    }
    if (events.isEmpty()) {
        Txt(stringResource(Res.string.settings_security_no_events), color = Skerry.colors.faint, size = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
    } else {
        events.forEach { event ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("●", color = Skerry.colors.moss, size = 9.sp)
                Txt(securityEventLine(event), color = Skerry.colors.dim, size = 12.sp)
            }
        }
    }
}

/** Master password subtitle: real "last changed" from the log, or a neutral fallback. */
@Composable
internal fun masterPasswordSubtitle(lastChangeAt: String?): String {
    val moment = lastChangeAt?.let { securityMoment(it) }
        ?: return stringResource(Res.string.settings_security_master_password_desc)
    return when (moment.daysAgo) {
        0 -> stringResource(Res.string.settings_security_pw_changed_today)
        1 -> stringResource(Res.string.settings_security_pw_changed_yesterday)
        else -> stringResource(Res.string.settings_security_pw_changed_days, moment.daysAgo)
    }
}

/** Log event row: "Label[: detail] · relative time". */
@Composable
internal fun securityEventLine(event: SecurityEvent): String {
    val label = event.type.eventLabel()
    val head = event.detail?.let { stringResource(Res.string.settings_event_with_detail, label, it) } ?: label
    return stringResource(Res.string.settings_event_line, head, securityEventTime(event.at))
}

/** Localized event type label. */
@Composable
private fun SecurityEventType.eventLabel(): String = stringResource(
    when (this) {
        SecurityEventType.VaultCreated -> Res.string.settings_event_vault_created
        SecurityEventType.MasterPasswordChanged -> Res.string.settings_event_password_changed
        SecurityEventType.BiometricEnabled -> Res.string.settings_event_biometric_enabled
        SecurityEventType.BiometricDisabled -> Res.string.settings_event_biometric_disabled
        SecurityEventType.UnlockedBiometric -> Res.string.settings_event_unlocked_biometric
        SecurityEventType.DevicePaired -> Res.string.settings_event_device_paired
    },
)

/** Relative event time ("today 09:02" / "yesterday …" / "N days ago"); falls back to the raw timestamp. */
@Composable
private fun securityEventTime(at: String): String {
    val moment = securityMoment(at) ?: return at
    return when (moment.daysAgo) {
        0 -> stringResource(Res.string.settings_time_today, moment.timeOfDay)
        1 -> stringResource(Res.string.settings_time_yesterday, moment.timeOfDay)
        else -> stringResource(Res.string.settings_time_days_ago, moment.daysAgo)
    }
}

/** Auto-lock threshold dropdown ([AutoLockDuration.entries]). */
@Composable
private fun AutoLockPicker(current: AutoLockDuration, onPick: (AutoLockDuration) -> Unit) {
    DropdownField(current, AutoLockDuration.entries, label = { it.autoLockLabel() }, onPick = onPick)
}

/** Localized auto-lock threshold label. */
@Composable
internal fun AutoLockDuration.autoLockLabel(): String = stringResource(
    when (this) {
        AutoLockDuration.OneMinute -> Res.string.settings_autolock_1m
        AutoLockDuration.FiveMinutes -> Res.string.settings_autolock_5m
        AutoLockDuration.FifteenMinutes -> Res.string.settings_autolock_15m
        AutoLockDuration.ThirtyMinutes -> Res.string.settings_autolock_30m
        AutoLockDuration.Never -> Res.string.settings_autolock_never
    },
)

/**
 * Master password change dialog: current + new + confirm. The submit button is enabled only for
 * valid input (new password >= [MIN_MASTER_PASSWORD_LENGTH] and matches confirm). The only submit
 * failure is a wrong current password. Success closes the dialog and refreshes the log
 * ([onChanged]). Field string buffers persist in the slot table until recomposition (same as the
 * unlock form); the vault receives the password as a [CharArray] which the controller wipes.
 */
@Composable
internal fun ChangeMasterPasswordDialog(
    controller: VaultGateController,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var wrongCurrent by remember { mutableStateOf(false) }
    // Argon2id key derivation (twice) + vault rewrite are intentionally expensive; run them on a
    // background dispatcher, not the composition thread, or the UI freezes (ANR on Android).
    // [busy] disables the button while the change is in flight to prevent re-triggering it.
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val tooShort = fresh.isNotEmpty() && fresh.length < MIN_MASTER_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && fresh != confirm
    val canSubmit = current.isNotEmpty() && fresh.length >= MIN_MASTER_PASSWORD_LENGTH && fresh == confirm && !busy

    val submit = {
        if (canSubmit) {
            wrongCurrent = false
            busy = true
            // Snapshot the buffers before launching the coroutine (the controller wipes them);
            // string hygiene in the slot table is out of scope here, same as the unlock form.
            val old = current.toCharArray()
            val next = fresh.toCharArray()
            scope.launch {
                val ok = withContext(Dispatchers.Default) { controller.changePassword(old, next) }
                busy = false
                if (ok) { onChanged(); onClose() } else wrongCurrent = true
            }
            Unit
        }
    }

    val error: String? = when {
        wrongCurrent -> stringResource(Res.string.settings_change_pw_err_wrong)
        tooShort -> stringResource(Res.string.vtail_error_password_too_short, MIN_MASTER_PASSWORD_LENGTH)
        mismatch -> stringResource(Res.string.vtail_error_password_mismatch)
        else -> null
    }

    ModalScrim(onDismiss = onClose) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) {
            Txt(stringResource(Res.string.settings_change_pw_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold)
            Box(Modifier.height(16.dp))
            FieldLabel(stringResource(Res.string.settings_change_pw_current), top = 0.dp)
            SyncField(placeholder = "••••••••", value = current, icon = "lock", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true) { current = it; wrongCurrent = false }
            FieldLabel(stringResource(Res.string.settings_change_pw_new))
            SyncField(placeholder = "••••••••", value = fresh, icon = "lock_reset", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true) { fresh = it }
            FieldLabel(stringResource(Res.string.settings_change_pw_confirm))
            SyncField(placeholder = "••••••••", value = confirm, icon = "lock_reset", keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, secret = true, onSubmit = submit) { confirm = it }
            if (error != null) Txt(error, color = Skerry.colors.storm, size = 11.5.sp, modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.settings_cancel), color = Skerry.colors.dim, size = 12.5.sp)
                }
                PrimaryButton(
                    stringResource(Res.string.settings_change_pw_submit),
                    onClick = submit,
                    enabled = canSubmit,
                    bg = if (canSubmit) Skerry.colors.cyan else Skerry.colors.cyan10,
                    fg = if (canSubmit) Skerry.colors.ink else Skerry.colors.faint,
                )
            }
        }
    }
}

/**
 * Change ACCOUNT password dialog (issue #32), shown instead of [ChangeMasterPasswordDialog] when sync
 * is configured. Same three fields, but the submit rotates the account password over the network
 * ([SyncCoordinator.changeAccountPassword]): the server swaps the SRP verifier + wrapped dataKey and
 * revokes the other devices, and the local vault is re-wrapped under the new password. The note warns
 * that this affects every synced device. [LocalRewrapFailed] gets its own message — the account did
 * rotate, only this device must reconnect.
 */
@Composable
internal fun ChangeAccountPasswordDialog(
    sync: SyncCoordinator,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    // Last coordinator result (kept as the typed value so the localized error text is resolved in
    // composition via the @Composable syncFailureText); cleared on the next edit.
    var result by remember { mutableStateOf<AccountPasswordChange?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val tooShort = fresh.isNotEmpty() && fresh.length < MIN_MASTER_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && fresh != confirm
    val canSubmit = current.isNotEmpty() && fresh.length >= MIN_MASTER_PASSWORD_LENGTH && fresh == confirm && !busy

    val submit = {
        if (canSubmit) {
            result = null
            busy = true
            val old = current.toCharArray()
            val next = fresh.toCharArray()
            scope.launch {
                val r = withContext(Dispatchers.Default) { sync.changeAccountPassword(old, next) }
                busy = false
                if (r is AccountPasswordChange.Success) { onChanged(); onClose() } else result = r
            }
            Unit
        }
    }

    val error: String? = when (val r = result) {
        is AccountPasswordChange.WrongCurrentPassword -> stringResource(Res.string.settings_change_pw_err_wrong)
        is AccountPasswordChange.LocalRewrapFailed -> stringResource(Res.string.settings_change_account_pw_err_rewrap)
        is AccountPasswordChange.Failed -> syncFailureText(SyncStatus.Failed(r.reason, r.detail))
        is AccountPasswordChange.NotConfigured -> syncFailureText(SyncStatus.Failed(SyncFailureReason.ConnectFailed))
        // Success never renders (the coroutine closed the dialog); null is the untouched state — both
        // fall through to input validation. Exhaustive over the sealed type so a new variant won't
        // silently land in a validation message.
        is AccountPasswordChange.Success, null -> when {
            tooShort -> stringResource(Res.string.vtail_error_password_too_short, MIN_MASTER_PASSWORD_LENGTH)
            mismatch -> stringResource(Res.string.vtail_error_password_mismatch)
            else -> null
        }
    }

    ModalScrim(onDismiss = onClose) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) {
            Txt(stringResource(Res.string.settings_change_account_pw_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold)
            Box(Modifier.height(8.dp))
            Txt(stringResource(Res.string.settings_change_account_pw_note), color = Skerry.colors.dim, size = 11.5.sp)
            Box(Modifier.height(14.dp))
            FieldLabel(stringResource(Res.string.settings_change_pw_current), top = 0.dp)
            SyncField(placeholder = "••••••••", value = current, icon = "lock", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true) { current = it; result = null }
            FieldLabel(stringResource(Res.string.settings_change_pw_new))
            SyncField(placeholder = "••••••••", value = fresh, icon = "lock_reset", keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, secret = true) { fresh = it; result = null }
            FieldLabel(stringResource(Res.string.settings_change_pw_confirm))
            SyncField(placeholder = "••••••••", value = confirm, icon = "lock_reset", keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, secret = true, onSubmit = submit) { confirm = it; result = null }
            if (error != null) Txt(error, color = Skerry.colors.storm, size = 11.5.sp, modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.settings_cancel), color = Skerry.colors.dim, size = 12.5.sp)
                }
                PrimaryButton(
                    stringResource(Res.string.settings_change_account_pw_submit),
                    onClick = submit,
                    enabled = canSubmit,
                    bg = if (canSubmit) Skerry.colors.cyan else Skerry.colors.cyan10,
                    fg = if (canSubmit) Skerry.colors.ink else Skerry.colors.faint,
                )
            }
        }
    }
}
