package app.skerry.ui.vault

import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.Identity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultPresentationTest {

    private fun key(id: String) = Credential(id, "key-$id", CredentialSecret.PrivateKey("pem-$id"))
    private fun pwd(id: String) = Credential(id, "pwd-$id", CredentialSecret.Password("s"))
    private fun cert(id: String) = Credential(id, "cert-$id", CredentialSecret.Certificate("pem-$id", "cert-$id"))

    private val credentials = listOf(key("k1"), key("k2"), pwd("p1"), cert("c1"))

    @Test
    fun `classifies credential by secret type`() {
        assertEquals(VaultCategoryKind.SSH_KEYS, VaultPresentation.categoryOf(key("k1")))
        assertEquals(VaultCategoryKind.PASSWORDS, VaultPresentation.categoryOf(pwd("p1")))
        assertEquals(VaultCategoryKind.CERTIFICATES, VaultPresentation.categoryOf(cert("c1")))
    }

    @Test
    fun `filters credentials into keychain categories`() {
        assertEquals(listOf("k1", "k2"), VaultPresentation.credentialsIn(VaultCategoryKind.SSH_KEYS, credentials).map { it.id })
        assertEquals(listOf("p1"), VaultPresentation.credentialsIn(VaultCategoryKind.PASSWORDS, credentials).map { it.id })
        assertEquals(listOf("c1"), VaultPresentation.credentialsIn(VaultCategoryKind.CERTIFICATES, credentials).map { it.id })
    }

    @Test
    fun `identities category never holds keychain secrets`() {
        assertTrue(VaultPresentation.credentialsIn(VaultCategoryKind.IDENTITIES, credentials).isEmpty())
    }

    @Test
    fun `counts live records per category`() {
        val accounts = listOf(
            Identity("a1", "acc-1", "root", "k1"),
            Identity("a2", "acc-2", "deploy", "k1"),
            Identity("a3", "acc-3", "ci", "p1"),
        )
        assertEquals(2, VaultPresentation.count(VaultCategoryKind.SSH_KEYS, credentials, accounts))
        assertEquals(1, VaultPresentation.count(VaultCategoryKind.PASSWORDS, credentials, accounts))
        assertEquals(1, VaultPresentation.count(VaultCategoryKind.CERTIFICATES, credentials, accounts))
        // Identities считает учётки, а не keychain-секреты.
        assertEquals(3, VaultPresentation.count(VaultCategoryKind.IDENTITIES, credentials, accounts))
    }

    @Test
    fun `accountsUsing returns only accounts referencing the credential`() {
        val accounts = listOf(
            Identity("a1", "acc-1", "root", "k1"),
            Identity("a2", "acc-2", "deploy", "k1"),
            Identity("a3", "acc-3", "ci", "k2"),
        )
        assertEquals(listOf("a1", "a2"), VaultPresentation.accountsUsing("k1", accounts).map { it.id })
        assertEquals(listOf("a3"), VaultPresentation.accountsUsing("k2", accounts).map { it.id })
        assertTrue(VaultPresentation.accountsUsing("nope", accounts).isEmpty())
    }

    @Test
    fun `hostsUsing returns only hosts referencing the account`() {
        val hosts = listOf(
            Host("h1", "web-01", "10.0.0.1", username = "root", identityId = "a1"),
            Host("h2", "web-02", "10.0.0.2", username = "root", identityId = "a1"),
            Host("h3", "db", "10.0.0.3", username = "root", identityId = "a2"),
            Host("h4", "misc", "10.0.0.4", username = "root", identityId = null),
        )
        assertEquals(listOf("web-01", "web-02"), VaultPresentation.hostsUsing("a1", hosts).map { it.label })
        assertEquals(listOf("db"), VaultPresentation.hostsUsing("a2", hosts).map { it.label })
        assertTrue(VaultPresentation.hostsUsing("nope", hosts).isEmpty())
    }
}
