package app.skerry.server.db

import java.security.MessageDigest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

data class RecordingChunkSpec(val index: Int, val length: Int, val sha256: String)

data class RecordingReservation(
    val teamId: String,
    val scopeId: String,
    val recordingId: String,
    val hostId: String,
    val actorId: String,
    val keyEpoch: Long,
    val wrappedKey: ByteArray,
    val manifest: ByteArray,
    val chunks: List<RecordingChunkSpec>,
    val durationSec: Long,
    val retentionDays: Int,
    val wrapEpoch: Long = keyEpoch,
)

data class StoredRecording(
    val teamId: String,
    val scopeId: String,
    val recordingId: String,
    val hostId: String,
    val actorId: String,
    val keyEpoch: Long,
    val wrappedKey: ByteArray,
    val manifest: ByteArray,
    val chunkCount: Int,
    val durationSec: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val wrapEpoch: Long,
    val stagedWrappedKey: ByteArray?,
    val stagedWrapEpoch: Long?,
)

data class StoredRecordingPolicy(
    val revision: Long,
    val keyEpoch: Long,
    val retentionDays: Int,
    val ciphertext: ByteArray,
    val signature: ByteArray,
)

enum class RecordingReserveResult { CREATED, SAME, RETIRED, CONFLICT, NO_HOST, NO_POLICY, DENIED, TOO_LARGE }
enum class RecordingChunkResult { CREATED, SAME, CONFLICT, NOT_FOUND, DENIED }
enum class RecordingPolicyResult { UPDATED, CONFLICT, DENIED }

internal fun verifyPolicySignature(
    teamId: String, scopeId: String, policy: StoredRecordingPolicy, publicKey: ByteArray,
): Boolean = try {
    val aad = buildString {
        append("skerry.team-recording.v1:policy")
        for (field in listOf(teamId, scopeId, policy.revision.toString(), policy.keyEpoch.toString(),
            policy.retentionDays.toString())) append(':').append(field.length).append(':').append(field)
    }.encodeToByteArray()
    val signed = aad + policy.ciphertext
    val verifier = Ed25519Signer()
    verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
    verifier.update(signed, 0, signed.size)
    verifier.verifySignature(policy.signature)
} catch (_: IllegalArgumentException) {
    false
}
