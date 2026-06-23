package app.skerry.ui.vault

import app.skerry.shared.host.Host
import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultPresentationTest {

    private fun key(id: String) = Identity(id, "key-$id", IdentityAuth.PrivateKey("pem-$id"))
    private fun pwd(id: String) = Identity(id, "pwd-$id", IdentityAuth.Password("s"))
    private fun cert(id: String) = Identity(id, "cert-$id", IdentityAuth.Certificate("pem-$id", "cert-$id"))

    private val identities = listOf(key("k1"), key("k2"), pwd("p1"), cert("c1"))

    @Test
    fun `classifies identity by auth type`() {
        assertEquals(VaultCategoryKind.SSH_KEYS, VaultPresentation.categoryOf(key("k1")))
        assertEquals(VaultCategoryKind.PASSWORDS, VaultPresentation.categoryOf(pwd("p1")))
        assertEquals(VaultCategoryKind.CERTIFICATES, VaultPresentation.categoryOf(cert("c1")))
    }

    @Test
    fun `filters identities into backed categories`() {
        assertEquals(listOf("k1", "k2"), VaultPresentation.identitiesIn(VaultCategoryKind.SSH_KEYS, identities).map { it.id })
        assertEquals(listOf("p1"), VaultPresentation.identitiesIn(VaultCategoryKind.PASSWORDS, identities).map { it.id })
        assertEquals(listOf("c1"), VaultPresentation.identitiesIn(VaultCategoryKind.CERTIFICATES, identities).map { it.id })
    }

    @Test
    fun `placeholder categories are always empty regardless of data`() {
        assertTrue(VaultPresentation.identitiesIn(VaultCategoryKind.IDENTITIES, identities).isEmpty())
        assertEquals(0, VaultPresentation.count(VaultCategoryKind.IDENTITIES, identities))
    }

    @Test
    fun `counts live records per category`() {
        assertEquals(2, VaultPresentation.count(VaultCategoryKind.SSH_KEYS, identities))
        assertEquals(1, VaultPresentation.count(VaultCategoryKind.PASSWORDS, identities))
        assertEquals(1, VaultPresentation.count(VaultCategoryKind.CERTIFICATES, identities))
    }

    @Test
    fun `hostsUsing returns only hosts referencing the identity`() {
        val hosts = listOf(
            Host("h1", "web-01", "10.0.0.1", username = "root", identityId = "k1"),
            Host("h2", "web-02", "10.0.0.2", username = "root", identityId = "k1"),
            Host("h3", "db", "10.0.0.3", username = "root", identityId = "k2"),
            Host("h4", "misc", "10.0.0.4", username = "root", identityId = null),
        )
        assertEquals(listOf("web-01", "web-02"), VaultPresentation.hostsUsing("k1", hosts).map { it.label })
        assertEquals(listOf("db"), VaultPresentation.hostsUsing("k2", hosts).map { it.label })
        assertTrue(VaultPresentation.hostsUsing("nope", hosts).isEmpty())
    }
}
