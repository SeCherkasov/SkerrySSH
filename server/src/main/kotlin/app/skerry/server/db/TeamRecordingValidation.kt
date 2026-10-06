package app.skerry.server.db

import org.jetbrains.exposed.v1.core.ResultRow

internal fun validRecordingPolicy(policy: StoredRecordingPolicy, epoch: Long): Boolean {
    if (policy.keyEpoch != epoch || policy.revision <= 0) return false
    return policy.retentionDays in 1..365 && policy.ciphertext.size in 40..4096 && policy.signature.size == 64
}

internal fun ResultRow.toStoredRecording() = StoredRecording(
    this[TeamRecordings.teamId], this[TeamRecordings.scopeId], this[TeamRecordings.recordingId],
    this[TeamRecordings.hostId], this[TeamRecordings.actorId], this[TeamRecordings.keyEpoch],
    this[TeamRecordings.wrappedKey].bytes, this[TeamRecordings.manifest].bytes,
    this[TeamRecordings.chunkCount], this[TeamRecordings.durationSec], this[TeamRecordings.createdAt],
    this[TeamRecordings.expiresAt], this[TeamRecordings.wrapEpoch],
    this[TeamRecordings.stagedWrappedKey]?.bytes, this[TeamRecordings.stagedWrapEpoch],
)

// Reserve capacity for both current and staged DEK wraps so rotation never bypasses quotas.
internal fun recordingReservedBytes(spec: RecordingReservation): Long =
    spec.chunks.sumOf { it.length.toLong() } + spec.manifest.size + 2 * 256 + TeamRecordingRepository.RECEIPT_BYTES

internal fun validRecordingReservation(spec: RecordingReservation): Boolean {
    if (!validReservationMetadata(spec)) return false
    if (spec.chunks.isEmpty() || spec.chunks.size > TeamRecordingRepository.MAX_CHUNKS) return false
    if (spec.chunks.map { it.index } != spec.chunks.indices.toList()) return false
    return spec.chunks.all { validChunkSpec(it) } &&
        spec.chunks.sumOf { it.length.toLong() } <= TeamRecordingRepository.MAX_RECORDING_BYTES
}

private fun validReservationMetadata(spec: RecordingReservation): Boolean {
    if (spec.durationSec !in 0..(30L * 24 * 3600) || spec.retentionDays !in 1..365) return false
    if (spec.keyEpoch < 0 || spec.wrapEpoch < spec.keyEpoch) return false
    return spec.wrappedKey.size in 40..256 && spec.manifest.size in 40..65_536
}

private fun validChunkSpec(chunk: RecordingChunkSpec): Boolean =
    chunk.length in 40..TeamRecordingRepository.MAX_CHUNK_BYTES && chunk.sha256.matches(Regex("[0-9a-f]{64}"))

