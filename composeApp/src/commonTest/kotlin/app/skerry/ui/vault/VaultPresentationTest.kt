package app.skerry.ui.vault

import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.skerry.ui.theme.nightSeaColors

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
    fun `files a file-backed secret by whether it names a certificate`() {
        val plainKey = Credential("f1", "key file", CredentialSecret.KeyFile("~/.ssh/id_ed25519"))
        val withCert = Credential("f2", "cert file", CredentialSecret.KeyFile("~/.ssh/id_ed25519", "~/.ssh/id_ed25519-cert.pub"))

        assertEquals(VaultCategoryKind.SSH_KEYS, VaultPresentation.categoryOf(plainKey))
        assertEquals(VaultCategoryKind.CERTIFICATES, VaultPresentation.categoryOf(withCert))
        assertEquals("key", VaultPresentation.secretIcon(plainKey.secret))
        assertEquals("workspace_premium", VaultPresentation.secretIcon(withCert.secret))
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
        val c = nightSeaColors()
        assertEquals(SecretTypeStyle("key", c.cyanBright, tinted = true), VaultPresentation.secretStyle(key("k1").secret, c))
        assertEquals(SecretTypeStyle("password", c.dim, tinted = false), VaultPresentation.secretStyle(pwd("p1").secret, c))
        assertEquals(SecretTypeStyle("workspace_premium", c.moss, tinted = true), VaultPresentation.secretStyle(cert("c1").secret, c))
    }

    // usedByLabel is now @Composable (plural string resource), so its string unit test was dropped;
    // the label resolves in composition.

    @Test
    fun `names the hosts a secret is bound to, and counts the rest`() {
        assertEquals("web-01", VaultPresentation.hostNames(listOf("web-01"), max = 3))
        assertEquals("web-01, web-02", VaultPresentation.hostNames(listOf("web-01", "web-02"), max = 3))
        // Beyond the cap the row would not fit anyway: the remainder becomes a tail count.
        assertEquals("a, b, c +2", VaultPresentation.hostNames(listOf("a", "b", "c", "d", "e"), max = 3))
        assertNull(VaultPresentation.hostNames(emptyList(), max = 3))
    }

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
