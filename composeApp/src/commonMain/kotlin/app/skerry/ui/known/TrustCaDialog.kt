package app.skerry.ui.known

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_ca_add
import app.skerry.ui.generated.resources.lib_ca_dialog_title
import app.skerry.ui.generated.resources.lib_ca_error_duplicate
import app.skerry.ui.generated.resources.lib_ca_error_hosts_invalid
import app.skerry.ui.generated.resources.lib_ca_error_hosts_missing
import app.skerry.ui.generated.resources.lib_ca_error_key
import app.skerry.ui.generated.resources.lib_ca_error_not_stored
import app.skerry.ui.generated.resources.lib_ca_field_hosts
import app.skerry.ui.generated.resources.lib_ca_field_hosts_hint
import app.skerry.ui.generated.resources.lib_ca_field_key
import app.skerry.ui.generated.resources.lib_ca_field_key_hint
import app.skerry.ui.generated.resources.lib_ca_field_label
import app.skerry.ui.generated.resources.lib_ca_field_label_hint
import app.skerry.ui.generated.resources.lib_ca_section_sub
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vault.DialogButtons
import app.skerry.ui.vault.DialogField
import app.skerry.ui.vault.VaultDialogScaffold
import org.jetbrains.compose.resources.stringResource

/**
 * Trust a certificate authority: the CA's public key, the hosts it covers, and an optional label.
 * Shared by desktop and mobile, on the vault dialog scaffold.
 *
 * The key field is multi-line (a pasted key is one long blob) and accepts a whole `@cert-authority`
 * line, whose pattern prefills nothing but is used when the HOSTS field is left empty — pasting a
 * line straight out of `known_hosts` is the fastest path and shouldn't require retyping the pattern.
 * A failed [TrustedCaController.add] keeps the dialog open with the reason under the fields; nothing
 * else can report it, since the entry simply wouldn't appear in the list.
 */
@Composable
fun TrustCaDialog(controller: TrustedCaController, onDismiss: () -> Unit) {
    var keyText by remember { mutableStateOf("") }
    var hosts by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<AddCaResult?>(null) }

    VaultDialogScaffold(
        title = stringResource(Res.string.lib_ca_dialog_title),
        subtitle = stringResource(Res.string.lib_ca_section_sub),
        onDismiss = onDismiss,
    ) {
        Column {
            DialogField(
                label = stringResource(Res.string.lib_ca_field_key),
                value = keyText,
                onValueChange = { keyText = it; error = null },
                placeholder = stringResource(Res.string.lib_ca_field_key_hint),
                singleLine = false,
                // A public key is not a secret, but the IME dictionary has no business learning it.
                keyboardType = KeyboardType.Password,
            )
            Column(Modifier.padding(top = 12.dp)) {
                DialogField(
                    label = stringResource(Res.string.lib_ca_field_hosts),
                    value = hosts,
                    onValueChange = { hosts = it; error = null },
                    placeholder = stringResource(Res.string.lib_ca_field_hosts_hint),
                )
            }
            Column(Modifier.padding(top = 12.dp)) {
                DialogField(
                    label = stringResource(Res.string.lib_ca_field_label),
                    value = label,
                    onValueChange = { label = it },
                    placeholder = stringResource(Res.string.lib_ca_field_label_hint),
                )
            }
            errorText(error)?.let {
                Txt(it, color = Skerry.colors.sunset, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
            }
            DialogButtons(
                confirmLabel = stringResource(Res.string.lib_ca_add),
                confirmEnabled = keyText.isNotBlank(),
                onDismiss = onDismiss,
                onConfirm = {
                    when (val result = controller.add(keyText, hosts, label)) {
                        is AddCaResult.Added -> onDismiss()
                        else -> error = result
                    }
                },
            )
        }
    }
}

@Composable
private fun errorText(result: AddCaResult?): String? = when (result) {
    null, is AddCaResult.Added -> null
    AddCaResult.InvalidKey -> stringResource(Res.string.lib_ca_error_key)
    AddCaResult.MissingPattern -> stringResource(Res.string.lib_ca_error_hosts_missing)
    AddCaResult.InvalidPattern -> stringResource(Res.string.lib_ca_error_hosts_invalid)
    AddCaResult.Duplicate -> stringResource(Res.string.lib_ca_error_duplicate)
    AddCaResult.NotStored -> stringResource(Res.string.lib_ca_error_not_stored)
}
