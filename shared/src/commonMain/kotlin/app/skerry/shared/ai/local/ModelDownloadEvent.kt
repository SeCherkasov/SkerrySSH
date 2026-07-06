package app.skerry.shared.ai.local

import okio.Path

/** Model download events ([ModelDownloader.download]): progress for UI and completion phases. */
sealed interface ModelDownloadEvent {
    /** [downloadedBytes] out of [totalBytes] (catalog size; Content-Length is not trusted). */
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadEvent

    /** File fully downloaded, sha256 verification in progress. */
    data object Verifying : ModelDownloadEvent

    /** Model installed and ready to load into the runtime. */
    data class Completed(val path: Path) : ModelDownloadEvent
}

/**
 * Model download failure. [Kind.NETWORK]: network/HTTP status/disconnect, part file kept for resume.
 * [Kind.INTEGRITY]: downloaded data doesn't match the catalog (sha256/size); corrupt data is deleted,
 * nothing to resume from — must restart.
 */
class ModelDownloadException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NETWORK, INTEGRITY }
}
