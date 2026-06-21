package app.skerry.ui.connection

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityAuth

/**
 * Чистые хелперы проводки сохранённого профиля хоста к живой сессии. Вынесены отдельно от UI,
 * чтобы desktop-дизайн-слой и мобильный экран собирали [SshTarget]/[SshAuth] и подписи одинаково
 * (DRY) и это покрывалось общими тестами без Compose.
 */

/** Профиль хоста → адрес для подключения ([SshTarget]). */
fun Host.toTarget(): SshTarget = SshTarget(host = address, port = port, username = username)

/** Строка `user@addr:port` — подпись вкладки/заголовка сессии. */
fun Host.connectionSubtitle(): String = "$username@$address:$port"

/**
 * Секрет identity из vault → способ аутентификации SSH. Пароль и приватный ключ (с опц. passphrase)
 * разворачиваются один в один; ветки совпадают с моделью [IdentityAuth].
 */
fun Identity.toSshAuth(): SshAuth = when (val a = auth) {
    is IdentityAuth.Password -> SshAuth.Password(a.password)
    is IdentityAuth.PrivateKey -> SshAuth.PublicKey(a.privateKeyPem, a.passphrase)
}
