package app.skerry.ui.sftp

/**
 * Android-заглушка выбора файла: возвращает `null` (передача недоступна) до интеграции Storage
 * Access Framework. Нужна для паритета компиляции `expect`/`actual`; полноценный SAF-пикер с
 * `ActivityResultContracts` — отдельный шаг мобильного паритета SFTP.
 */
actual suspend fun pickDownloadTarget(suggestedName: String): String? = null

actual suspend fun pickUploadSource(): String? = null
