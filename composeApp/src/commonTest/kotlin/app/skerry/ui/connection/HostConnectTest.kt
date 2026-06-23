package app.skerry.ui.connection

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Чистые хелперы проводки хоста к сессии (host → адрес/ярлык, keychain-секрет → способ
 * аутентификации), общие для desktop-дизайн-слоя и мобильного UI. Color/Compose тут не участвуют —
 * только модели. Хост ссылается на keychain-секрет по `credentialId`: вызывающий резолвит его в
 * [Credential] и зовёт [toSshAuth].
 */
class HostConnectTest {

    private fun host(
        address: String = "10.0.0.1",
        port: Int = 22,
        username: String = "root",
    ) = Host(id = "h1", label = "Prod", address = address, port = port, username = username)

    @Test
    fun target_maps_address_port_and_user() {
        assertEquals(
            SshTarget(host = "example.com", port = 2222, username = "deploy"),
            host(address = "example.com", port = 2222, username = "deploy").toTarget(),
        )
    }

    @Test
    fun subtitle_is_user_at_address_colon_port() {
        assertEquals("deploy@example.com:2222", host(address = "example.com", port = 2222, username = "deploy").connectionSubtitle())
    }

    @Test
    fun password_credential_maps_to_password_auth() {
        val c = Credential("c1", "pw", CredentialSecret.Password("s3cr3t"))
        assertEquals(SshAuth.Password("s3cr3t"), c.toSshAuth())
    }

    @Test
    fun private_key_credential_maps_with_passphrase() {
        val c = Credential("c2", "key", CredentialSecret.PrivateKey("PEMDATA", "phrase"))
        assertEquals(SshAuth.PublicKey("PEMDATA", "phrase"), c.toSshAuth())
    }

    @Test
    fun private_key_credential_without_passphrase_keeps_null() {
        val c = Credential("c3", "key", CredentialSecret.PrivateKey("PEMDATA", null))
        assertEquals(SshAuth.PublicKey("PEMDATA", null), c.toSshAuth())
    }

    @Test
    fun certificate_credential_maps_to_certificate_auth() {
        val c = Credential("c4", "cert", CredentialSecret.Certificate("PEMDATA", "CERTDATA", "phrase"))
        assertEquals(SshAuth.Certificate("PEMDATA", "CERTDATA", "phrase"), c.toSshAuth())
    }

    @Test
    fun certificate_credential_without_passphrase_keeps_null() {
        val c = Credential("c5", "cert", CredentialSecret.Certificate("PEMDATA", "CERTDATA", null))
        assertEquals(SshAuth.Certificate("PEMDATA", "CERTDATA", null), c.toSshAuth())
    }

    @Test
    fun short_cipher_drops_vendor_suffix() {
        assertEquals("chacha20-poly1305", shortCipher("chacha20-poly1305@openssh.com"))
        assertEquals("aes256-gcm", shortCipher("aes256-gcm@openssh.com"))
    }

    @Test
    fun short_cipher_keeps_plain_name() {
        assertEquals("aes256-ctr", shortCipher("aes256-ctr"))
    }

    @Test
    fun short_cipher_trims_and_handles_blank_or_null() {
        assertEquals("chacha20-poly1305", shortCipher("  chacha20-poly1305@openssh.com  "))
        assertEquals(null, shortCipher(null))
        assertEquals(null, shortCipher("   "))
        assertEquals(null, shortCipher("@"))
    }
}
