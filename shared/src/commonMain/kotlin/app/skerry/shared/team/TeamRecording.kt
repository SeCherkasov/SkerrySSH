package app.skerry.shared.team

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.SigningKeyPair
import app.skerry.shared.vault.VaultCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** A team's audit requirement for one keyed share space. */
enum class RecordingMode { OFF, OPTIONAL, REQUIRED }

@Serializable
data class RecordingPolicy(
    val ref: TeamScopeRef,
    val mode: RecordingMode,
    val revision: Long,
    val keyEpoch: Long,
    val retentionDays: Int,
) {
    init {
        require(TeamScopeRef.isSafeId(ref.teamId))
        require(ref.isTeamWide || TeamScopeRef.isSafeId(ref.scopeId))
        require(revision > 0 && keyEpoch >= 0)
        require(retentionDays in 1..365)
    }
}

/** Transport envelope. The owner signs the exact ciphertext and its outer routing metadata. */
data class SignedRecordingPolicy(
    val revision: Long,
    val keyEpoch: Long,
    val retentionDays: Int,
    val ciphertext: ByteArray,
    val signature: ByteArray,
)

/** Locally approved signer; server role metadata cannot replace this authority. */
@Serializable
data class RecordingPolicyAuthority(val accountId: String, val signingKey: String) {
    fun publicKey(): ByteArray = signingKey.decodeBase64()?.toByteArray()
        ?.also { require(it.size == 32) } ?: error("invalid authority key")
    val fingerprint: String get() = publicKey().toByteString().sha256().hex()
}

@Serializable
data class CachedRecordingPolicy(
    val revision: Long,
    val keyEpoch: Long,
    val retentionDays: Int,
    val ciphertext: String,
    val signature: String,
    val mode: RecordingMode? = null,
    val authority: RecordingPolicyAuthority? = null,
) {
    fun envelope(): SignedRecordingPolicy = SignedRecordingPolicy(
        revision, keyEpoch, retentionDays,
        ciphertext.decodeBase64()?.toByteArray() ?: error("invalid cached policy"),
        signature.decodeBase64()?.toByteArray() ?: error("invalid cached signature"),
    )
}

fun SignedRecordingPolicy.cached(mode: RecordingMode? = null, authority: RecordingPolicyAuthority? = null) = CachedRecordingPolicy(
    revision, keyEpoch, retentionDays, ciphertext.toByteString().base64(), signature.toByteString().base64(), mode, authority,
)

open class RecordingPolicyException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Only encrypted in the outbox; sufficient to rebuild an interrupted cast after restart. */
@Serializable
internal data class RecordingDraft(
    val columns: Int,
    val rows: Int,
    val title: String?,
    val startedAtSeconds: Long,
)

internal data class RecordingJournalFeed(val elapsedMillis: Long, val bytes: ByteArray)

/** The immutable fields authenticated with every chunk and the wrapped per-recording key. */
data class RecordingIdentity(
    val ref: TeamScopeRef,
    val recordingId: String,
    val hostId: String,
    val actorId: String,
    val keyEpoch: Long,
) {
    init {
        require(TeamScopeRef.isSafeId(ref.teamId))
        require(ref.isTeamWide || TeamScopeRef.isSafeId(ref.scopeId))
        require(TeamScopeRef.isSafeId(recordingId) && TeamScopeRef.isSafeId(hostId))
        require(actorId.isNotBlank() && actorId.length <= 320 && keyEpoch >= 0)
    }
}

/** Encrypted metadata binds the ordered chunk hashes to this host, actor and cast geometry. */
@Serializable
data class RecordingManifest(
    val ref: TeamScopeRef,
    val recordingId: String,
    val hostId: String,
    val actorId: String,
    val keyEpoch: Long,
    val columns: Int,
    val rows: Int,
    val title: String?,
    val durationSec: Long,
    val chunkHashes: List<String>,
) {
    fun identity() = RecordingIdentity(ref, recordingId, hostId, actorId, keyEpoch)
}

/** Domain-separated, length-prefixed AAD has no ambiguous concatenations or JSON ordering. */
private fun recordingAad(kind: String, vararg fields: String): ByteArray = buildString {
    append("skerry.team-recording.v1:").append(kind)
    for (field in fields) append(':').append(field.length).append(':').append(field)
}.encodeToByteArray()

private fun RecordingIdentity.aad(kind: String, vararg fields: String): ByteArray = recordingAad(
    kind, ref.teamId, ref.scopeId, recordingId, hostId, actorId, keyEpoch.toString(), *fields,
)

/** XChaCha20-Poly1305 envelopes for recordings, using the existing cross-platform vault primitive. */
class TeamRecordingCrypto(private val crypto: VaultCrypto) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    internal fun sealDraft(key: DataKey, identity: RecordingIdentity, draft: RecordingDraft): ByteArray {
        val bytes = json.encodeToString(draft).encodeToByteArray()
        return try { crypto.seal(key, bytes, identity.aad("draft")) } finally { bytes.fill(0) }
    }

    internal fun openDraft(key: DataKey, identity: RecordingIdentity, envelope: ByteArray): RecordingDraft {
        val bytes = crypto.open(key, envelope, identity.aad("draft")) ?: error("recording draft authentication failed")
        return try {
            json.decodeFromString<RecordingDraft>(bytes.decodeToString()).also {
                require(it.columns in 1..1000 && it.rows in 1..500 && (it.title?.length ?: 0) <= 256)
            }
        } finally { bytes.fill(0) }
    }

    internal fun sealJournal(key: DataKey, identity: RecordingIdentity, index: Int,
        elapsedMillis: Long, bytes: ByteArray): ByteArray {
        require(index >= 0 && elapsedMillis >= 0 && bytes.size <= MAX_CHUNK_PLAINTEXT - 8)
        val clear = ByteArray(bytes.size + 8)
        for (offset in 0..7) clear[offset] = (elapsedMillis ushr (56 - offset * 8)).toByte()
        bytes.copyInto(clear, 8)
        return try { crypto.seal(key, clear, identity.aad("journal", index.toString())) }
        finally { clear.fill(0) }
    }

    internal fun openJournal(key: DataKey, identity: RecordingIdentity, index: Int,
        envelope: ByteArray): RecordingJournalFeed {
        require(index >= 0 && envelope.size in 48..MAX_CHUNK_CIPHERTEXT)
        val clear = crypto.open(key, envelope, identity.aad("journal", index.toString()))
            ?: error("recording journal authentication failed")
        return try {
            require(clear.size >= 8)
            var elapsed = 0L
            for (offset in 0..7) elapsed = (elapsed shl 8) or (clear[offset].toLong() and 255)
            require(elapsed >= 0)
            RecordingJournalFeed(elapsed, clear.copyOfRange(8, clear.size))
        } finally { clear.fill(0) }
    }

    fun wrapKey(spaceKey: DataKey, recordingKey: DataKey, identity: RecordingIdentity): ByteArray =
        crypto.seal(spaceKey, recordingKey.bytes, identity.aad("key"))

    fun openKey(spaceKey: DataKey, envelope: ByteArray, identity: RecordingIdentity): DataKey? {
        val bytes = crypto.open(spaceKey, envelope, identity.aad("key")) ?: return null
        if (bytes.size == 32) return DataKey(bytes)
        bytes.fill(0)
        return null
    }

    fun sealChunk(key: DataKey, identity: RecordingIdentity, index: Int, plaintext: ByteArray): ByteArray {
        require(index in 0 until MAX_CHUNKS && plaintext.size <= MAX_CHUNK_PLAINTEXT)
        // The final count is unknown while the session streams. The encrypted manifest binds count
        // and ordered ciphertext hashes after finalization; each chunk binds its own slot here.
        return crypto.seal(key, plaintext, identity.aad("chunk", index.toString()))
    }

    fun openChunk(key: DataKey, identity: RecordingIdentity, index: Int, ciphertext: ByteArray): ByteArray? {
        if (index !in 0 until MAX_CHUNKS || ciphertext.size > MAX_CHUNK_CIPHERTEXT) return null
        return crypto.open(key, ciphertext, identity.aad("chunk", index.toString()))
    }

    fun sealManifest(key: DataKey, manifest: RecordingManifest): ByteArray {
        require(manifest.columns in 1..1000 && manifest.rows in 1..500)
        require(manifest.chunkHashes.size in 1..MAX_CHUNKS)
        val bytes = json.encodeToString(manifest).encodeToByteArray()
        return try { crypto.seal(key, bytes, manifest.identity().aad("manifest")) } finally { bytes.fill(0) }
    }

    fun openManifest(key: DataKey, identity: RecordingIdentity, ciphertext: ByteArray): RecordingManifest? {
        if (ciphertext.size !in 40..65_536) return null
        val bytes = crypto.open(key, ciphertext, identity.aad("manifest")) ?: return null
        return try {
            json.decodeFromString<RecordingManifest>(bytes.decodeToString()).takeIf {
                it.identity() == identity && it.columns in 1..1000 && it.rows in 1..500 &&
                    it.chunkHashes.size in 1..MAX_CHUNKS
            }
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    fun sealPolicy(spaceKey: DataKey, owner: SigningKeyPair, policy: RecordingPolicy): SignedRecordingPolicy {
        val aad = policyAad(policy.ref, policy.revision, policy.keyEpoch, policy.retentionDays)
        val bytes = json.encodeToString(policy).encodeToByteArray()
        val ciphertext = try { crypto.seal(spaceKey, bytes, aad) } finally { bytes.fill(0) }
        val signature = crypto.sign(owner, aad + ciphertext)
        return SignedRecordingPolicy(policy.revision, policy.keyEpoch, policy.retentionDays, ciphertext, signature)
    }

    /** [minimumRevision] must be persisted outside the server's rollback control. */
    fun openPolicy(
        spaceKey: DataKey,
        ownerPublicKey: ByteArray,
        envelope: SignedRecordingPolicy,
        ref: TeamScopeRef,
        minimumRevision: Long,
    ): RecordingPolicy {
        if (envelope.revision < minimumRevision) throw RecordingPolicyException("policy rollback")
        val aad = policyAad(ref, envelope.revision, envelope.keyEpoch, envelope.retentionDays)
        if (!crypto.verifySignature(ownerPublicKey, aad + envelope.ciphertext, envelope.signature)) {
            throw RecordingPolicyException("invalid owner signature")
        }
        val bytes = crypto.open(spaceKey, envelope.ciphertext, aad)
            ?: throw RecordingPolicyException("invalid policy ciphertext")
        val policy = try {
            json.decodeFromString<RecordingPolicy>(bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw RecordingPolicyException("invalid policy payload", e)
        } finally {
            bytes.fill(0)
        }
        if (policy.ref != ref || policy.revision != envelope.revision) {
            throw RecordingPolicyException("policy metadata mismatch")
        }
        if (policy.keyEpoch != envelope.keyEpoch || policy.retentionDays != envelope.retentionDays) {
            throw RecordingPolicyException("policy metadata mismatch")
        }
        return policy
    }

    private fun policyAad(ref: TeamScopeRef, revision: Long, epoch: Long, retention: Int): ByteArray =
        recordingAad("policy", ref.teamId, ref.scopeId, revision.toString(), epoch.toString(), retention.toString())

    companion object {
        const val MAX_CHUNK_PLAINTEXT = 2 * 1024 * 1024 - 40
        const val MAX_CHUNK_CIPHERTEXT = 2 * 1024 * 1024
        const val MAX_CHUNKS = 128
    }
}
