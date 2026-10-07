package app.skerry.shared.host

import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.VaultRecordCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class HostPayloadIdentityTest {
    @Test
    fun `a peer cannot add another profile under an existing payload identity`() {
        val vault = FakeVault()
        val store = VaultHostStore(vault)
        val honest = Host("honest", "Honest server", "honest.test", username = "test")
        store.put(honest)
        val codec = VaultRecordCodec(vault, RecordType.HOST, Host.serializer())
        // A team peer can encrypt arbitrary payloads under a different authenticated record ID.
        codec.put("peer-record", honest.copy(label = "Peer server", address = "peer.test"))
        assertEquals(listOf(honest), store.all(), "Mismatched payload IDs must not enter the catalog")
        assertEquals(2, vault.records().count { it.type == RecordType.HOST }, "Reading must not delete peer data")
    }

    @Test
    fun `a mismatched unique identity is skipped while valid profiles remain readable`() {
        val vault = FakeVault()
        val store = VaultHostStore(vault)
        val valid = Host("valid", "Valid server", "valid.test", username = "test")
        store.put(valid)
        VaultRecordCodec(vault, RecordType.HOST, Host.serializer()).put(
            "record-id", Host("payload-id", "Invalid identity", "peer.test", username = "test"),
        )
        assertEquals(listOf(valid), store.all())
    }
}
