package app.skerry.ui.design

import app.skerry.shared.vault.CredentialSecret

/**
 * Подписи/иконки типа keychain-секрета для строки выбора в пикерах аутентификации. Общие для
 * desktop ([NewConnectionModal]) и mobile ([MobileNewConnectionSheet]) — одна точка правки при
 * добавлении нового вида [CredentialSecret], чтобы оба пикера не разъезжались.
 */
internal fun CredentialSecret.pickerTypeLabel(): String = when (this) {
    is CredentialSecret.Password -> "Password"
    is CredentialSecret.PrivateKey -> "SSH key"
    is CredentialSecret.Certificate -> "Certificate"
}

internal fun CredentialSecret.pickerIcon(): String = when (this) {
    is CredentialSecret.Password -> "password"
    is CredentialSecret.PrivateKey -> "key"
    is CredentialSecret.Certificate -> "workspace_premium"
}
