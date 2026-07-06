package app.skerry.ui.host

import androidx.compose.runtime.Composable
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vtail_picker_type_certificate
import app.skerry.ui.generated.resources.vtail_picker_type_password
import app.skerry.ui.generated.resources.vtail_picker_type_ssh_key
import app.skerry.ui.vault.VaultPresentation
import org.jetbrains.compose.resources.stringResource

/**
 * Labels/icons for keychain secret types in auth picker rows. Shared between desktop
 * ([NewConnectionModal]) and mobile ([MobileNewConnectionSheet]) so both pickers stay in sync when
 * a new [CredentialSecret] kind is added.
 */
@Composable
internal fun CredentialSecret.pickerTypeLabel(): String = when (this) {
    is CredentialSecret.Password -> stringResource(Res.string.vtail_picker_type_password)
    is CredentialSecret.PrivateKey -> stringResource(Res.string.vtail_picker_type_ssh_key)
    is CredentialSecret.Certificate -> stringResource(Res.string.vtail_picker_type_certificate)
}

/** Secret type icon, from the shared [VaultPresentation.secretStyle] so pickers stay in sync with Vault. */
internal fun CredentialSecret.pickerIcon(): String = VaultPresentation.secretStyle(this).icon
