package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.BrandMark
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_corrupted_subtitle
import app.skerry.ui.generated.resources.shell_corrupted_title
import app.skerry.ui.generated.resources.shell_create_subtitle
import app.skerry.ui.generated.resources.shell_create_title
import app.skerry.ui.generated.resources.shell_create_vault
import app.skerry.ui.generated.resources.shell_footer_never_leaves_short
import app.skerry.ui.generated.resources.shell_forgot_password
import app.skerry.ui.generated.resources.shell_lock_title
import app.skerry.ui.generated.resources.shell_master_password
import app.skerry.ui.generated.resources.shell_not_now
import app.skerry.ui.generated.resources.shell_pairing_link
import app.skerry.ui.generated.resources.shell_quick_unlock_subtitle
import app.skerry.ui.generated.resources.shell_quick_unlock_title
import app.skerry.ui.generated.resources.shell_repeat_password
import app.skerry.ui.generated.resources.shell_reset_confirm_placeholder
import app.skerry.ui.generated.resources.shell_reset_permanently
import app.skerry.ui.generated.resources.shell_reset_scope_all_subtitle
import app.skerry.ui.generated.resources.shell_reset_scope_all_title
import app.skerry.ui.generated.resources.shell_reset_scope_secrets_subtitle
import app.skerry.ui.generated.resources.shell_reset_scope_secrets_title
import app.skerry.ui.generated.resources.shell_reset_skerry
import app.skerry.ui.generated.resources.shell_reset_subtitle
import app.skerry.ui.generated.resources.shell_unlock
import app.skerry.ui.generated.resources.shell_unlock_subtitle_mobile
import app.skerry.ui.generated.resources.shell_use_biometrics
import app.skerry.ui.secure.SecureScreen
import app.skerry.ui.sync.PairingJoinScreen
import app.skerry.ui.sync.SyncCoordinator
import app.skerry.ui.vault.RESET_CONFIRM_WORD
import app.skerry.ui.vault.ResetScope
import app.skerry.ui.vault.VaultGateError
import app.skerry.ui.vault.vaultGateErrorMessage
import org.jetbrains.compose.resources.stringResource

// Lock screens (mobile visual).

/**
 * Live unlock form (master-password mode): logo, title, password field, full-width Unlock
 * button, biometric row and footer. PIN mode is deferred (no passcode backend). Password goes
 * to [onUnlock] as a [CharArray] and is wiped by the controller; the biometric row is shown
 * only when [canUseBiometric].
 */
@Composable
fun MobileUnlockScreen(
    error: VaultGateError?,
    canUseBiometric: Boolean,
    onUnlock: (CharArray) -> Unit,
    onBiometric: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    val submit = { if (pwd.isNotEmpty()) onUnlock(pwd.toCharArray()) }
    // Blocks screenshots/Recent Apps preview of the master password field (Android; no-op on desktop).
    SecureScreen()
    MobileLockScaffold(title = stringResource(Res.string.shell_lock_title), subtitle = stringResource(Res.string.shell_unlock_subtitle_mobile), error = error) {
        MobileLockField(pwd, { pwd = it }, stringResource(Res.string.shell_master_password), ImeAction.Done, onSubmit = submit)
        Spacer(Modifier.height(14.dp))
        MobileWideButton(stringResource(Res.string.shell_unlock), onClick = submit)
        if (canUseBiometric) {
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onBiometric),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sym("fingerprint", size = 24.sp, color = D.cyanBright)
                Txt(stringResource(Res.string.shell_use_biometrics), color = D.dim, size = 14.sp)
            }
        }
        // Forgot-password dead end is resolved only by reset (zero-knowledge); leads to [MobileResetScreen].
        Spacer(Modifier.height(18.dp))
        Txt(
            stringResource(Res.string.shell_forgot_password),
            color = D.faint,
            size = 13.sp,
            modifier = Modifier.clickable(onClick = onForgotPassword),
        )
    }
}

/**
 * Live master-password creation form on first run (mobile visual): two fields + full-width button.
 * Validation (length/match) lives in `VaultGateController`; both buffers go out as [CharArray]
 * and are wiped there.
 *
 * If sync is wired up ([sync] != null and [onPairingComplete] != null), an "I have a pairing code"
 * affordance appears below the form (quick pairing, variant B): it opens [PairingJoinScreen], where
 * the password is entered ONCE — the coordinator creates the vault under it and accepts the account
 * key, then [onPairingComplete] moves the gate to the biometric offer (no second password entry).
 */
@Composable
fun MobileCreateScreen(
    error: VaultGateError?,
    onCreate: (CharArray, CharArray) -> Unit,
    sync: SyncCoordinator? = null,
    onPairingComplete: (() -> Unit)? = null,
) {
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    val submit = { if (pwd.isNotEmpty() && confirm.isNotEmpty()) onCreate(pwd.toCharArray(), confirm.toCharArray()) }
    // Blocks screenshots/Recent Apps preview of the master password fields (Android; no-op on desktop).
    SecureScreen()

    if (joining && sync != null && onPairingComplete != null) {
        PairingJoinScreen(sync, onBack = { joining = false }, onDone = onPairingComplete)
        return
    }

    MobileLockScaffold(
        title = stringResource(Res.string.shell_create_title),
        subtitle = stringResource(Res.string.shell_create_subtitle),
        error = error,
    ) {
        MobileLockField(pwd, { pwd = it }, stringResource(Res.string.shell_master_password), ImeAction.Next)
        Spacer(Modifier.height(12.dp))
        MobileLockField(confirm, { confirm = it }, stringResource(Res.string.shell_repeat_password), ImeAction.Done, onSubmit = submit)
        Spacer(Modifier.height(14.dp))
        MobileWideButton(stringResource(Res.string.shell_create_vault), onClick = submit)
        if (sync != null && onPairingComplete != null) {
            Spacer(Modifier.height(18.dp))
            Txt(
                stringResource(Res.string.shell_pairing_link),
                color = D.cyanBright, size = 13.sp,
                modifier = Modifier.clickable { joining = true },
            )
        }
    }
}

/**
 * One-time offer to enable biometrics right after vault creation (mobile visual). The vault is
 * already open, so the step is optional: "Use biometrics" launches the system prompt, "Not now"
 * proceeds into the app. Buttons are disabled while the prompt is in flight ([inFlight]).
 * Biometrics can always be configured later in More.
 */
@Composable
fun MobileBiometricOfferScreen(inFlight: Boolean, onEnable: () -> Unit, onSkip: () -> Unit) {
    MobileLockScaffold(
        title = stringResource(Res.string.shell_quick_unlock_title),
        subtitle = stringResource(Res.string.shell_quick_unlock_subtitle),
        error = null,
    ) {
        MobileWideButton(stringResource(Res.string.shell_use_biometrics), onClick = { if (!inFlight) onEnable() }, enabled = !inFlight)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().clickable(enabled = !inFlight, onClick = onSkip).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt(stringResource(Res.string.shell_not_now), color = D.dim, size = 14.sp, weight = FontWeight.Medium)
        }
    }
}

/**
 * Corrupted vault file screen (mobile visual, parity with [DesktopCorruptedScreen]). The file
 * can't be read, so no password entry is possible; the only action is to go to reset confirmation
 * ([onReset]).
 */
@Composable
fun MobileCorruptedScreen(onReset: () -> Unit) {
    MobileLockScaffold(
        title = stringResource(Res.string.shell_corrupted_title),
        subtitle = stringResource(Res.string.shell_corrupted_subtitle),
        error = null,
    ) {
        MobileWideButton(stringResource(Res.string.shell_reset_skerry), onClick = onReset)
    }
}

/**
 * Irreversible-reset confirmation screen (mobile visual, parity with [DesktopResetScreen]): scope
 * selection ([ResetScope]) plus type-to-confirm — the danger button is enabled only once
 * [RESET_CONFIRM_WORD] is typed. Deletion is irreversible (zero-knowledge), hence the strict
 * accidental-tap barrier.
 */
@Composable
fun MobileResetScreen(onConfirm: (ResetScope) -> Unit, onCancel: () -> Unit) {
    var scope by remember { mutableStateOf(ResetScope.SecretsOnly) }
    var confirmText by remember { mutableStateOf("") }
    val canConfirm = confirmText.trim() == RESET_CONFIRM_WORD
    MobileLockScaffold(
        title = stringResource(Res.string.shell_reset_skerry),
        subtitle = stringResource(Res.string.shell_reset_subtitle),
        error = null,
    ) {
        MobileResetScopeRow(
            selected = scope == ResetScope.SecretsOnly,
            title = stringResource(Res.string.shell_reset_scope_secrets_title),
            subtitle = stringResource(Res.string.shell_reset_scope_secrets_subtitle),
            onSelect = { scope = ResetScope.SecretsOnly },
        )
        Spacer(Modifier.height(10.dp))
        MobileResetScopeRow(
            selected = scope == ResetScope.Everything,
            title = stringResource(Res.string.shell_reset_scope_all_title),
            subtitle = stringResource(Res.string.shell_reset_scope_all_subtitle),
            onSelect = { scope = ResetScope.Everything },
        )
        Spacer(Modifier.height(14.dp))
        MobileLockPlainField(confirmText, { confirmText = it }, stringResource(Res.string.shell_reset_confirm_placeholder, RESET_CONFIRM_WORD), ImeAction.Done) {
            if (canConfirm) onConfirm(scope)
        }
        Spacer(Modifier.height(14.dp))
        MobileWideButton(
            stringResource(Res.string.shell_reset_permanently),
            onClick = { if (canConfirm) onConfirm(scope) },
            bg = if (canConfirm) D.storm else Color(0x14FFFFFF),
            fg = if (canConfirm) D.ink else D.faint,
            enabled = canConfirm,
        )
        Spacer(Modifier.height(16.dp))
        // Only exit from this irreversible screen is a full-width tap zone (parity with desktop);
        // otherwise a mis-tap leaves the user on the danger screen with no obvious way back.
        Txt(
            stringResource(Res.string.shell_cancel),
            color = D.dim,
            size = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCancel)
                .padding(vertical = 10.dp),
        )
    }
}

/** Reset-scope selection row (mobile visual): radio dot + title/subtitle, whole row is clickable. */
@Composable
private fun MobileResetScopeRow(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, if (selected) D.cyan else D.line, RoundedCornerShape(11.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(9.dp))
                .border(1.dp, if (selected) D.cyan else D.faint, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(D.cyan))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, color = D.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = D.dim, size = 12.sp, lineHeight = 16.sp)
        }
    }
}

/** Lock-screen scaffold: radial background, 64dp logo, title, [fields], footer. */
@Composable
private fun MobileLockScaffold(
    title: String,
    subtitle: String,
    error: VaultGateError?,
    fields: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(Color(0xFF132838), Color(0xFF06121C))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 30.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF142634), Color(0xFF0A141B), Color(0xFF05090D)))),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark(size = 64.dp)
        }
        Spacer(Modifier.height(16.dp))
        Txt(title, color = D.text, size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Txt(subtitle, color = D.dim, size = 13.sp)
        Spacer(Modifier.height(26.dp))
        Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            fields()
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Txt(vaultGateErrorMessage(error), color = D.storm, size = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Sym("shield_lock", size = 14.sp, color = D.faint)
            Txt(stringResource(Res.string.shell_footer_never_leaves_short), color = D.faint, size = 11.sp)
        }
    }
}

/** Master-password field: lock icon + masked input; Enter (Done) calls [onSubmit]. */
@Composable
private fun MobileLockField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSubmit: () -> Unit = {},
) {
    // Border/icon live in decorationBox so a click anywhere in the field places the caret.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        textStyle = TextStyle(color = D.text, fontSize = 15.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = KeyboardType.Password),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan.copy(alpha = 0.16f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Sym("lock", size = 19.sp, color = D.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp)
                    inner()
                }
            }
        },
    )
}

/** Plain input field (no masking/icon) — for type-to-confirm on the reset screen. */
@Composable
private fun MobileLockPlainField(
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
        textStyle = TextStyle(color = D.text, fontSize = 15.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(D.cyan),
        // Confirm word is uppercase (RESET): disable autocorrect (otherwise the IME rewrites it to
        // "Reset" and the comparison never matches) and force uppercase capitalization.
        keyboardOptions = KeyboardOptions(
            imeAction = imeAction,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.Characters,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(D.surface2)
                    .border(1.dp, D.cyan.copy(alpha = 0.16f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = D.faint, size = 15.sp)
                    inner()
                }
            }
        },
    )
}

/**
 * Full-width primary button (default cyan background, dark text, 13dp radius) in the mobile
 * button style. [bg]/[fg]/[enabled] are overridden for the danger variant (vault reset): until
 * the confirm word is typed, the button is dimmed and not clickable.
 */
@Composable
private fun MobileWideButton(
    label: String,
    onClick: () -> Unit,
    bg: Color = D.cyan,
    fg: Color = D.ink,
    enabled: Boolean = true,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = fg, size = 16.sp, weight = FontWeight.Bold)
    }
}
