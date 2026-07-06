package app.skerry.shared.vault

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultMigrationTest {

    // Seeds an IDENTITY record in the legacy raw-secret format: {id,label,auth:<secretJson>}.
    private fun seedLegacySecret(vault: Vault, id: String, label: String, secretJson: String) {
        vault.put(id, RecordType.IDENTITY, """{"id":"$id","label":"$label","auth":$secretJson}""".encodeToByteArray())
    }

    // Seeds an IDENTITY record in the legacy account-wrapper format: {id,label,username,credentialId}.
    private fun seedAccountWrapper(vault: Vault, id: String, label: String, username: String, credentialId: String) {
        vault.put(
            id,
            RecordType.IDENTITY,
            """{"id":"$id","label":"$label","username":"$username","credentialId":"$credentialId"}""".encodeToByteArray(),
        )
    }

    private fun liveIdentities(vault: Vault) =
        vault.records().filter { it.type == RecordType.IDENTITY && !it.deleted }

    @Test
    fun `legacy raw secret becomes a credential and host points straight at it`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Prod key", """{"type":"private_key","privateKeyPem":"pem"}""")
        hosts.put(Host("h1", "prod", "1.2.3.4", username = "root", identityId = "sec-1"))

        val changed = VaultMigration(vault, hosts).migrate()

        assertTrue(changed)
        // The secret moved into the keychain (same id) as a CREDENTIAL.
        assertEquals(
            Credential("sec-1", "Prod key", CredentialSecret.PrivateKey("pem")),
            CredentialStore(vault).get("sec-1"),
        )
        // Host now references the secret directly; the legacy pointer is cleared.
        val host = hosts.all().single()
        assertEquals("sec-1", host.credentialId)
        assertNull(host.identityId)
        // No live IDENTITY records remain.
        assertTrue(liveIdentities(vault).isEmpty())
    }

    @Test
    fun `two-level account wrapper collapses to a direct credential reference`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("c1", "Key", CredentialSecret.Password("x")))
        seedAccountWrapper(vault, "a1", "root@a", "root", "c1")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "a1"))

        val changed = VaultMigration(vault, hosts).migrate()

        assertTrue(changed)
        val host = hosts.all().single()
        assertEquals("c1", host.credentialId)
        assertNull(host.identityId)
        // Wrapper is removed and no live IDENTITY records remain; the credential itself survives.
        assertTrue(liveIdentities(vault).isEmpty())
        assertNotNull(CredentialStore(vault).get("c1"))
    }

    @Test
    fun `migration is idempotent`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Key", """{"type":"password","password":"x"}""")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "sec-1"))

        assertTrue(VaultMigration(vault, hosts).migrate())
        val credIdAfterFirst = hosts.all().single().credentialId

        // Second run changes nothing: no IDENTITY records left and host identityId is already cleared.
        assertFalse(VaultMigration(vault, hosts).migrate())
        assertEquals(credIdAfterFirst, hosts.all().single().credentialId)
    }

    @Test
    fun `host without a binding is left untouched but the legacy secret still migrates`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        seedLegacySecret(vault, "sec-1", "Key", """{"type":"password","password":"x"}""")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = null))

        VaultMigration(vault, hosts).migrate()

        // A host with no binding gets no credentialId.
        assertNull(hosts.all().single().credentialId)
        assertNull(hosts.all().single().identityId)
        // The secret still migrates to the keychain (it's in the vault even if unused by any host).
        assertNotNull(CredentialStore(vault).get("sec-1"))
    }

    @Test
    fun `host identityId pointing straight at a live credential is resolved`() {
        // Interrupted run: step (a) already converted the secret to CREDENTIAL, but step (c) never
        // cleared the host's identityId. A rerun must rebind the host straight to that secret.
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("c1", "Key", CredentialSecret.Password("x")))
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "c1"))

        assertTrue(VaultMigration(vault, hosts).migrate())

        val host = hosts.all().single()
        assertEquals("c1", host.credentialId)
        assertNull(host.identityId)
        assertNotNull(CredentialStore(vault).get("c1"))
    }

    @Test
    fun `host bound to a vanished secret is unbound rather than left dangling`() {
        // Corruption/interrupted run: the legacy pointer resolves to neither a wrapper nor a live
        // secret (both gone). The host is unbound (will prompt for a password) rather than left
        // with a dangling reference to a nonexistent id.
        val vault = FakeVault()
        val hosts = FakeHostStore()
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "gone"))

        VaultMigration(vault, hosts).migrate()

        val host = hosts.all().single()
        assertNull(host.credentialId)
        assertNull(host.identityId)
        // Rerun is stable (host is already unbound).
        assertFalse(VaultMigration(vault, hosts).migrate())
    }

    @Test
    fun `account wrapper with a missing target credential is not collapsed and host is unbound`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        // Wrapper references secret "c1", which does not exist in the vault.
        seedAccountWrapper(vault, "a1", "root@a", "root", "c1")
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "a1"))

        VaultMigration(vault, hosts).migrate()

        // No dangling reference is created — host is unbound; the targetless wrapper is kept as evidence of the link.
        val host = hosts.all().single()
        assertNull(host.credentialId)
        assertNull(host.identityId)
    }

    @Test
    fun `record with both auth and credentialId is treated as a wrapper not a raw secret`() {
        // Discriminator: a record with credentialId is an account wrapper even if it also has auth.
        // It is not converted to CREDENTIAL (that would lose the link) but collapsed as a wrapper.
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("c1", "Key", CredentialSecret.Password("x")))
        vault.put(
            "a1",
            RecordType.IDENTITY,
            """{"id":"a1","label":"root@a","username":"root","credentialId":"c1","auth":{"type":"password","password":"x"}}""".encodeToByteArray(),
        )
        hosts.put(Host("h1", "a", "a.example", username = "root", identityId = "a1"))

        VaultMigration(vault, hosts).migrate()

        val host = hosts.all().single()
        assertEquals("c1", host.credentialId)
        assertNull(host.identityId)
        // "a1" did not become a separate credential (not converted as a raw secret).
        assertNull(CredentialStore(vault).get("a1"))
        assertNotNull(CredentialStore(vault).get("c1"))
    }

    @Test
    fun `nothing to migrate returns false`() {
        val vault = FakeVault()
        val hosts = FakeHostStore()
        CredentialStore(vault).put(Credential("c1", "Key", CredentialSecret.Password("x")))
        hosts.put(Host("h1", "a", "a.example", username = "root", credentialId = "c1"))

        assertFalse(VaultMigration(vault, hosts).migrate())
    }
}
