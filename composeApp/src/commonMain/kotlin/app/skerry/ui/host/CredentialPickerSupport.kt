package app.skerry.ui.host

import androidx.compose.runtime.Composable
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vtail_picker_type_certificate
import app.skerry.ui.generated.resources.vtail_picker_type_password
import app.skerry.ui.generated.resources.vtail_picker_type_ssh_key
import org.jetbrains.compose.resources.stringResource

/**
 * Подписи/иконки типа keychain-секрета для строки выбора в пикерах аутентификации. Общие для
 * desktop ([NewConnectionModal]) и mobile ([MobileNewConnectionSheet]) — одна точка правки при
 * добавлении нового вида [CredentialSecret], чтобы оба пикера не разъезжались.
 */
@Composable
internal fun CredentialSecret.pickerTypeLabel(): String = when (this) {
    is CredentialSecret.Password -> stringResource(Res.string.vtail_picker_type_password)
    is CredentialSecret.PrivateKey -> stringResource(Res.string.vtail_picker_type_ssh_key)
    is CredentialSecret.Certificate -> stringResource(Res.string.vtail_picker_type_certificate)
}

internal fun CredentialSecret.pickerIcon(): String = when (this) {
    is CredentialSecret.Password -> "password"
    is CredentialSecret.PrivateKey -> "key"
    is CredentialSecret.Certificate -> "workspace_premium"
}
