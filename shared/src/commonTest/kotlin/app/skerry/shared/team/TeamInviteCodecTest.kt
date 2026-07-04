package app.skerry.shared.team

import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TeamInviteCodecTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val codec = TeamInviteCodec(crypto)

    private fun cryptoTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    @Test
    fun `invite round-trips team key and name to the invited key pair`() = cryptoTest {
        val bob = crypto.newSharingKeyPair()
        val teamKey = crypto.newDataKey()
        val record = crypto.seal(teamKey, "shared-host".encodeToByteArray(), VaultCrypto.EMPTY_AAD)

        val envelope = codec.seal(bob.publicKey, teamKey, "Platform crew")
        val opened = codec.open(bob, envelope)

        assertNotNull(opened)
        assertEquals("Platform crew", opened.teamName)
        // расшифрованный teamKey функционально равен исходному: читает запись команды
        assertContentEquals(
            "shared-host".encodeToByteArray(),
            crypto.open(opened.teamKey, record, VaultCrypto.EMPTY_AAD),
        )
    }

    @Test
    fun `invite is unreadable by another key pair and rejects tampering`() = cryptoTest {
        val bob = crypto.newSharingKeyPair()
        val envelope = codec.seal(bob.publicKey, crypto.newDataKey(), "Crew")

        assertNull(codec.open(crypto.newSharingKeyPair(), envelope))

        envelope[envelope.size - 1] = (envelope[envelope.size - 1].toInt() xor 0x01).toByte()
        assertNull(codec.open(bob, envelope))
    }

    @Test
    fun `garbage sealed for the right key is rejected by the codec`() = cryptoTest {
        // Конверт валиден криптографически, но внутри не приглашение — доменная проверка отбрасывает.
        val bob = crypto.newSharingKeyPair()
        val alien = crypto.sealForRecipient(bob.publicKey, "not-json".encodeToByteArray())

        assertNull(codec.open(bob, alien))
    }

    @Test
    fun `fingerprint is stable per key and differs between keys`() = cryptoTest {
        val a = crypto.newSharingKeyPair()
        val b = crypto.newSharingKeyPair()

        assertEquals(sharingKeyFingerprint(a.publicKey), sharingKeyFingerprint(a.publicKey))
        assertNotEquals(sharingKeyFingerprint(a.publicKey), sharingKeyFingerprint(b.publicKey))
        assertEquals(4, sharingKeyFingerprint(a.publicKey).split("-").size)
    }
}
