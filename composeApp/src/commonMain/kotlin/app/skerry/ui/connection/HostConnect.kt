package app.skerry.ui.connection

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret

/**
 * Чистые хелперы проводки сохранённого профиля хоста к живой сессии. Вынесены отдельно от UI,
 * чтобы desktop-дизайн-слой и мобильный экран собирали [SshTarget]/[SshAuth] и подписи одинаково
 * (DRY) и это покрывалось общими тестами без Compose.
 */

/** Профиль хоста → адрес для подключения ([SshTarget]); [Host.connectionType] выбирает транспорт. */
fun Host.toTarget(): SshTarget =
    SshTarget(host = address, port = port, username = username, connectionType = connectionType)

/** Строка `user@addr:port` — подпись вкладки/заголовка сессии. */
fun Host.connectionSubtitle(): String = "$username@$address:$port"

/**
 * Keychain-секрет из vault → способ аутентификации SSH. Пароль/ключ/сертификат разворачиваются
 * один в один; ветки совпадают с моделью [CredentialSecret]. Хост ссылается на секрет по
 * `credentialId` — вызывающий резолвит его в [Credential] и зовёт это.
 */
fun Credential.toSshAuth(): SshAuth = when (val s = secret) {
    is CredentialSecret.Password -> SshAuth.Password(s.password)
    is CredentialSecret.PrivateKey -> SshAuth.PublicKey(s.privateKeyPem, s.passphrase)
    is CredentialSecret.Certificate -> SshAuth.Certificate(s.privateKeyPem, s.certificate, s.passphrase)
}

/**
 * Имя шифра для тесной info-панели: отбрасывает вендорный суффикс `@…` (`chacha20-poly1305@openssh.com`
 * → `chacha20-poly1305`), чтобы строка влезала. Пустую/`null`-строку возвращает как `null` (нечего
 * показывать). Сам алгоритм в имени не меняется — суффикс лишь маркер вендора OpenSSH.
 */
fun shortCipher(cipher: String?): String? =
    cipher?.trim()?.substringBefore('@')?.takeIf { it.isNotEmpty() }
