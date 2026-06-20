package app.skerry.ui.sftp

/**
 * Выбор локального места для SFTP-передачи нативным платформенным диалогом. Возвращает не «голый»
 * путь, а handle ([DownloadTarget]/[UploadSource]) — потому что на Android выбор даёт `content://`
 * Uri, а не путь файловой системы, который понимает sshj. Поэтому передача всегда идёт через
 * промежуточный (staging) путь, а пост-обработка инкапсулирована в handle:
 * - desktop: staging = реальный путь, финализация/очистка — no-op;
 * - android: staging = временный файл в кэше, финализация копирует его в выбранный Uri.
 *
 * Возвращает `null`, если пользователь отменил выбор или платформа его не поддерживает.
 */
expect suspend fun pickDownloadTarget(suggestedName: String): DownloadTarget?

expect suspend fun pickUploadSource(): UploadSource?

/**
 * Локальная цель скачивания. SFTP-клиент пишет байты в [stagingPath]; по успешном завершении
 * вызывается [finalize] (на Android — копирование staging→Uri), при ошибке/отмене — [discard]
 * (очистка staging). Оркеструет это [SftpController].
 */
interface DownloadTarget {
    /** Имя для UI (баннер передачи). */
    val displayName: String

    /** Путь файловой системы, куда SFTP-клиент пишет байты. */
    val stagingPath: String

    /** Перенести staging в реальную цель. Вызывается ровно один раз при успешной передаче. */
    suspend fun finalize()

    /** Освободить staging без переноса (ошибка/отмена передачи или сбой [finalize]). */
    suspend fun discard()
}

/**
 * Локальный источник загрузки. К моменту возврата из [pickUploadSource] байты уже доступны по
 * [stagingPath] (на Android — скопированы из Uri во временный файл). SFTP-клиент читает их оттуда;
 * по завершении (успех или ошибка) [SftpController] вызывает [cleanup].
 */
interface UploadSource {
    /** Имя файла на удалённой стороне (без пути). */
    val name: String

    /** Путь файловой системы, откуда SFTP-клиент читает байты. */
    val stagingPath: String

    /** Освободить staging. Вызывается ровно один раз после завершения передачи. */
    suspend fun cleanup()
}
