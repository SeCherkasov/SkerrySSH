package app.skerry.ui.identity

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.Identity
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
    fun `save without id creates an account with a generated id`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { "gen" }

        val id = controller.save(label = "Prod", username = "root", credentialId = "cred-1")

        assertEquals("gen", id)
        assertEquals(Identity("gen", "Prod", "root", "cred-1"), controller.identities.single())
    }

    @Test
    fun `save with an existing id updates in place`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { error("must not generate") }

        val id = controller.save(label = "New", username = "deploy", credentialId = "cred-2", id = "x")

        assertEquals("x", id)
        assertEquals(1, controller.identities.size)
        assertEquals(Identity("x", "New", "deploy", "cred-2"), controller.identities.single())
    }

    @Test
    fun `starts empty and reload pulls existing accounts from the vault`() {
        // Контроллер создаётся до разблокировки vault, поэтому не читает стор в конструкторе;
        // существующие записи появляются только после reload (вызывается из UI после unlock).
        val vault = FakeVault()
        IdentityStore(vault).put(Identity("a", "Pre-existing", "root", "cred-pre"))
        val controller = IdentityManagerController(IdentityStore(vault)) { "gen" }

        assertEquals(emptyList(), controller.identities)
        controller.reload()

        assertEquals(listOf("Pre-existing"), controller.identities.map { it.label })
    }

    @Test
    fun `delete removes the account`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { "gen" }
        controller.save(label = "Key", username = "root", credentialId = "cred-1")

        controller.delete("gen")

        assertEquals(emptyList(), controller.identities)
    }

    @Test
    fun `find resolves by id or returns null`() {
        val controller = IdentityManagerController(IdentityStore(FakeVault())) { "gen" }
        controller.save(label = "Key", username = "root", credentialId = "cred-1")

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

    // Биометрия в тестах identity-менеджера не задействована — стабы.
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Corrupted
    override fun exportDataKey(): DataKey? = null
}
