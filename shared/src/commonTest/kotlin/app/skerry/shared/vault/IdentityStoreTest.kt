package app.skerry.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityStoreTest {

    @Test
    fun `put then get round-trips an account`() {
        val store = IdentityStore(FakeVault())
        val account = Identity("id-1", "Prod root", username = "root", credentialId = "cred-1")

        store.put(account)

        assertEquals(account, store.get("id-1"))
    }

    @Test
    fun `get returns null for an unknown id`() {
        val store = IdentityStore(FakeVault())

        assertNull(store.get("missing"))
    }

    @Test
    fun `all returns live accounts and skips tombstones`() {
        val store = IdentityStore(FakeVault())
        store.put(Identity("a", "A", "root", "c1"))
        store.put(Identity("b", "B", "deploy", "c2"))

        store.remove("a")

        assertEquals(listOf("b"), store.all().map { it.id })
    }

    @Test
    fun `all ignores records of other types`() {
        val vault = FakeVault()
        vault.put("host-1", RecordType.HOST, "whatever".encodeToByteArray())
        // Keychain-секрет (CREDENTIAL) — другой тип, не должен попасть в список учёток.
        vault.put("cred-1", RecordType.CREDENTIAL, "whatever".encodeToByteArray())
        val store = IdentityStore(vault)
        store.put(Identity("id-1", "Acct", "root", "cred-1"))

        assertEquals(listOf("id-1"), store.all().map { it.id })
    }

    @Test
    fun `put with an existing id updates in place`() {
        val store = IdentityStore(FakeVault())
        store.put(Identity("id-1", "Old", "root", "c1"))

        store.put(Identity("id-1", "New", "deploy", "c2"))

        assertEquals(Identity("id-1", "New", "deploy", "c2"), store.get("id-1"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `all skips an account record whose payload does not decode`() {
        val vault = FakeVault()
        vault.put("broken", RecordType.IDENTITY, "not json".encodeToByteArray())
        val store = IdentityStore(vault)
        store.put(Identity("ok", "Acct", "root", "c1"))

        assertEquals(listOf("ok"), store.all().map { it.id })
    }

    @Test
    fun `all skips a legacy identity record carrying a raw secret`() {
        val vault = FakeVault()
        // Старый формат (id,label,auth) больше не учётка: не должен показываться до миграции.
        vault.put(
            "legacy",
            RecordType.IDENTITY,
            """{"id":"legacy","label":"old","auth":{"type":"password","password":"x"}}""".encodeToByteArray(),
        )
        val store = IdentityStore(vault)
        store.put(Identity("ok", "Acct", "root", "c1"))

        assertEquals(listOf("ok"), store.all().map { it.id })
    }
}
