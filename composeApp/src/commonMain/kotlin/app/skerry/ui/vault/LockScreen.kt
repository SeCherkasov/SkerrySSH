package app.skerry.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.vault.RESET_CONFIRM_WORD
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGateError
import app.skerry.ui.vault.vaultGateErrorMessage
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_lock_title
import app.skerry.ui.generated.resources.shell_unlock_subtitle_desktop
import app.skerry.ui.generated.resources.shell_master_password
import app.skerry.ui.generated.resources.shell_unlock
import app.skerry.ui.generated.resources.shell_forgot_password
import app.skerry.ui.generated.resources.shell_footer_never_leaves
import app.skerry.ui.generated.resources.shell_corrupted_title
import app.skerry.ui.generated.resources.shell_corrupted_subtitle
import app.skerry.ui.generated.resources.shell_reset_skerry
import app.skerry.ui.generated.resources.shell_reset_subtitle
import app.skerry.ui.generated.resources.shell_reset_scope_secrets_title
import app.skerry.ui.generated.resources.shell_reset_scope_secrets_subtitle
import app.skerry.ui.generated.resources.shell_reset_scope_all_title
import app.skerry.ui.generated.resources.shell_reset_scope_all_subtitle
import app.skerry.ui.generated.resources.shell_reset_confirm_placeholder
import app.skerry.ui.generated.resources.shell_reset_permanently
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_create_title
import app.skerry.ui.generated.resources.shell_create_subtitle
import app.skerry.ui.generated.resources.shell_repeat_password
import app.skerry.ui.generated.resources.shell_create_vault
import app.skerry.ui.generated.resources.shell_pairing_link
import app.skerry.ui.generated.resources.shell_no_recovery_ack
import app.skerry.ui.generated.resources.shell_password_strength
import app.skerry.ui.generated.resources.shell_password_strength_weak
import app.skerry.ui.generated.resources.shell_password_strength_fair
import app.skerry.ui.generated.resources.shell_password_strength_good
import app.skerry.ui.generated.resources.shell_password_strength_strong
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.BrandMark
import app.skerry.ui.design.D
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.sync.PairingJoinScreen
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

/**
 * Lock overlay (mock path): radial background, large logo, master password field and buttons. Unlock
 * here is a stub ([DesktopDesignState.unlock]); the live gate over `VaultGateController` renders
 * [DesktopUnlockScreen]/[DesktopCreateScreen].
 */
@Composable
fun LockScreen(state: DesktopDesignState) {
    var pwd by remember { mutableStateOf("") }
    LockScaffold(
        title = stringResource(Res.string.shell_lock_title),
        subtitle = stringResource(Res.string.shell_unlock_subtitle_desktop),
    ) {
        LockPasswordField(pwd, { pwd = it }, stringResource(Res.string.shell_master_password), ImeAction.Done, onSubmit = state::unlock)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(Res.string.shell_unlock), onClick = state::unlock, modifier = Modifier.weight(1f))
            BiometricButton(onClick = state::unlock)
        }
    }
}

/**
 * Live unlock form: same visuals as [LockScreen] but over [VaultGateController] (via the `unlockForm`
 * slot in [app.skerry.ui.vault.VaultGate]). On submit the password goes to [onUnlock] as a [CharArray]
 * and is wiped by the controller; [error] is localized by [vaultGateErrorMessage]. The biometric button
 * shows only when [canUseBiometric].
 */
@Composable
fun DesktopUnlockScreen(
    error: VaultGateError?,
    canUseBiometric: Boolean,
    onUnlock: (CharArray) -> Unit,
    onBiometric: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    val submit = { if (pwd.isNotEmpty()) onUnlock(pwd.toCharArray()) }
    // Biometrics enabled — trigger the system prompt right on entering the form (once); the fingerprint
    // button remains for a retry.
    if (canUseBiometric) {
        LaunchedEffect(Unit) { onBiometric() }
    }
    LockScaffold(
        title = stringResource(Res.string.shell_lock_title),
        subtitle = stringResource(Res.string.shell_unlock_subtitle_desktop),
        error = error,
    ) {
        LockPasswordField(pwd, { pwd = it }, stringResource(Res.string.shell_master_password), ImeAction.Done, onSubmit = submit, autoFocus = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(Res.string.shell_unlock), onClick = submit, modifier = Modifier.weight(1f))
            if (canUseBiometric) BiometricButton(onClick = onBiometric)
        }
        // A forgotten password can only be resolved by a reset (zero-knowledge): an unobtrusive link
        // under the button leads to the confirmation screen.
        Txt(
            stringResource(Res.string.shell_forgot_password),
            color = D.faint,
            size = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onForgotPassword)
                .padding(vertical = 4.dp),
        )
    }
}

/**
 * Corrupted vault file screen. The file can't be read → no password entry; the only action is to go to
 * reset confirmation ([onReset]). Amber warning icon.
 */
@Composable
fun DesktopCorruptedScreen(onReset: () -> Unit) {
    LockScaffold(
        title = stringResource(Res.string.shell_corrupted_title),
        subtitle = stringResource(Res.string.shell_corrupted_subtitle),
    ) {
        PrimaryButton(stringResource(Res.string.shell_reset_skerry), onClick = onReset, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Confirmation screen for an irreversible reset: scope choice ([ResetScope]) + type-to-confirm — the
 * reset button is enabled only when [RESET_CONFIRM_WORD] is typed. Deletion is irreversible, so the
 * barrier against an accidental click is strict.
 */
@Composable
fun DesktopResetScreen(onConfirm: (ResetScope) -> Unit, onCancel: () -> Unit) {
    var scope by remember { mutableStateOf(ResetScope.SecretsOnly) }
    var confirmText by remember { mutableStateOf("") }
    val canConfirm = confirmText.trim() == RESET_CONFIRM_WORD
    LockScaffold(
        title = stringResource(Res.string.shell_reset_skerry),
        subtitle = stringResource(Res.string.shell_reset_subtitle),
    ) {
        ResetScopeRow(
            selected = scope == ResetScope.SecretsOnly,
            title = stringResource(Res.string.shell_reset_scope_secrets_title),
            subtitle = stringResource(Res.string.shell_reset_scope_secrets_subtitle),
            onSelect = { scope = ResetScope.SecretsOnly },
        )
        ResetScopeRow(
            selected = scope == ResetScope.Everything,
            title = stringResource(Res.string.shell_reset_scope_all_title),
            subtitle = stringResource(Res.string.shell_reset_scope_all_subtitle),
            onSelect = { scope = ResetScope.Everything },
        )
        LockTextField(confirmText, { confirmText = it }, stringResource(Res.string.shell_reset_confirm_placeholder, RESET_CONFIRM_WORD), ImeAction.Done) {
            if (canConfirm) onConfirm(scope)
        }
        PrimaryButton(
            stringResource(Res.string.shell_reset_permanently),
            onClick = { if (canConfirm) onConfirm(scope) },
            modifier = Modifier.fillMaxWidth(),
            bg = if (canConfirm) D.storm else Color(0x14FFFFFF),
            fg = if (canConfirm) Color(0xFF0A1A26) else D.faint,
            enabled = canConfirm,
        )
        Txt(
            stringResource(Res.string.shell_cancel),
            color = D.dim,
            size = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCancel)
                .padding(vertical = 6.dp),
        )
    }
}

/** Reset scope row: radio dot + title/subtitle, clickable as a whole. */
@Composable
private fun ResetScopeRow(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, if (selected) D.cyan else D.line, RoundedCornerShape(7.dp))
            .clickable(onClick = onSelect)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, if (selected) D.cyan else D.faint, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(D.cyan))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, color = D.text, size = 13.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.dim, size = 11.5.sp, lineHeight = 15.sp)
        }
    }
}

/**
 * Live master-password creation form on first launch (`createForm` slot): two fields + a button,
 * validation (length/match) in [VaultGateController]; both buffers go here as [CharArray] and are wiped
 * there. Same "night sea" visuals as [DesktopUnlockScreen].
 *
 * If the platform wired sync ([sync] != null and [onPairingComplete] != null), an "I have a pairing
 * code" affordance appears under the form (quick pairing, variant B): it opens [PairingJoinScreen]
 * where the password is entered once — the coordinator creates the vault under it and adopts the
 * account key, after which [onPairingComplete] moves the gate to the biometrics offer / into the app.
 */
@Composable
fun DesktopCreateScreen(
    error: VaultGateError?,
    onCreate: (CharArray, CharArray) -> Unit,
    sync: SyncCoordinator? = null,
    onPairingComplete: (() -> Unit)? = null,
) {
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    // Losing the master password = irreversibly losing the vault (zero-knowledge): require explicit
    // acknowledgement before creation so "no recovery" isn't missed as fine print.
    var acknowledged by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    val strength = passwordStrength(pwd)
    val issue = masterPasswordIssue(pwd)
    val canCreate = pwd.isNotEmpty() && issue == null && confirm.isNotEmpty() && acknowledged
    val submit = { if (canCreate) onCreate(pwd.toCharArray(), confirm.toCharArray()) }

    if (joining && sync != null && onPairingComplete != null) {
        PairingJoinScreen(sync, onBack = { joining = false }, onDone = onPairingComplete)
        return
    }

    LockScaffold(
        title = stringResource(Res.string.shell_create_title),
        subtitle = stringResource(Res.string.shell_create_subtitle),
        error = error,
    ) {
        LockPasswordField(pwd, { pwd = it }, stringResource(Res.string.shell_master_password), ImeAction.Next, autoFocus = true)
        strength?.let { PasswordStrengthMeter(it) }
        issue?.let { Txt(masterPasswordHint(it), color = D.amber, size = 11.sp) }
        LockPasswordField(confirm, { confirm = it }, stringResource(Res.string.shell_repeat_password), ImeAction.Done, onSubmit = submit)
        NoRecoveryAcknowledge(acknowledged) { acknowledged = !acknowledged }
        PrimaryButton(
            stringResource(Res.string.shell_create_vault),
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            bg = if (canCreate) D.cyan else Color(0x14FFFFFF),
            fg = if (canCreate) Color(0xFF0A1A26) else D.faint,
            enabled = canCreate,
        )
        if (sync != null && onPairingComplete != null) {
            Txt(
                stringResource(Res.string.shell_pairing_link),
                color = D.cyanBright, size = 12.5.sp,
                modifier = Modifier.fillMaxWidth().clickable { joining = true },
            )
        }
    }
}

/** Master-password strength bar (4 segments) + label; color by [PasswordStrength]. */
@Composable
private fun PasswordStrengthMeter(strength: PasswordStrength) {
    val (filled, color, label) = when (strength) {
        PasswordStrength.Weak -> Triple(1, D.storm, stringResource(Res.string.shell_password_strength_weak))
        PasswordStrength.Fair -> Triple(2, D.amber, stringResource(Res.string.shell_password_strength_fair))
        PasswordStrength.Good -> Triple(3, D.cyan, stringResource(Res.string.shell_password_strength_good))
        PasswordStrength.Strong -> Triple(4, D.moss, stringResource(Res.string.shell_password_strength_strong))
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i < filled) color else D.cyan14),
                )
            }
        }
        Txt(stringResource(Res.string.shell_password_strength, label), color = color, size = 11.sp)
    }
}

/** "Password is unrecoverable" acknowledgement checkbox, gates vault creation. */
@Composable
private fun NoRecoveryAcknowledge(checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
            .toggleable(value = checked, onValueChange = { onToggle() }, role = Role.Checkbox)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) D.cyan.copy(alpha = 0.15f) else Color.Transparent)
                .border(1.dp, if (checked) D.cyan else D.faint, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Sym("check", size = 13.sp, color = D.cyan)
        }
        Txt(
            stringResource(Res.string.shell_no_recovery_ack),
            color = D.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f),
        )
    }
}

// Shared scaffold for lock screens.

/** Shared lock-screen scaffold: radial background, logo, title/subtitle, [fields], footer. */
@Composable
private fun LockScaffold(
    title: String,
    subtitle: String,
    error: VaultGateError? = null,
    fields: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(Color(0xFF122332), D.bg)))
            .clickable(enabled = false) {}
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF142634), Color(0xFF0A141B), Color(0xFF05090D)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark(size = 88.dp)
        }
        Box(Modifier.height(22.dp))
        Txt(title, color = D.text, size = 22.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
        Box(Modifier.height(6.dp))
        Txt(subtitle, color = D.dim, size = 13.sp)
        Box(Modifier.height(32.dp))
        Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields()
            if (error != null) {
                Txt(vaultGateErrorMessage(error), color = D.storm, size = 12.sp)
            }
        }
        Box(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("shield_lock", size = 14.sp, color = D.faint)
            Txt(stringResource(Res.string.shell_footer_never_leaves), color = D.faint, size = 11.sp)
        }
    }
}

/** Master-password field: lock icon + masked input; Enter (Done) calls [onSubmit]. */
@Composable
private fun LockPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSubmit: () -> Unit = {},
    autoFocus: Boolean = false,
) {
    // Autofocus on screen entry: the cursor lands in the password field without a click.
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
    // Border/padding/icon live in the field's own decorationBox, so the input area covers the entire
    // visible border — a click anywhere in it (padding, edge, icon) places the caret.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        textStyle = TextStyle(color = D.text, fontSize = 14.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = KeyboardType.Password),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Sym("lock", size = 18.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 14.sp)
                    innerTextField()
                }
            }
        },
    )
}

/** Flat text field (no masking) — for type-to-confirm on the reset screen. */
@Composable
private fun LockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSubmit: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = D.text, fontSize = 14.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 14.sp)
                    innerTextField()
                }
            }
        },
    )
}

/** Outlined square biometric (fingerprint) button. */
@Composable
private fun BiometricButton(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, D.cyan14, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym("fingerprint", size = 16.sp, color = D.dim)
    }
}
