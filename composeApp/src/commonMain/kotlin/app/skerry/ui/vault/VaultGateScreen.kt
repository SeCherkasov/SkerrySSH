package app.skerry.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.skerry.shared.vault.BiometricPrompt
import app.skerry.shared.vault.SecurityLog
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultBiometrics
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vtail_error_biometric_reset
import app.skerry.ui.generated.resources.vtail_error_corrupted
import app.skerry.ui.generated.resources.vtail_error_password_mismatch
import app.skerry.ui.generated.resources.vtail_error_password_too_short
import app.skerry.ui.generated.resources.vtail_error_wrong_password
import app.skerry.ui.generated.resources.vault_biometric_enable
import app.skerry.ui.generated.resources.vault_biometric_offer_subtitle
import app.skerry.ui.generated.resources.vault_biometric_offer_title
import app.skerry.ui.generated.resources.vault_cancel
import app.skerry.ui.generated.resources.vault_confirm_password_label
import app.skerry.ui.generated.resources.vault_corrupted_subtitle
import app.skerry.ui.generated.resources.vault_corrupted_title
import app.skerry.ui.generated.resources.vault_create_button
import app.skerry.ui.generated.resources.vault_create_subtitle
import app.skerry.ui.generated.resources.vault_create_title
import app.skerry.ui.generated.resources.vault_forgot_password
import app.skerry.ui.generated.resources.vault_master_password_label
import app.skerry.ui.generated.resources.vault_not_now
import app.skerry.ui.generated.resources.vault_reset_confirm_button
import app.skerry.ui.generated.resources.vault_reset_confirm_label
import app.skerry.ui.generated.resources.vault_reset_scope_everything_subtitle
import app.skerry.ui.generated.resources.vault_reset_scope_everything_title
import app.skerry.ui.generated.resources.vault_reset_scope_secrets_subtitle
import app.skerry.ui.generated.resources.vault_reset_scope_secrets_title
import app.skerry.ui.generated.resources.vault_reset_subtitle
import app.skerry.ui.generated.resources.vault_reset_vault
import app.skerry.ui.generated.resources.vault_unlock_biometric
import app.skerry.ui.generated.resources.vault_unlock_button
import app.skerry.ui.generated.resources.vault_unlock_subtitle
import app.skerry.ui.generated.resources.vault_unlock_title
import app.skerry.ui.generated.resources.vtail_bio_enable_cancel
import app.skerry.ui.generated.resources.vtail_bio_enable_subtitle
import app.skerry.ui.generated.resources.vtail_bio_enable_title
import app.skerry.ui.generated.resources.vtail_bio_unlock_cancel
import app.skerry.ui.generated.resources.vtail_bio_unlock_subtitle
import app.skerry.ui.generated.resources.vtail_bio_unlock_title
import app.skerry.ui.nav.PlatformBackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Idle auto-lock: inactivity longer than this threshold locks the vault (protects a left-open screen). */
private const val AUTO_LOCK_IDLE_MS = 5 * 60 * 1000L

/**
 * Whether to lock the vault when the app goes to background (`ON_STOP`) — a platform policy. Desktop:
 * always. Android: only if the device is actually locked (keyguard) — switching to a system picker/shade
 * doesn't lock the vault, else file picking would break the session. The idle timer ([AUTO_LOCK_IDLE_MS])
 * and process exit (a fresh start is always locked) cover the rest.
 */
expect fun deviceMandatesAutoLock(): Boolean

/**
 * Master-password gate: while [Vault] is locked, show the create or unlock form; after unlock render
 * [content] (the rest of the app UI). The controller lives for the composition (tied to the
 * [vault]/[biometrics] identity).
 *
 * If [biometrics] is passed, the unlock form offers biometric unlock (the prompt fires automatically
 * when biometrics is enabled). Auto-lock: the vault locks on background (lifecycle `ON_STOP`) and on
 * idle ([AUTO_LOCK_IDLE_MS], timer restarted by touches).
 *
 * Compose input fields work with [String], so the password is converted to [CharArray] only on submit
 * and wiped immediately by the controller; the field's string buffer lives until recomposition — a
 * known limitation; secure String-free input is a separate step.
 */
@Composable
fun VaultGate(
    vault: Vault,
    biometrics: VaultBiometrics? = null,
    // Local security event log (Settings → Security). Passed to the controller: it records
    // create/change password, biometrics enable/disable, biometric unlock. `null` — not logging (mock/preview).
    securityLog: SecurityLog? = null,
    // Idle auto-lock threshold (Settings → Security). From settings: changing it recomposes VaultGate and
    // restarts the idle timer. `null` — idle timer off ([AutoLockDuration.Never]); background lock remains
    // (deviceMandatesAutoLock).
    autoLockIdleMs: Long? = AUTO_LOCK_IDLE_MS,
    modifier: Modifier = Modifier,
    // External cleanup on reset (hosts/known_hosts/settings per the chosen [ResetScope]). Called after the
    // vault is erased; the platform wiring (desktop `main`) supplies the real implementation.
    onReset: (ResetScope) -> Unit = {},
    // [onPairingComplete] != null — the platform can link this device by code right on the creation screen
    // (quick pairing, variant B): the form can show an "I have a code" affordance where the coordinator
    // creates the vault under the chosen password and adopts the account key; on completion the form calls
    // [onPairingComplete], moving the gate to the biometrics offer / into the app. null — pairing from the
    // creation screen is unavailable (no sync / preview), only normal creation is shown.
    createForm: @Composable (
        error: VaultGateError?,
        onCreate: (CharArray, CharArray) -> Unit,
        onPairingComplete: (() -> Unit)?,
    ) -> Unit =
        { error, onCreate, _ ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CreateVaultForm(error, onCreate) }
        },
    unlockForm: @Composable (
        error: VaultGateError?,
        canUseBiometric: Boolean,
        onUnlock: (CharArray) -> Unit,
        onBiometric: () -> Unit,
        onForgotPassword: () -> Unit,
    ) -> Unit =
        { error, canUseBiometric, onUnlock, onBiometric, onForgotPassword ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                UnlockVaultForm(error, canUseBiometric, onUnlock, onBiometric, onForgotPassword)
            }
        },
    // Sync connect step in onboarding ([VaultGateState.OfferSync]) — shown right after vault creation,
    // before the biometrics offer. The form drives SyncCoordinator (connect/skip) itself and calls onDone
    // when the step finishes. null (default) — the step isn't shown (device/preview without sync); then
    // creation goes straight to biometrics/app.
    offerSyncForm: (@Composable (onDone: () -> Unit) -> Unit)? = null,
    // Corrupted file screen: the only action is to go to reset confirmation ([onReset]).
    corruptedForm: @Composable (onReset: () -> Unit) -> Unit =
        { onResetClick ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CorruptedVaultForm(onResetClick) }
        },
    // Reset confirmation screen: scope choice + explicit confirmation, then onConfirm/onCancel.
    resetForm: @Composable (onConfirm: (ResetScope) -> Unit, onCancel: () -> Unit) -> Unit =
        { onConfirm, onCancel ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ResetVaultForm(onConfirm, onCancel) }
        },
    // One-time offer to enable biometrics right after vault creation. onEnable triggers the prompt
    // (enables biometrics), onSkip skips; both lead into the app. inFlight disables buttons during the
    // prompt. Shown only when biometrics is available (see [VaultGateState.OfferBiometric]).
    offerBiometricForm: @Composable (
        inFlight: Boolean,
        onEnable: () -> Unit,
        onSkip: () -> Unit,
    ) -> Unit =
        { inFlight, onEnable, onSkip ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OfferBiometricForm(inFlight, onEnable, onSkip)
            }
        },
    content: @Composable (onLock: () -> Unit) -> Unit,
) {
    // onReset must not be a remember key (recreating the controller on every lambda change would lose
    // state/input). rememberUpdatedState gives the controller an always-fresh callback without making it
    // a key: otherwise the caller's inline lambda would "freeze" on the first composition.
    val currentOnReset by rememberUpdatedState(onReset)
    // Whether a sync form exists doesn't change over the screen's life — safe to fix at controller start
    // (it decides whether to show the OfferSync step).
    val offersSync = offerSyncForm != null
    // Composition scope — for the biometric prompts below and for the controller's async create/unlock
    // (Argon2id off the UI thread): the gate lives as long as the controller, so their lifecycles match.
    val scope = rememberCoroutineScope()
    val controller = remember(vault, biometrics, securityLog) {
        VaultGateController(
            vault,
            biometrics,
            onReset = { currentOnReset(it) },
            offersSyncOnboarding = offersSync,
            securityLog = securityLog,
            scope = scope,
        )
    }
    // Stable reference (not a new instance per VaultGate recomposition) — else createForm and its subtree
    // (the pairing affordance) would repaint needlessly. null when pairing from the creation screen is
    // unavailable (no sync).
    val onPairingComplete: (() -> Unit)? =
        if (offersSync) remember(controller) { { controller.completePairing() } } else null

    // Biometric prompts are resolved in composition (stringResource), then passed into coroutines.
    val enablePrompt = BiometricPrompt(
        title = stringResource(Res.string.vtail_bio_enable_title),
        cancelLabel = stringResource(Res.string.vtail_bio_enable_cancel),
        subtitle = stringResource(Res.string.vtail_bio_enable_subtitle),
    )
    val unlockPrompt = BiometricPrompt(
        title = stringResource(Res.string.vtail_bio_unlock_title),
        cancelLabel = stringResource(Res.string.vtail_bio_unlock_cancel),
        subtitle = stringResource(Res.string.vtail_bio_unlock_subtitle),
    )

    // Background auto-lock: other hands on an unlocked device must not get an open vault after minimizing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            // Don't lock during a biometric prompt: it may send ON_STOP, and locking mid-authentication
            // would lose its successful result (see biometricInFlight).
            if (event == Lifecycle.Event.ON_STOP &&
                controller.state == VaultGateState.Unlocked &&
                !controller.biometricInFlight &&
                deviceMandatesAutoLock()
            ) {
                controller.lock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Idle auto-lock: the delay restarts on activityTick change (user touch) and on [autoLockIdleMs]
    // threshold change from settings. null (AutoLockDuration.Never) — timer off.
    if (controller.state == VaultGateState.Unlocked && autoLockIdleMs != null) {
        LaunchedEffect(controller.activityTick, autoLockIdleMs) {
            delay(autoLockIdleMs)
            controller.lock()
        }
    }

    // key on state: on a screen change Compose destroys and recreates the form subtree, so the entered
    // password doesn't survive the slot-table transition (e.g. after lock()).
    key(controller.state) {
        when (controller.state) {
            VaultGateState.NeedsCreate ->
                createForm(
                    controller.error,
                    { password, confirm -> controller.create(password, confirm) },
                    // null when the platform didn't wire sync (same readiness as OfferSync): otherwise
                    // there's nothing to run claimPairing. Stable reference — see onPairingComplete above.
                    onPairingComplete,
                )

            VaultGateState.NeedsUnlock ->
                unlockForm(
                    controller.error,
                    controller.canUnlockWithBiometric(),
                    { password -> controller.unlock(password) },
                    { scope.launch { controller.unlockWithBiometric(unlockPrompt) } },
                    { controller.beginReset() },
                )

            // Sync step in onboarding: the form connects/skips sync itself and calls onDone, after which
            // the dataKey is final and biometrics can be safely offered. offerSyncForm is guaranteed
            // non-null here — else the controller wouldn't reach OfferSync (offersSyncOnboarding).
            VaultGateState.OfferSync ->
                offerSyncForm?.invoke { controller.completeSyncOnboarding() }

            VaultGateState.Corrupted -> corruptedForm { controller.beginReset() }

            VaultGateState.Resetting -> {
                // System "back" on the reset confirmation screen = "Cancel": return to unlock, not close
                // the app (else the only exit from the danger screen would be the Cancel button).
                PlatformBackHandler { controller.cancelReset() }
                resetForm({ scope -> controller.confirmReset(scope) }, { controller.cancelReset() })
            }

            // Enable/decline both lead into the app: on decline or prompt failure the vault is already
            // open, biometrics can be set up later in More. dismissBiometricOffer is called in any outcome.
            VaultGateState.OfferBiometric ->
                offerBiometricForm(
                    controller.biometricInFlight,
                    { scope.launch { controller.enableBiometric(enablePrompt); controller.dismissBiometricOffer() } },
                    { controller.dismissBiometricOffer() },
                )

            // lock() moves the gate to NeedsUnlock; key(state) tears down the content subtree, whose
            // DisposableEffect drops the live SSH session — locking closes sessions too.
            VaultGateState.Unlocked -> Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    // Observe presses on the Initial pass without consuming — children still get events,
                    // and the idle timer restarts on each touch.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) controller.touch()
                        }
                    }
                },
            ) {
                content { controller.lock() }
            }
        }
    }
}

@Composable
private fun CreateVaultForm(error: VaultGateError?, onCreate: (CharArray, CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val canSubmit = password.isNotEmpty() && confirm.isNotEmpty()

    VaultFormScaffold(
        title = stringResource(Res.string.vault_create_title),
        subtitle = stringResource(Res.string.vault_create_subtitle),
        error = error,
    ) {
        PasswordField(stringResource(Res.string.vault_master_password_label), password, ImeAction.Next) { password = it }
        PasswordField(stringResource(Res.string.vault_confirm_password_label), confirm, ImeAction.Done) { confirm = it }
        Button(
            onClick = { if (canSubmit) onCreate(password.toCharArray(), confirm.toCharArray()) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.vault_create_button))
        }
    }
}

/**
 * One-time offer to enable biometrics after vault creation (Material default; the mobile layer provides
 * its own visuals). The vault is already open — this is optional: "Enable" triggers the prompt, "Skip"
 * enters the app. Buttons disable during the prompt ([inFlight]).
 */
@Composable
private fun OfferBiometricForm(inFlight: Boolean, onEnable: () -> Unit, onSkip: () -> Unit) {
    VaultFormScaffold(
        title = stringResource(Res.string.vault_biometric_offer_title),
        subtitle = stringResource(Res.string.vault_biometric_offer_subtitle),
        error = null,
    ) {
        Button(onClick = onEnable, enabled = !inFlight, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_biometric_enable))
        }
        TextButton(onClick = onSkip, enabled = !inFlight, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_not_now))
        }
    }
}

@Composable
private fun UnlockVaultForm(
    error: VaultGateError?,
    canUseBiometric: Boolean,
    onUnlock: (CharArray) -> Unit,
    onBiometric: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val canSubmit = password.isNotEmpty()

    // If biometrics is available and enabled — trigger the prompt on entering the form (once).
    if (canUseBiometric) {
        LaunchedEffect(Unit) { onBiometric() }
    }

    VaultFormScaffold(
        title = stringResource(Res.string.vault_unlock_title),
        subtitle = stringResource(Res.string.vault_unlock_subtitle),
        error = error,
    ) {
        PasswordField(stringResource(Res.string.vault_master_password_label), password, ImeAction.Done) { password = it }
        Button(
            onClick = { if (canSubmit) onUnlock(password.toCharArray()) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.vault_unlock_button))
        }
        if (canUseBiometric) {
            OutlinedButton(onClick = onBiometric, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.vault_unlock_biometric))
            }
        }
        TextButton(onClick = onForgotPassword, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_forgot_password))
        }
    }
}

/**
 * Corrupted vault file screen (Material default). Dead end: no password entry, the only exit is an
 * irreversible reset. The button just leads to the [ResetVaultForm] confirmation screen.
 */
@Composable
private fun CorruptedVaultForm(onReset: () -> Unit) {
    VaultFormScaffold(
        title = stringResource(Res.string.vault_corrupted_title),
        subtitle = stringResource(Res.string.vault_corrupted_subtitle),
        error = null,
    ) {
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_reset_vault))
        }
    }
}

/**
 * Irreversible reset confirmation screen (Material default): scope choice ([ResetScope]) and
 * type-to-confirm — the button is enabled only when `RESET` is typed. The loss list is in the subtitle.
 */
@Composable
private fun ResetVaultForm(onConfirm: (ResetScope) -> Unit, onCancel: () -> Unit) {
    var scope by remember { mutableStateOf(ResetScope.SecretsOnly) }
    var confirmText by remember { mutableStateOf("") }
    val canConfirm = confirmText.trim() == RESET_CONFIRM_WORD

    VaultFormScaffold(
        title = stringResource(Res.string.vault_reset_vault),
        subtitle = stringResource(Res.string.vault_reset_subtitle),
        error = null,
    ) {
        ResetScopeOption(
            selected = scope == ResetScope.SecretsOnly,
            title = stringResource(Res.string.vault_reset_scope_secrets_title),
            subtitle = stringResource(Res.string.vault_reset_scope_secrets_subtitle),
            onSelect = { scope = ResetScope.SecretsOnly },
        )
        ResetScopeOption(
            selected = scope == ResetScope.Everything,
            title = stringResource(Res.string.vault_reset_scope_everything_title),
            subtitle = stringResource(Res.string.vault_reset_scope_everything_subtitle),
            onSelect = { scope = ResetScope.Everything },
        )
        OutlinedTextField(
            value = confirmText,
            onValueChange = { confirmText = it },
            label = { Text(stringResource(Res.string.vault_reset_confirm_label, RESET_CONFIRM_WORD)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (canConfirm) onConfirm(scope) },
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.vault_reset_confirm_button))
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.vault_cancel))
        }
    }
}

@Composable
private fun ResetScopeOption(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VaultFormScaffold(
    title: String,
    subtitle: String,
    error: VaultGateError?,
    fields: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 360.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fields()
        if (error != null) {
            Text(vaultGateErrorMessage(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, imeAction: ImeAction, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Localized gate error message. `internal` (not `private`) because it's reused by the design layer
 * (`ui/design`) over the same [VaultGateController]; pinned by
 * [app.skerry.ui.vault.VaultGateErrorMessageTest].
 */
@Composable
internal fun vaultGateErrorMessage(error: VaultGateError): String = when (error) {
    VaultGateError.PasswordTooShort -> stringResource(Res.string.vtail_error_password_too_short, MIN_MASTER_PASSWORD_LENGTH)
    VaultGateError.PasswordMismatch -> stringResource(Res.string.vtail_error_password_mismatch)
    VaultGateError.WrongPassword -> stringResource(Res.string.vtail_error_wrong_password)
    VaultGateError.Corrupted -> stringResource(Res.string.vtail_error_corrupted)
    VaultGateError.BiometricReset -> stringResource(Res.string.vtail_error_biometric_reset)
}
