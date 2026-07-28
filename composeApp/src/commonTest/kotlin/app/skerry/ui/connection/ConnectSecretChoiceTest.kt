package app.skerry.ui.connection

import app.skerry.shared.host.Host
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

    private fun host(type: ConnectionType) =
        Host(id = "h1", label = "box", address = "10.0.0.1", username = "root", connectionType = type)

    @Test
    fun `a shared ssh host is offered every kind of secret`() {
        val offered = connectableSecrets(listOf(password, key, certificate), host(ConnectionType.SSH), ownCatalog = emptyList())

        assertEquals(listOf("pw", "key", "cert"), offered.map { it.id })
    }

    @Test
    fun `a shared vnc host is offered passwords only`() {
        // VNC-Auth has no notion of a key: offering one would be a row that cannot work.
        val offered = connectableSecrets(listOf(password, key, certificate), host(ConnectionType.VNC), ownCatalog = emptyList())

        assertEquals(listOf("pw"), offered.map { it.id })
    }

    @Test
    fun `a shared rdp host is offered passwords only`() {
        // RDP logs on with a password; a key picked from the list produces no session at all,
        // because there is nothing to turn it into.
        val offered = connectableSecrets(listOf(password, key, certificate), host(ConnectionType.RDP), ownCatalog = emptyList())

        assertEquals(listOf("pw"), offered.map { it.id })
    }

    @Test
    fun `our own profile is offered nothing — asking is what it was set to do`() {
        // The profile says "ask every time"; the whole keychain listed under the field is noise,
        // and binding a secret belongs in the edit form.
        val own = host(ConnectionType.RDP)

        assertTrue(connectableSecrets(listOf(password, key), own, ownCatalog = listOf(own)).isEmpty())
    }

    @Test
    fun `an empty keychain offers nothing`() {
        assertTrue(connectableSecrets(emptyList(), host(ConnectionType.SSH), ownCatalog = emptyList()).isEmpty())
    }
}
