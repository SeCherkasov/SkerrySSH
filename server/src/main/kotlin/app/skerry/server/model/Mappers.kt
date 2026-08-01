package app.skerry.server.model

import app.skerry.server.db.AdminRepository
import app.skerry.server.db.IncomingRecord
import app.skerry.server.db.StoredRecord
import app.skerry.sync.wire.RecordDto
import io.ktor.server.plugins.BadRequestException
import java.util.Base64

fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

/** Invalid base64 is a client error (400): Ktor responds 400 to BadRequestException. */
fun String.unb64(): ByteArray = try {
    Base64.getDecoder().decode(this)
} catch (e: IllegalArgumentException) {
    throw BadRequestException("invalid base64 payload", e)
}

fun StoredRecord.toDto() = RecordDto(id, type, version, updatedAt, deviceId, deleted, blob.b64())

/**
 * One mapping for both zones that serve envelopes — the operator's account inspector and the
 * owner's own Storage section. Two copies would be two places for a blob field to appear in.
 */
fun AdminRepository.RecordEnvelope.toDto() =
    RecordEnvelopeDto(id, type, version, updatedAt, deviceId, deleted, blobBytes, serverSeq, previewHex)

fun RecordDto.toIncoming() = IncomingRecord(id, type, version, updatedAt, deviceId, deleted, blob.unb64())
