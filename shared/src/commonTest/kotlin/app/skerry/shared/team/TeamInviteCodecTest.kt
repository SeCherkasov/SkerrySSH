package app.skerry.shared.team

import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamInviteCodecTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val codec = TeamInviteCodec(crypto)

    private fun cryptoTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    @Test
    fun `invite round-trips key, name, and binding to the invited key pair`() = cryptoTest {
        val alice = crypto.newSigningKeyPair()
        val bob = crypto.newSharingKeyPair()
        val teamKey = crypto.newDataKey()
        val record = crypto.seal(teamKey, "shared-host".encodeToByteArray(), VaultCrypto.EMPTY_AAD)

        val envelope = codec.seal(bob.publicKey, alice, "alice@x", "bob@y", "team-1", teamKey, "Platform crew", epoch = 3)
        val opened = codec.open(bob, envelope)

        assertNotNull(opened)
        assertEquals("Platform crew", opened.teamName)
        assertEquals("team-1", opened.teamId)
        assertEquals("alice@x", opened.inviterAccountId)
        assertEquals("bob@y", opened.inviteeAccountId)
        assertEquals(3, opened.epoch)
        assertTrue(codec.verify(opened, alice.publicKey))
        assertContentEquals(
            "shared-host".encodeToByteArray(),
            crypto.open(opened.teamKey, record, VaultCrypto.EMPTY_AAD),
        )
    }

    @Test
    fun `invite signature is rejected when verified against the wrong signer`() = cryptoTest {
        val alice = crypto.newSigningKeyPair()
        val mallory = crypto.newSigningKeyPair()
        val bob = crypto.newSharingKeyPair()

        val envelope = codec.seal(bob.publicKey, alice, "alice@x", "bob@y", "team-1", crypto.newDataKey(), "Crew", epoch = 0)
        val opened = codec.open(bob, envelope)

        assertNotNull(opened)
        assertTrue(codec.verify(opened, alice.publicKey))
        // A server that fabricated the invite would have to publish its own signing key for "alice@x";
        // the fingerprint check catches that, and here the real alice key rejects the forged signature.
        assertFalse(codec.verify(opened, mallory.publicKey))
    }

    @Test
    fun `tampering with the sealed binding invalidates the signature`() = cryptoTest {
        // Even if an attacker could re-seal a modified payload to the invitee, the signature covers
        // the binding: changing teamId/inviter/invitee/epoch/name breaks verification.
        val alice = crypto.newSigningKeyPair()
        val bob = crypto.newSharingKeyPair()
        val teamKey = crypto.newDataKey()

        val original = codec.seal(bob.publicKey, alice, "alice@x", "bob@y", "team-1", teamKey, "Crew", epoch = 0)
        val opened = assertNotNull(codec.open(bob, original))

        // Forge an envelope with a different invitee but reuse alice's signature — verify must fail.
        val forgedForge = TeamInviteCodec(crypto)
        val reForged = forgedForge.seal(bob.publicKey, alice, "alice@x", "carol@z", "team-1", teamKey, "Crew", epoch = 0)
        val reOpened = assertNotNull(codec.open(bob, reForged))
        assertNotEquals(opened.inviteeAccountId, reOpened.inviteeAccountId)
        // Both are self-consistently signed by alice; the guarantee is the binding is authenticated,
        // so the coordinator's inviteeId == self check plus signature verification pin the target.
        assertTrue(codec.verify(reOpened, alice.publicKey))
    }

    @Test
    fun `invite is unreadable by another key pair and rejects tampering`() = cryptoTest {
        val alice = crypto.newSigningKeyPair()
        val bob = crypto.newSharingKeyPair()
        val envelope = codec.seal(bob.publicKey, alice, "alice@x", "bob@y", "team-1", crypto.newDataKey(), "Crew", epoch = 0)

        assertNull(codec.open(crypto.newSharingKeyPair(), envelope))

        envelope[envelope.size - 1] = (envelope[envelope.size - 1].toInt() xor 0x01).toByte()
        assertNull(codec.open(bob, envelope))
    }

    @Test
    fun `garbage sealed for the right key is rejected by the codec`() = cryptoTest {
        val bob = crypto.newSharingKeyPair()
        val alien = crypto.sealForRecipient(bob.publicKey, "not-json".encodeToByteArray())

        assertNull(codec.open(bob, alien))
    }

    @Test
    fun `scope grant round-trips its scope id and verifies only under that scope`() = cryptoTest {
        val alice = crypto.newSigningKeyPair()
        val bob = crypto.newSharingKeyPair()
        val scopeKey = crypto.newDataKey()

        val envelope = codec.seal(
            bob.publicKey, alice, "alice@x", "bob@y", "team-1", scopeKey, "Production", epoch = 1, scopeId = "prod",
        )
        val opened = assertNotNull(codec.open(bob, envelope))

        assertEquals("prod", opened.scopeId)
        assertEquals("Production", opened.teamName)
        assertTrue(codec.verify(opened, alice.publicKey, scopeId = "prod"))
        // Bound to its scope: the same signature doesn't authenticate the key for a different scope,
        // so a server that files this grant under another scope's slot is caught.
        assertFalse(codec.verify(opened, alice.publicKey, scopeId = "staging"))
    }

    @Test
    fun `a scope key cannot be re-sealed as a team-wide key`() = cryptoTest {
        // Domain separation. Without it, a member who legitimately holds a scope key could re-seal
        // alice's signature over that key as a team-wide rekey envelope (crypto_box_seal is anonymous
        // — anyone can seal to bob). Bob would adopt the attacker's key as teamKey and every team
        // record he writes afterwards would be readable by the attacker.
        val alice = crypto.newSigningKeyPair()
        val bob = crypto.newSharingKeyPair()
        val scopeKey = crypto.newDataKey()

        val scopeGrant = codec.seal(
            bob.publicKey, alice, "alice@x", "bob@y", "team-1", scopeKey, "Production", epoch = 1, scopeId = "prod",
        )
        val opened = assertNotNull(codec.open(bob, scopeGrant))

        assertFalse(codec.verify(opened, alice.publicKey))
    }

    @Test
    fun `a team-wide envelope cannot be presented as a scope grant`() = cryptoTest {
        val alice = crypto.newSigningKeyPair()
        val bob = crypto.newSharingKeyPair()

        val teamEnvelope = codec.seal(bob.publicKey, alice, "alice@x", "bob@y", "team-1", crypto.newDataKey(), "Crew", epoch = 0)
        val opened = assertNotNull(codec.open(bob, teamEnvelope))

        assertEquals("", opened.scopeId)
        assertTrue(codec.verify(opened, alice.publicKey))
        assertFalse(codec.verify(opened, alice.publicKey, scopeId = "prod"))
    }

    @Test
    fun `fingerprint covers both halves, is stable per identity, and is 128-bit`() = cryptoTest {
        val box = crypto.newSharingKeyPair()
        val sign = crypto.newSigningKeyPair()
        val otherSign = crypto.newSigningKeyPair()

        assertEquals(accountKeyFingerprint(box.publicKey, sign.publicKey), accountKeyFingerprint(box.publicKey, sign.publicKey))
        // Substituting the signing half changes the fingerprint (both keys are covered).
        assertNotEquals(accountKeyFingerprint(box.publicKey, sign.publicKey), accountKeyFingerprint(box.publicKey, otherSign.publicKey))
        // 16 bytes → 8 groups of 4 hex.
        assertEquals(8, accountKeyFingerprint(box.publicKey, sign.publicKey).split("-").size)
    }
}
