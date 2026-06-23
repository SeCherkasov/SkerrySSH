package app.skerry.ui.identity

import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
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
}

/** In-memory [Vault] с хранением записей (put/openPayload/records/remove, tombstone) для тестов. */
private class FakeCredVault : Vault {
    private val payloads = mutableMapOf<String, ByteArray>()
    private val records = mutableMapOf<String, VaultRecord>()

    override fun exists(): Boolean = true
    override val isUnlocked: Boolean = true
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun lock() = Unit

    override fun records(): List<VaultRecord> = records.values.toList()
    override fun openPayload(id: String): ByteArray? =
        records[id]?.takeIf { !it.deleted }?.let { payloads[id] }

    override fun put(id: String, type: RecordType, payload: ByteArray) {
        val version = (records[id]?.version ?: 0L) + 1
        records[id] = VaultRecord(id, type, version, "2026-06-12T00:00:00Z", "dev", deleted = false, blob = ByteArray(0))
        payloads[id] = payload
    }

    override fun remove(id: String) {
        records[id] = (records[id] ?: return).copy(version = records[id]!!.version + 1, deleted = true)
    }

    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true

    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Corrupted
    override fun exportDataKey(): DataKey? = null
}
