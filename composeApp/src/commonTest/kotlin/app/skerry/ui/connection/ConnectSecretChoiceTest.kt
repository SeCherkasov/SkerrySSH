package app.skerry.ui.connection

import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which keychain entries the connect prompt offers. A team-shared host arrives with its credential
 * link stripped, so this list is the only way a member reaches a box that doesn't take passwords.
 */
class ConnectSecretChoiceTest {

    private fun cred(id: String, secret: CredentialSecret) = Credential(id = id, label = id, secret = secret)

    private val password = cred("pw", CredentialSecret.Password("s3cret"))
    private val key = cred("key", CredentialSecret.PrivateKey("-----BEGIN-----"))
    private val certificate = cred("cert", CredentialSecret.Certificate("-----BEGIN-----", "cert"))

    @Test
    fun `an ssh host is offered every kind of secret`() {
        val offered = connectableSecrets(listOf(password, key, certificate), ConnectionType.SSH)

        assertEquals(listOf("pw", "key", "cert"), offered.map { it.id })
    }

    @Test
    fun `vnc is offered passwords only`() {
        // VNC-Auth has no notion of a key: offering one would be a row that cannot work.
        val offered = connectableSecrets(listOf(password, key, certificate), ConnectionType.VNC)

        assertEquals(listOf("pw"), offered.map { it.id })
    }

    @Test
    fun `an empty keychain offers nothing`() {
        assertTrue(connectableSecrets(emptyList(), ConnectionType.SSH).isEmpty())
    }
}
