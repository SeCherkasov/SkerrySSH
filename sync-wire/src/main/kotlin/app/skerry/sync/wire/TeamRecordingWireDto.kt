package app.skerry.sync.wire

import kotlinx.serialization.Serializable

/** Ciphertext is base64 only in small JSON metadata. Chunks travel as octet-stream bodies. */
@Serializable
data class RecordingPolicyDto(
    val revision: Long,
    val keyEpoch: Long,
    val retentionDays: Int,
    val ciphertext: String,
    val signature: String,
)

@Serializable
data class RecordingChunkDto(val index: Int, val length: Int, val sha256: String)

@Serializable
data class RecordingWrapRequest(val nextEpoch: Long, val wrappedKey: String)

@Serializable
data class RecordingReserveRequest(
    val recordingId: String,
    val hostId: String,
    val keyEpoch: Long,
    val wrappedKey: String,
    val manifest: String,
    val chunks: List<RecordingChunkDto>,
    val durationSec: Long,
    val wrapEpoch: Long? = null,
)

@Serializable
data class RecordingMetadataDto(
    val recordingId: String,
    val teamId: String,
    val scopeId: String,
    val hostId: String,
    val actorId: String,
    val keyEpoch: Long,
    val wrappedKey: String,
    val manifest: String,
    val chunkCount: Int,
    val durationSec: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val wrapEpoch: Long,
    val stagedWrappedKey: String? = null,
    val stagedWrapEpoch: Long? = null,
)

@Serializable
data class RecordingListResponse(val recordings: List<RecordingMetadataDto>)

@Serializable
data class RecordingBulkDeleteRequest(val recordingIds: List<String>)

@Serializable
data class RecordingBulkDeleteResponse(val deleted: Int)
