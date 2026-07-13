package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.SharingKeyPair
import app.skerry.shared.vault.SigningKeyPair
import app.skerry.shared.vault.VaultCrypto
import com.ionspin.kotlin.crypto.generichash.GenericHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * What the invitee decrypts and — after signature verification — trusts. [inviterAccountId] is the
 * account that signed the envelope; the invitee verifies the signature against that account's
 * published Ed25519 key and confirms its fingerprint out-of-band. [epoch] is the teamKey generation
 * the sealed key belongs to (see [TeamKeyEntry.epoch]).
 */
class TeamInvitePayload(
    val teamKey: DataKey,
    val teamName: String,
    val teamId: String,
    val inviterAccountId: String,
    val inviteeAccountId: String,
    val epoch: Int,
    /** Detached signature over [signedBytes]; verified via [TeamInviteCodec.verify]. */
    internal val signature: ByteArray,
    internal val signedBytes: ByteArray,
)

/**
 * Invite envelope: JSON sealed (crypto_box_seal) to the invitee's X25519 key, carrying teamKey,
 * name, the invite binding (teamId, inviter, invitee, epoch), and a **detached Ed25519 signature**
 * by the inviter's identity key.
 *
 * Two independent guarantees:
 * - **Confidentiality** (the seal): only the invitee opens it; the server sees ciphertext.
 * - **Authenticity** (the signature): the invitee verifies who authored the invite. crypto_box_seal
 *   alone is anonymous — a malicious server could fabricate an invite to a fake team it controls the
 *   key for. The signature binds the envelope to a specific inviter, teamId, and invitee, so the
 *   server can neither forge one without the inviter's secret key nor retarget an existing one.
 *
 * Format version 2 (signed). Version 1 (unsigned, anonymous) is rejected — there are no released
 * clients holding v1 envelopes.
 */
class TeamInviteCodec(private val crypto: VaultCrypto) {

    @Serializable
    private data class WireInvite(
        val v: Int,
        val teamKey: String,
        val name: String,
        val teamId: String,
        val inviterId: String,
        val inviteeId: String,
        val epoch: Int,
        val sig: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Seals and signs an invite for [inviteeId]. [inviter] is the inviter's Ed25519 identity pair;
     * the binding (teamId, inviter, invitee, epoch) is authenticated so it can't be retargeted.
     */
    fun seal(
        recipientPublicKey: ByteArray,
        inviter: SigningKeyPair,
        inviterId: String,
        inviteeId: String,
        teamId: String,
        teamKey: DataKey,
        teamName: String,
        epoch: Int,
    ): ByteArray {
        val signed = inviteSignedBytes(teamId, inviterId, inviteeId, teamKey, teamName, epoch)
        val sig = crypto.sign(inviter, signed)
        val plain = json.encodeToString(
            WireInvite.serializer(),
            WireInvite(
                v = VERSION,
                teamKey = teamKey.bytes.toByteString().base64(),
                name = teamName,
                teamId = teamId,
                inviterId = inviterId,
                inviteeId = inviteeId,
                epoch = epoch,
                sig = sig.toByteString().base64(),
            ),
        ).encodeToByteArray()
        return crypto.sealForRecipient(recipientPublicKey, plain).also { plain.fill(0); signed.fill(0) }
    }

    /**
     * Decrypts and parses the envelope. **Does not verify the signature** — the caller resolves the
     * inviter's published key (from [TeamInvitePayload.inviterAccountId]) and calls [verify]. `null`
     * if the envelope isn't ours, is corrupted, or has an unexpected version (untrusted input).
     */
    fun open(keyPair: SharingKeyPair, envelope: ByteArray): TeamInvitePayload? {
        val plain = crypto.openSealedEnvelope(keyPair, envelope) ?: return null
        return try {
            val wire = json.decodeFromString(WireInvite.serializer(), plain.decodeToString())
            if (wire.v != VERSION) return null
            val keyBytes = wire.teamKey.decodeBase64()?.toByteArray() ?: return null
            if (keyBytes.size != 32) return null
            val sig = wire.sig.decodeBase64()?.toByteArray() ?: return null
            val teamKey = DataKey(keyBytes)
            TeamInvitePayload(
                teamKey = teamKey,
                teamName = wire.name,
                teamId = wire.teamId,
                inviterAccountId = wire.inviterId,
                inviteeAccountId = wire.inviteeId,
                epoch = wire.epoch,
                signature = sig,
                signedBytes = inviteSignedBytes(wire.teamId, wire.inviterId, wire.inviteeId, teamKey, wire.name, wire.epoch),
            )
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        } finally {
            plain.fill(0)
        }
    }

    /** True if [payload]'s signature is valid for [inviterSignPublicKey] (the inviter's published Ed25519 key). */
    fun verify(payload: TeamInvitePayload, inviterSignPublicKey: ByteArray): Boolean =
        crypto.verifySignature(inviterSignPublicKey, payload.signedBytes, payload.signature)

    private fun inviteSignedBytes(
        teamId: String,
        inviterId: String,
        inviteeId: String,
        teamKey: DataKey,
        teamName: String,
        epoch: Int,
    ): ByteArray = canonicalBytes(
        "skerry.team.invite.v$VERSION",
        teamId.encodeToByteArray(),
        inviterId.encodeToByteArray(),
        inviteeId.encodeToByteArray(),
        teamKey.bytes,
        teamName.encodeToByteArray(),
        intBytes(epoch),
    )

    private companion object {
        const val VERSION = 2
    }
}

/**
 * Unambiguous byte encoding of a list of fields for signing: a domain tag followed by each field
 * length-prefixed (4-byte big-endian). Length-prefixing (not delimiters) means a field containing
 * the delimiter — e.g. a team name with a newline — can't be split/merged to forge a different
 * signed message.
 */
internal fun canonicalBytes(domain: String, vararg fields: ByteArray): ByteArray {
    val buffer = Buffer()
    buffer.write(domain.encodeToByteArray())
    for (field in fields) {
        buffer.writeInt(field.size)
        buffer.write(field)
    }
    return buffer.readByteArray()
}

/** 4-byte big-endian encoding of [value] (epoch/counter fields in signed messages). */
internal fun intBytes(value: Int): ByteArray =
    byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

/**
 * 128-bit fingerprint of an account's Teams identity (X25519 sharing key ‖ Ed25519 signing key),
 * format `ab12-cd34-…` (8 groups). Parties compare it over a trusted channel (voice/chat) to guard
 * against key substitution by the server. Covers **both** halves: substituting either the sealing
 * or the signing key changes the fingerprint. 128 bits resists second-preimage forgery (a 64-bit
 * fingerprint was brute-forceable — see the security audit).
 */
fun accountKeyFingerprint(sharingPublicKey: ByteArray, signingPublicKey: ByteArray): String {
    @OptIn(ExperimentalUnsignedTypes::class)
    val digest = GenericHash.genericHash((sharingPublicKey + signingPublicKey).toUByteArray(), FINGERPRINT_BYTES)
        .toByteArray()
    return digest.toList().chunked(2).joinToString("-") { pair ->
        pair.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

private const val FINGERPRINT_BYTES = 16
