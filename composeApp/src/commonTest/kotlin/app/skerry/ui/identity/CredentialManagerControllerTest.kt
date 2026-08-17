package app.skerry.ui.identity

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.CredentialUsage
import app.skerry.shared.vault.CredentialUsageLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredentialManagerControllerTest {

    @Test
    fun `save without id creates a password credential with a generated id`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }

        val id = controller.save(CredentialDraft(label = "Prod", kind = CredentialKind.PASSWORD, password = "pw"))

        assertEquals("gen", id)
        val c = controller.credentials.single()
        assertEquals("gen", c.id)
        assertEquals("Prod", c.label)
        assertEquals(CredentialSecret.Password("pw"), c.secret)
    }

    @Test
    fun `save builds a private-key credential, blank passphrase becomes null`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }

        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PRIVATE_KEY, privateKeyPem = "pem", passphrase = ""))

        assertEquals(CredentialSecret.PrivateKey("pem", passphrase = null), controller.credentials.single().secret)
    }

    @Test
    fun `save with a passphrase keeps it`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }

        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PRIVATE_KEY, privateKeyPem = "pem", passphrase = "pp"))

        assertEquals(CredentialSecret.PrivateKey("pem", passphrase = "pp"), controller.credentials.single().secret)
    }

    @Test
    fun `save builds a certificate credential`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }

        controller.save(
            CredentialDraft(label = "Cert", kind = CredentialKind.CERTIFICATE, privateKeyPem = "pem", certificate = "cert"),
        )

        assertEquals(CredentialSecret.Certificate("pem", "cert", passphrase = null), controller.credentials.single().secret)
    }

    @Test
    fun `save with an existing id updates in place`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { error("must not generate") }

        val id = controller.save(CredentialDraft(id = "x", label = "New", kind = CredentialKind.PASSWORD, password = "p2"))

        assertEquals("x", id)
        assertEquals(1, controller.credentials.size)
    }

    @Test
    fun `starts empty and reload pulls existing credentials from the vault`() {
        val vault = FakeCredVault()
        CredentialManagerController(CredentialStore(vault)) { "seed" }
            .save(CredentialDraft(label = "Pre-existing", kind = CredentialKind.PASSWORD, password = "p"))
        val controller = CredentialManagerController(CredentialStore(vault)) { "gen" }

        assertEquals(emptyList(), controller.credentials)
        controller.reload()

        assertEquals(listOf("Pre-existing"), controller.credentials.map { it.label })
    }

    @Test
    fun `edit changes the label in the reactive list and keeps id and secret`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }
        controller.save(CredentialDraft(label = "old", kind = CredentialKind.PRIVATE_KEY, privateKeyPem = "pem", passphrase = "pp"))

        controller.edit("gen", "new", note = null)

        val c = controller.credentials.single()
        assertEquals("gen", c.id)
        assertEquals("new", c.label)
        assertEquals(CredentialSecret.PrivateKey("pem", passphrase = "pp"), c.secret)
    }

    @Test
    fun `edit of a missing id is a no-op`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }

        controller.edit("missing", "x", note = null)

        assertEquals(emptyList(), controller.credentials)
    }

    @Test
    fun `edit writes the note, and re-saving the secret keeps it`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }
        controller.save(CredentialDraft(label = "temp key", kind = CredentialKind.PRIVATE_KEY, privateKeyPem = "pem"))
        controller.edit("gen", "temp key", note = "drop after the audit")

        // Rotating the material goes through the form, which has no note field: the remark on the
        // secret must survive that write rather than be silently dropped.
        controller.save(CredentialDraft(id = "gen", label = "temp key", kind = CredentialKind.PRIVATE_KEY, privateKeyPem = "pem2"))

        val c = controller.credentials.single()
        assertEquals("drop after the audit", c.note)
        assertEquals(CredentialSecret.PrivateKey("pem2"), c.secret)
    }

    @Test
    fun `delete removes the credential`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }
        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PASSWORD, password = "p"))

        controller.delete("gen")

        assertEquals(emptyList(), controller.credentials)
    }

    @Test
    fun `find resolves by id or returns null`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }
        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PASSWORD, password = "p"))

        assertEquals("Key", controller.find("gen")?.label)
        assertNull(controller.find("missing"))
        assertNull(controller.find(null))
    }

    @Test
    fun `creating a secret stamps it as added, updating one does not`() {
        val usage = FakeUsageLog()
        val controller = CredentialManagerController(CredentialStore(FakeCredVault()), usage = usage, scope = CoroutineScope(Dispatchers.Unconfined)) { "gen" }

        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PASSWORD, password = "p"))
        assertEquals(listOf("added:gen"), usage.events)

        // An edit of the same secret is not a second birth — it is a rotation (see the test below).
        controller.save(CredentialDraft(id = "gen", label = "Key", kind = CredentialKind.PASSWORD, password = "p2"))
        assertEquals(listOf("added:gen", "changed:gen"), usage.events)
    }

    @Test
    fun `replacing the material of a secret is recorded as a rotation`() {
        val usage = FakeUsageLog()
        val controller = CredentialManagerController(CredentialStore(FakeCredVault()), usage = usage, scope = CoroutineScope(Dispatchers.Unconfined)) { "gen" }
        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PASSWORD, password = "p"))
        usage.events.clear()

        controller.save(CredentialDraft(id = "gen", label = "Key", kind = CredentialKind.PASSWORD, password = "p2"))
        assertEquals(listOf("changed:gen"), usage.events)

        // Re-saving the same material (a rename goes through save too) is not a rotation.
        controller.save(CredentialDraft(id = "gen", label = "Renamed", kind = CredentialKind.PASSWORD, password = "p2"))
        assertEquals(listOf("changed:gen"), usage.events)
    }

    @Test
    fun `imported secrets are stamped as added`() {
        val usage = FakeUsageLog()
        val controller = CredentialManagerController(CredentialStore(FakeCredVault()), usage = usage, scope = CoroutineScope(Dispatchers.Unconfined)) { "gen" }

        controller.importCredentials(listOf(Credential("i1", "Imported", CredentialSecret.Password("p"))))

        assertEquals(listOf("added:i1"), usage.events)
    }

    @Test
    fun `deleting a secret forgets its usage`() {
        val usage = FakeUsageLog()
        val controller = CredentialManagerController(CredentialStore(FakeCredVault()), usage = usage, scope = CoroutineScope(Dispatchers.Unconfined)) { "gen" }
        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PASSWORD, password = "p"))

        controller.delete("gen")

        assertEquals(listOf("added:gen", "forgot:gen"), usage.events)
    }

    @Test
    fun `resolving a secret for a connection marks it used`() {
        val usage = FakeUsageLog()
        val controller = CredentialManagerController(CredentialStore(FakeCredVault()), usage = usage, scope = CoroutineScope(Dispatchers.Unconfined)) { "gen" }
        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PASSWORD, password = "p"))
        usage.events.clear()

        assertEquals("Key", controller.useForConnect("gen")?.label)
        assertEquals(listOf("used:gen"), usage.events)

        // A host with no binding (or a dangling one) has nothing to mark.
        assertNull(controller.useForConnect("missing"))
        assertNull(controller.useForConnect(null))
        assertEquals(listOf("used:gen"), usage.events)
    }

    @Test
    fun `copying a secret is recorded against it`() {
        val usage = FakeUsageLog()
        val controller = CredentialManagerController(CredentialStore(FakeCredVault()), usage = usage, scope = CoroutineScope(Dispatchers.Unconfined)) { "gen" }

        controller.recordCopied("gen")

        assertEquals(listOf("copied:gen"), usage.events)
    }

    @Test
    fun `exporting a key is recorded against it`() = runTest {
        val usage = FakeUsageLog()
        val controller = CredentialManagerController(CredentialStore(FakeCredVault()), usage = usage, scope = CoroutineScope(Dispatchers.Unconfined)) { "gen" }

        controller.recordExported("gen")

        assertEquals(listOf("exported:gen"), usage.events)
        assertEquals(1, controller.usageOf("gen")?.exportedAt?.size)
    }

    @Test
    fun `usage events run off the caller thread in the order they were submitted`() = runTest {
        // The production lane: one thread, so a forget can't overtake a still-pending recordUsed and
        // the in-memory mirror can't lose an update to a concurrent one.
        @OptIn(ExperimentalCoroutinesApi::class)
        val lane = Dispatchers.Default.limitedParallelism(1)
        val usage = FakeUsageLog()
        val controller = CredentialManagerController(CredentialStore(FakeCredVault()), usage = usage, scope = CoroutineScope(lane)) { "gen" }

        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PASSWORD, password = "p"))
        controller.useForConnect("gen")
        controller.delete("gen")
        // Barrier: the lane is FIFO, so once our own block runs everything queued before it is done.
        withContext(lane) { }

        assertEquals(listOf("added:gen", "used:gen", "forgot:gen"), usage.events)
        assertNull(controller.usageOf("gen"))
    }

    @Test
    fun `works without a usage log`() {
        val controller = CredentialManagerController(CredentialStore(FakeCredVault())) { "gen" }
        controller.save(CredentialDraft(label = "Key", kind = CredentialKind.PASSWORD, password = "p"))

        assertEquals("Key", controller.useForConnect("gen")?.label)
        controller.recordCopied("gen")
        controller.delete("gen")

        assertEquals(emptyList(), controller.credentials)
    }
}

/** Records what the controller reported, in order, so tests assert on the calls themselves. */
private class FakeUsageLog : CredentialUsageLog {
    val events = mutableListOf<String>()
    private val entries = mutableMapOf<String, CredentialUsage>()

    override fun of(credentialId: String): CredentialUsage? = entries[credentialId]
    override fun all(): List<CredentialUsage> = entries.values.toList()

    override fun recordAdded(credentialId: String): CredentialUsage {
        events += "added:$credentialId"
        return store(credentialId) { it.copy(addedAt = it.addedAt ?: "t") }
    }

    override fun recordChanged(credentialId: String): CredentialUsage {
        events += "changed:$credentialId"
        return store(credentialId) { it.copy(changedAt = "t") }
    }

    override fun recordUsed(credentialId: String): CredentialUsage {
        events += "used:$credentialId"
        return store(credentialId) { it.copy(lastUsedAt = "t") }
    }

    override fun recordCopied(credentialId: String): CredentialUsage {
        events += "copied:$credentialId"
        return store(credentialId) { it.copy(copiedAt = it.copiedAt + "t") }
    }

    override fun recordExported(credentialId: String): CredentialUsage {
        events += "exported:$credentialId"
        return store(credentialId) { it.copy(exportedAt = it.exportedAt + "t") }
    }

    override fun forget(credentialId: String) {
        events += "forgot:$credentialId"
        entries -= credentialId
    }

    override fun clear() {
        events += "cleared"
        entries.clear()
    }

    private fun store(id: String, edit: (CredentialUsage) -> CredentialUsage): CredentialUsage =
        edit(entries[id] ?: CredentialUsage(id)).also { entries[id] = it }
}
