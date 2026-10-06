package app.skerry.server.db

import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

internal suspend fun signedPolicyFixture(
    teams: TeamRepository, owner: String, teamId: String, scopeId: String = "", revision: Long = 1,
    epoch: Long = 0, retentionDays: Int = 30,
): StoredRecordingPolicy {
    val secret = Ed25519PrivateKeyParameters(SecureRandom())
    teams.publishKey(owner, ByteArray(32), secret.generatePublicKey().encoded, 0)
    val ciphertext = ByteArray(40) { it.toByte() }
    val aad = buildString {
        append("skerry.team-recording.v1:policy")
        for (field in listOf(teamId, scopeId, revision.toString(), epoch.toString(), retentionDays.toString())) {
            append(':').append(field.length).append(':').append(field)
        }
    }.encodeToByteArray()
    val signed = aad + ciphertext
    val signer = Ed25519Signer()
    signer.init(true, secret)
    signer.update(signed, 0, signed.size)
    return StoredRecordingPolicy(revision, epoch, retentionDays, ciphertext, signer.generateSignature())
}
