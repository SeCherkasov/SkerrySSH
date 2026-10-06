package app.skerry.shared.team

import app.skerry.shared.sync.SyncSession
import kotlinx.serialization.Serializable

/** Dedicated recording API; never passes cast plaintext over the wire. */
interface TeamRecordingClient {
    suspend fun recordingPolicy(session: SyncSession, ref: TeamScopeRef): SignedRecordingPolicy?
    suspend fun putRecordingPolicy(session: SyncSession, ref: TeamScopeRef, policy: SignedRecordingPolicy)
    suspend fun reserveRecording(session: SyncSession, upload: RecordingUpload)
    suspend fun uploadRecordingChunk(session: SyncSession, ref: TeamScopeRef, recordingId: String, index: Int, ciphertext: ByteArray)
    suspend fun completeRecording(session: SyncSession, ref: TeamScopeRef, recordingId: String)
    suspend fun listRecordings(session: SyncSession, ref: TeamScopeRef, offset: Long = 0): List<RemoteRecording>
    suspend fun recording(session: SyncSession, ref: TeamScopeRef, recordingId: String): RemoteRecording?
    suspend fun downloadRecordingChunk(session: SyncSession, ref: TeamScopeRef, recordingId: String, index: Int): ByteArray
    suspend fun deleteRecording(session: SyncSession, ref: TeamScopeRef, recordingId: String)
    suspend fun deleteRecordings(session: SyncSession, ref: TeamScopeRef, recordingIds: List<String>) {
        for (id in recordingIds) deleteRecording(session, ref, id)
    }
    suspend fun stageRecordingWrap(session: SyncSession, ref: TeamScopeRef, recordingId: String,
        nextEpoch: Long, wrappedKey: ByteArray)
}

@Serializable
data class RecordingChunkHash(val index: Int, val length: Int, val sha256: String)

data class RecordingUpload(
    val identity: RecordingIdentity,
    val wrappedKey: ByteArray,
    val encryptedManifest: ByteArray,
    val chunks: List<RecordingChunkHash>,
    val durationSec: Long,
    val wrapEpoch: Long = identity.keyEpoch,
)

data class RemoteRecording(
    val identity: RecordingIdentity,
    val wrappedKey: ByteArray,
    val encryptedManifest: ByteArray,
    val chunkCount: Int,
    val durationSec: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val wrapEpoch: Long,
    val stagedWrappedKey: ByteArray? = null,
    val stagedWrapEpoch: Long? = null,
)
