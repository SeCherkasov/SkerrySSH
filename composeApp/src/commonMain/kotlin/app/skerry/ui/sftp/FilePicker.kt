package app.skerry.ui.sftp

/**
 * Выбор локального файла для SFTP-передачи нативным платформенным диалогом. Desktop — AWT
 * `FileDialog`; Android — заглушка (`null`) до интеграции Storage Access Framework (паритет позже,
 * см. план SFTP). Возвращает абсолютный локальный путь либо `null`, если пользователь отменил выбор
 * или платформа его не поддерживает.
 */
expect suspend fun pickDownloadTarget(suggestedName: String): String?

expect suspend fun pickUploadSource(): String?
