package app.skerry.shared.ai.local

import okio.Path

/** События закачки модели ([ModelDownloader.download]) — прогресс для UI и фазы завершения. */
sealed interface ModelDownloadEvent {
    /** Скачано [downloadedBytes] из [totalBytes] (каталожный размер, Content-Length не доверяем). */
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadEvent

    /** Файл скачан целиком, идёт проверка sha256 (секунды на гигабайтном файле). */
    data object Verifying : ModelDownloadEvent

    /** Модель установлена и готова к загрузке в рантайм. */
    data class Completed(val path: Path) : ModelDownloadEvent
}

/**
 * Сбой закачки модели. [Kind.NETWORK] — сеть/HTTP-статус/обрыв, part-файл сохранён для докачки.
 * [Kind.INTEGRITY] — скачанное не совпало с каталогом (sha256/размер): битые данные удалены,
 * докачивать нечего — только заново.
 */
class ModelDownloadException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NETWORK, INTEGRITY }
}
