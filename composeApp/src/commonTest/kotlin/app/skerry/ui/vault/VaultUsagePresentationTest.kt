package app.skerry.ui.vault

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure presentation over [CredentialUsage]: what the detail panel states as fact — how many
 * copies of a secret fall inside the audit window. Read off real state, so it has to be honest at the
 * edges (a timestamp the platform clock can't parse must not be counted as a copy).
 */
class VaultUsagePresentationTest {

    // Stub "days ago" resolver: the tests speak in ISO strings shaped "d<N>" meaning N days back.
    private val daysAgo: (String) -> Int? = { iso -> iso.removePrefix("d").toIntOrNull() }

    @Test
    fun `counts only copies inside the window`() {
        val usage = CredentialUsage("c1", copiedAt = listOf("d0", "d3", "d29", "d31"))
        assertEquals(3, VaultPresentation.copiesWithin(usage, days = 30, daysAgo = daysAgo))
    }

    @Test
    fun `unparsable copy timestamps are not counted`() {
        val usage = CredentialUsage("c1", copiedAt = listOf("d1", "garbage"))
        assertEquals(1, VaultPresentation.copiesWithin(usage, days = 30, daysAgo = daysAgo))
    }

    @Test
    fun `no usage means no copies`() {
        assertEquals(0, VaultPresentation.copiesWithin(null, days = 30, daysAgo = daysAgo))
    }

    // The audit section shows for every secret whose material can leave the vault: a password
    // (clipboard) and anything with an exportable private key. A file-backed secret has neither —
    // its material never entered the vault (issue #221).

    @Test
    fun `a password has an audit trail`() {
        assertTrue(hasAuditTrail(credential(CredentialSecret.Password("pw"))))
    }

    @Test
    fun `a private key has an audit trail`() {
        assertTrue(hasAuditTrail(credential(CredentialSecret.PrivateKey("-----BEGIN", null))))
    }

    @Test
    fun `a certificate has an audit trail`() {
        assertTrue(hasAuditTrail(credential(CredentialSecret.Certificate("-----BEGIN", "ssh-ed25519-cert", null))))
    }

    @Test
    fun `a file-backed secret has none`() {
        assertFalse(hasAuditTrail(credential(CredentialSecret.KeyFile("/home/u/.ssh/id", null, null))))
    }

    private fun credential(secret: CredentialSecret) = Credential(id = "c1", label = "Key", secret = secret)
}
