package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.SharingKeyPair
import app.skerry.shared.vault.VaultCrypto
import com.ionspin.kotlin.crypto.generichash.GenericHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** What the invitee decrypts: the team key and its name (the server sees neither). */
class TeamInvitePayload(
    val teamKey: DataKey,
    val teamName: String,
)

/**
 * Invite envelope: JSON `{v, teamKey, name}` sealed (crypto_box_seal) to the invitee's public key.
 * The version inside the payload is a domain binding — an envelope from a different context
 * (not an invite) is rejected by the decoder.
 */
class TeamInviteCodec(private val crypto: VaultCrypto) {

    @Serializable
    private data class WireInvite(val v: Int, val teamKey: String, val name: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun seal(recipientPublicKey: ByteArray, teamKey: DataKey, teamName: String): ByteArray {
        val plain = json.encodeToString(
            WireInvite.serializer(),
            WireInvite(VERSION, teamKey.bytes.toByteString().base64(), teamName),
        ).encodeToByteArray()
        return crypto.sealForRecipient(recipientPublicKey, plain).also { plain.fill(0) }
    }

    /** null if the envelope isn't ours, is corrupted, or has an unexpected version (untrusted input). */
    fun open(keyPair: SharingKeyPair, envelope: ByteArray): TeamInvitePayload? {
        val plain = crypto.openSealedEnvelope(keyPair, envelope) ?: return null
        return try {
            val wire = json.decodeFromString(WireInvite.serializer(), plain.decodeToString())
            if (wire.v != VERSION) return null
            val keyBytes = wire.teamKey.decodeBase64()?.toByteArray() ?: return null
            if (keyBytes.size != 32) return null
            TeamInvitePayload(DataKey(keyBytes), wire.name)
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        const val VERSION = 1
    }
}

/**
 * Short fingerprint of a public key (BLAKE2b-8), format `ab12-cd34-ef56-7890`. Parties compare it
 * over a trusted channel (voice/chat) to guard against key substitution by the server.
 */
fun sharingKeyFingerprint(publicKey: ByteArray): String {
    @OptIn(ExperimentalUnsignedTypes::class)
    val digest = GenericHash.genericHash(publicKey.toUByteArray(), 8).toByteArray()
    return digest.toList().chunked(2).joinToString("-") { pair ->
        pair.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
