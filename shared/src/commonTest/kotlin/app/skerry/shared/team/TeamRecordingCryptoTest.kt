package app.skerry.shared.team

import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TeamRecordingCryptoTest {
    private val crypto = IonspinVaultCrypto()
    private val ref = TeamScopeRef("team-1", "prod")

    @Test
    fun `chunk and key wrap bind every recording identity field`() = runTest {
        initializeVaultCrypto()
        val spaceKey = crypto.newDataKey()
        val recordingKey = crypto.newDataKey()
        val identity = RecordingIdentity(ref, "recording-1", "host-1", "actor-1", 3)
        val codec = TeamRecordingCrypto(crypto)
        val wrapped = codec.wrapKey(spaceKey, recordingKey, identity)
        val ciphertext = codec.sealChunk(recordingKey, identity, 0, "secret".encodeToByteArray())

        val recovered = codec.openKey(spaceKey, wrapped, identity)!!
        assertContentEquals("secret".encodeToByteArray(), codec.openChunk(recovered, identity, 0, ciphertext))
        assertNull(codec.openKey(spaceKey, wrapped, identity.copy(hostId = "host-2")))
        assertNull(codec.openChunk(recovered, identity, 1, ciphertext))
        assertNull(codec.openChunk(recovered, identity.copy(actorId = "actor-2"), 0, ciphertext))
    }

    @Test
    fun `owner signed policy rejects rollback and downgrade forgery`() = runTest {
        initializeVaultCrypto()
        val owner = crypto.newSigningKeyPair()
        val spaceKey = crypto.newDataKey()
        val codec = TeamRecordingCrypto(crypto)
        val required = RecordingPolicy(ref, RecordingMode.REQUIRED, revision = 7, keyEpoch = 3, retentionDays = 30)
        val signed = codec.sealPolicy(spaceKey, owner, required)

        assertEquals(required, codec.openPolicy(spaceKey, owner.publicKey, signed, ref, minimumRevision = 7))
        assertFailsWith<RecordingPolicyException> {
            codec.openPolicy(spaceKey, owner.publicKey, signed, ref, minimumRevision = 8)
        }
        assertFailsWith<RecordingPolicyException> {
            codec.openPolicy(spaceKey, owner.publicKey, signed.copy(revision = 8), ref, minimumRevision = 7)
        }
        assertFailsWith<RecordingPolicyException> {
            codec.openPolicy(spaceKey, owner.publicKey, signed, TeamScopeRef("team-2", "prod"), 0)
        }
    }

    @Test
    fun `journal authenticates slot identity and timestamp separately from cast chunks`() = runTest {
        initializeVaultCrypto()
        val codec = TeamRecordingCrypto(crypto)
        val key = crypto.newDataKey()
        val identity = RecordingIdentity(ref, "recording-1", "host-1", "actor-1", 3)
        val sealed = codec.sealJournal(key, identity, 0, 1000, "output".encodeToByteArray())
        val feed = codec.openJournal(key, identity, 0, sealed)
        try {
            assertEquals(1000L, feed.elapsedMillis)
            assertContentEquals("output".encodeToByteArray(), feed.bytes)
            assertFailsWith<IllegalStateException> { codec.openJournal(key, identity, 1, sealed) }
            assertFailsWith<IllegalStateException> { codec.openJournal(key, identity.copy(hostId = "host-2"), 0, sealed) }
            assertNull(codec.openChunk(key, identity, 0, sealed))
            assertFailsWith<IllegalStateException> { codec.openJournal(key, identity, 0, sealed.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }) }
        } finally { feed.bytes.fill(0); key.zeroize() }
    }
}
