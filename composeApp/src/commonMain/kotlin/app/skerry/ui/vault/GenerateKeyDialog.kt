package app.skerry.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.securitykey.SecurityKeyAlgorithm
import app.skerry.shared.securitykey.SecurityKeyException
import app.skerry.shared.securitykey.SecurityKeyFailure
import app.skerry.shared.securitykey.SecurityKeyOperation
import app.skerry.shared.securitykey.SecurityKeyRequestContext
import app.skerry.shared.vault.GeneratedSshKey
import app.skerry.shared.vault.SshKeyType
import app.skerry.ui.app.LocalSecurityKeyPrompts
import app.skerry.ui.app.LocalSecurityKeyService
import app.skerry.ui.app.LocalSecurityKeysEnabled
import app.skerry.ui.design.Chip
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.security_key_application
import app.skerry.ui.generated.resources.security_key_application_invalid
import app.skerry.ui.generated.resources.security_key_hardware
import app.skerry.ui.generated.resources.security_key_resident
import app.skerry.ui.generated.resources.security_key_username
import app.skerry.ui.generated.resources.security_key_uv
import app.skerry.ui.generated.resources.vault_dialog_generate_subtitle
import app.skerry.ui.generated.resources.vault_dialog_generate_title
import app.skerry.ui.generated.resources.vault_field_name
import app.skerry.ui.generated.resources.vault_generate
import app.skerry.ui.generated.resources.vault_placeholder_name_key
import app.skerry.ui.generated.resources.vault_placeholder_optional
import app.skerry.ui.securitykey.SecurityKeyGenerationForm
import app.skerry.ui.securitykey.securityKeyFailureText
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, type: SshKeyType) -> Unit,
    onHardwareCreate: (name: String, key: GeneratedSshKey) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SshKeyType.ED25519) }
    var hardware by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(SecurityKeyGenerationForm()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<SecurityKeyFailure?>(null) }
    val enabled = LocalSecurityKeysEnabled.current
    val currentlyEnabled by rememberUpdatedState(enabled)
    val service = LocalSecurityKeyService.current
    val prompts = LocalSecurityKeyPrompts.current
    val scope = rememberCoroutineScope()
    var operation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(enabled) { if (!enabled) { operation?.cancel(); prompts?.cancelPending(); hardware = false } }
    DisposableEffect(Unit) { onDispose { operation?.cancel() } }
    val errorText = error?.let { stringResource(securityKeyFailureText(it)) }.orEmpty()
    VaultDialogScaffold(stringResource(Res.string.vault_dialog_generate_title), stringResource(Res.string.vault_dialog_generate_subtitle), onDismiss) {
        StatusAnnouncer(errorText)
        DialogField(stringResource(Res.string.vault_field_name), name, { if (!busy) name = it.take(256) }, placeholder = stringResource(Res.string.vault_placeholder_name_key))
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SshKeyType.entries.forEach { option ->
                Chip(option.label, active = !hardware && option == type, modifier = Modifier.clickable(enabled = !busy) { hardware = false; type = option })
            }
            if (enabled && service != null && prompts != null) {
                Chip(stringResource(Res.string.security_key_hardware), active = hardware, modifier = Modifier.clickable(enabled = !busy) { hardware = true })
            }
            if (hardware) {
                SecurityKeyAlgorithm.entries.forEach { option ->
                    Chip(option.wireName, active = option == form.algorithm, modifier = Modifier.clickable(enabled = !busy) { form = form.copy(algorithm = option) })
                }
                DialogField(stringResource(Res.string.security_key_application), form.application, { if (!busy) form = form.copy(application = it) }, placeholder = "ssh:")
                DialogField(stringResource(Res.string.security_key_username), form.username, { if (!busy) form = form.copy(username = it) }, placeholder = stringResource(Res.string.vault_placeholder_optional))
                Chip(stringResource(Res.string.security_key_resident), active = form.resident, modifier = Modifier.toggleable(value = form.resident, enabled = !busy, role = Role.Checkbox) { form = form.copy(resident = it) })
                Chip(stringResource(Res.string.security_key_uv), active = form.userVerification, modifier = Modifier.toggleable(value = form.userVerification, enabled = !busy, role = Role.Checkbox) { form = form.copy(userVerification = it) })
                if (!form.valid) Txt(stringResource(Res.string.security_key_application_invalid), color = Skerry.colors.sunset, size = 11.sp)
            }
        }
        if (errorText.isNotEmpty()) Txt(errorText, color = Skerry.colors.sunset, size = 11.sp)
        DialogButtons(
            confirmLabel = stringResource(Res.string.vault_generate),
            confirmEnabled = !busy && name.isNotBlank() && name.none { it.code < 32 || it.code == 127 } && (!hardware || enabled && form.valid),
            onDismiss = { operation?.cancel(); onDismiss() },
            onConfirm = {
                val capturedName = name.trim()
                if (!hardware) onCreate(capturedName, type)
                else if (enabled && service != null && prompts != null) {
                    val options = form.options(capturedName)
                    busy = true
                    error = null
                    operation = scope.launch {
                        try {
                            val key = service.generate(options, prompts.forRequest(SecurityKeyRequestContext(application = options.application, operation = SecurityKeyOperation.Generate)))
                            currentCoroutineContext().ensureActive()
                            if (!currentlyEnabled) throw SecurityKeyException(SecurityKeyFailure.Disabled)
                            onHardwareCreate(capturedName, key)
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (failure: SecurityKeyException) { error = failure.reason
                        } finally { busy = false }
                    }
                }
            },
        )
    }
}
