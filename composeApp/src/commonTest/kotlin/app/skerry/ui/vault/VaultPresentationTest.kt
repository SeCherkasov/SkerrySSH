package app.skerry.ui.vault

import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.design.D
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
    fun `sidebar holds the three keychain categories in order`() {
        assertEquals(
            listOf(VaultCategoryKind.SSH_KEYS, VaultCategoryKind.PASSWORDS, VaultCategoryKind.CERTIFICATES),
            VaultPresentation.sidebarCategories,
        )
    }

    @Test
    fun `counts live secrets per category`() {
        assertEquals(2, VaultPresentation.count(VaultCategoryKind.SSH_KEYS, credentials))
        assertEquals(1, VaultPresentation.count(VaultCategoryKind.PASSWORDS, credentials))
        assertEquals(1, VaultPresentation.count(VaultCategoryKind.CERTIFICATES, credentials))
    }

    @Test
    fun `secretStyle maps secret type to icon, accent color and tint`() {
        assertEquals(SecretTypeStyle("key", D.cyanBright, tinted = true), VaultPresentation.secretStyle(key("k1").secret))
        assertEquals(SecretTypeStyle("password", D.dim, tinted = false), VaultPresentation.secretStyle(pwd("p1").secret))
        assertEquals(SecretTypeStyle("workspace_premium", D.moss, tinted = true), VaultPresentation.secretStyle(cert("c1").secret))
    }

    // Прим.: usedByLabel стала @Composable (текст «used by N host(s)» — строковый ресурс с формой
    // числа), поэтому её строковый юнит-тест снят — подпись резолвится в composition.

    @Test
    fun `hostsUsing returns only hosts referencing the credential`() {
        val hosts = listOf(
            Host("h1", "web-01", "10.0.0.1", username = "root", credentialId = "k1"),
            Host("h2", "web-02", "10.0.0.2", username = "root", credentialId = "k1"),
            Host("h3", "db", "10.0.0.3", username = "root", credentialId = "k2"),
            Host("h4", "misc", "10.0.0.4", username = "root", credentialId = null),
        )
        assertEquals(listOf("web-01", "web-02"), VaultPresentation.hostsUsing("k1", hosts).map { it.label })
        assertEquals(listOf("db"), VaultPresentation.hostsUsing("k2", hosts).map { it.label })
        assertTrue(VaultPresentation.hostsUsing("nope", hosts).isEmpty())
    }
}
