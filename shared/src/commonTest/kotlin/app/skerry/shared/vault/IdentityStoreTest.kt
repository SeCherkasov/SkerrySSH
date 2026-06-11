package app.skerry.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityStoreTest {

    @Test
    fun `put then get round-trips a password identity`() {
        val store = IdentityStore(FakeVault())
        val identity = Identity("id-1", "Prod root", IdentityAuth.Password("s3cret"))

        store.put(identity)

        assertEquals(identity, store.get("id-1"))
    }

    @Test
    fun `put then get round-trips a private-key identity with passphrase`() {
        val store = IdentityStore(FakeVault())
        val identity = Identity(
            "id-2",
            "Laptop key",
            IdentityAuth.PrivateKey(privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\n...", passphrase = "pp"),
        )

        store.put(identity)

        assertEquals(identity, store.get("id-2"))
    }

    @Test
    fun `private-key identity without passphrase round-trips with null`() {
        val store = IdentityStore(FakeVault())
        val identity = Identity("id-3", "CI key", IdentityAuth.PrivateKey(privateKeyPem = "pem", passphrase = null))

        store.put(identity)

        assertEquals(identity, store.get("id-3"))
    }

    @Test
    fun `get returns null for an unknown id`() {
        val store = IdentityStore(FakeVault())

        assertNull(store.get("missing"))
    }

    @Test
    fun `all returns live identities and skips tombstones`() {
        val store = IdentityStore(FakeVault())
        store.put(Identity("a", "A", IdentityAuth.Password("x")))
        store.put(Identity("b", "B", IdentityAuth.Password("y")))

        store.remove("a")

        assertEquals(listOf("b"), store.all().map { it.id })
    }

    @Test
    fun `all ignores records of other types`() {
        val vault = FakeVault()
        // Чужая запись (HOST) в том же vault не должна попасть в список identity.
        vault.put("host-1", RecordType.HOST, "whatever".encodeToByteArray())
        val store = IdentityStore(vault)
        store.put(Identity("id-1", "Key", IdentityAuth.Password("x")))

        assertEquals(listOf("id-1"), store.all().map { it.id })
    }

    @Test
    fun `put with an existing id updates in place`() {
        val store = IdentityStore(FakeVault())
        store.put(Identity("id-1", "Old", IdentityAuth.Password("x")))

        store.put(Identity("id-1", "New", IdentityAuth.Password("y")))

        assertEquals(Identity("id-1", "New", IdentityAuth.Password("y")), store.get("id-1"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `remove tombstones the identity`() {
        val store = IdentityStore(FakeVault())
        store.put(Identity("id-1", "Key", IdentityAuth.Password("x")))

        store.remove("id-1")

        assertNull(store.get("id-1"))
    }

    @Test
    fun `all skips an identity record whose payload does not decode`() {
        val vault = FakeVault()
        // Битый payload под типом IDENTITY (например, мусор после миграции) не должен валить список.
        vault.put("broken", RecordType.IDENTITY, "not json".encodeToByteArray())
        val store = IdentityStore(vault)
        store.put(Identity("ok", "Key", IdentityAuth.Password("x")))

        assertEquals(listOf("ok"), store.all().map { it.id })
    }
}

/**
 * In-memory [Vault] для тестов [IdentityStore]: моделирует put/openPayload/records/remove с
 * версиями и tombstone как [app.skerry.shared.vault.FileVault], но без реального шифрования —
 * payload хранится как есть. Жизненный цикл (unlock/lock) и AAD здесь не проверяются.
 */
private class FakeVault : Vault {
    private data class Entry(val record: VaultRecord, val payload: ByteArray)

    private val entries = mutableMapOf<String, Entry>()

    override fun exists(): Boolean = true
    override val isUnlocked: Boolean = true
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun lock() = Unit

    override fun records(): List<VaultRecord> = entries.values.map { it.record }

    override fun openPayload(id: String): ByteArray? =
        entries[id]?.takeIf { !it.record.deleted }?.payload

    override fun put(id: String, type: RecordType, payload: ByteArray) {
        val version = (entries[id]?.record?.version ?: 0L) + 1
        // blob у реального FileVault — шифротекст, наружу читается только через openPayload();
        // sentinel вместо открытого payload, чтобы тест не приучал читать record.blob напрямую.
        entries[id] = Entry(
            VaultRecord(id, type, version, "2026-06-12T00:00:00Z", "test-device", deleted = false, blob = SEALED),
            payload,
        )
    }

    override fun remove(id: String) {
        val existing = entries[id] ?: return
        entries[id] = existing.copy(
            record = existing.record.copy(version = existing.record.version + 1, deleted = true),
        )
    }

    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true

    private companion object {
        val SEALED = "sealed".encodeToByteArray()
    }
}
