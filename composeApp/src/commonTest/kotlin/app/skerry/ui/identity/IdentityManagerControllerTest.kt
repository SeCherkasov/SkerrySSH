package app.skerry.ui.identity

import app.skerry.shared.vault.Identity
import app.skerry.shared.vault.IdentityAuth
import app.skerry.shared.vault.IdentityStore
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityManagerControllerTest {

    @Test
    fun `save without id creates a password identity with a generated id`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { "gen" }

        val id = controller.save(IdentityDraft(label = "Prod", kind = IdentityKind.PASSWORD, password = "pw"))

        assertEquals("gen", id)
        assertEquals(Identity("gen", "Prod", IdentityAuth.Password("pw")), controller.identities.single())
    }

    @Test
    fun `save builds a private-key identity, blank passphrase becomes null`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { "gen" }

        controller.save(
            IdentityDraft(label = "Key", kind = IdentityKind.PRIVATE_KEY, privateKeyPem = "pem", passphrase = ""),
        )

        assertEquals(IdentityAuth.PrivateKey("pem", passphrase = null), controller.identities.single().auth)
    }

    @Test
    fun `save with a passphrase keeps it`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { "gen" }

        controller.save(
            IdentityDraft(label = "Key", kind = IdentityKind.PRIVATE_KEY, privateKeyPem = "pem", passphrase = "pp"),
        )

        assertEquals(IdentityAuth.PrivateKey("pem", passphrase = "pp"), controller.identities.single().auth)
    }

    @Test
    fun `save with an existing id updates in place`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { error("must not generate") }

        val id = controller.save(IdentityDraft(id = "x", label = "New", kind = IdentityKind.PASSWORD, password = "p2"))

        assertEquals("x", id)
        assertEquals(1, controller.identities.size)
    }

    @Test
    fun `starts empty and reload pulls existing identities from the vault`() {
        // Контроллер создаётся до разблокировки vault, поэтому не читает стор в конструкторе;
        // существующие записи появляются только после reload (вызывается из UI после unlock).
        val vault = FakeVault()
        IdentityStore(vault).put(Identity("a", "Pre-existing", IdentityAuth.Password("p")))
        val controller = IdentityManagerController(IdentityStore(vault)) { "gen" }

        assertEquals(emptyList(), controller.identities)
        controller.reload()

        assertEquals(listOf("Pre-existing"), controller.identities.map { it.label })
    }

    @Test
    fun `delete removes the identity`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { "gen" }
        controller.save(IdentityDraft(label = "Key", kind = IdentityKind.PASSWORD, password = "p"))

        controller.delete("gen")

        assertEquals(emptyList(), controller.identities)
    }

    @Test
    fun `find resolves by id or returns null`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { "gen" }
        controller.save(IdentityDraft(label = "Key", kind = IdentityKind.PASSWORD, password = "p"))

        assertEquals("Key", controller.find("gen")?.label)
        assertNull(controller.find("missing"))
        assertNull(controller.find(null))
    }
}

/** In-memory [Vault] с хранением записей (put/openPayload/records/remove, tombstone) для тестов. */
private class FakeVault : Vault {
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
}
