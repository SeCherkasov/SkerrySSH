package app.skerry.shared.sftp

/**
 * SFTP поверх установленной SSH-сессии. Открывается из [app.skerry.shared.ssh.SshConnection.openSftp];
 * платформенная реализация — sshj на desktop ([app.skerry.shared.sftp] desktopMain), мобильные позже.
 *
 * Каркас MVP: каталоги ([list]/[mkdir]/[rmdir]), метаданные ([stat]/[realpath]), целиковое
 * чтение/запись небольших файлов ([read]/[write]) и операции над путями ([rename]/[remove]).
 * Потоковая передача больших файлов с прогрессом и докачкой — отдельный шаг (как у `exec`,
 * где вывод пока читается целиком). Все методы suspend: I/O уводится с вызывающего потока.
 *
 * Пути трактуются сервером (POSIX-семантика, разделитель `/`). Относительные пути и `~`
 * разворачивает сам сервер; [realpath] канонизирует путь (в т.ч. стартовый каталог по `.`).
 */
interface SftpClient {

    /**
     * Содержимое каталога [path] без `.` и `..`. Порядок — как отдаёт сервер (не сортируется).
     * @throws SftpException путь не существует, это не каталог или нет прав
     */
    suspend fun list(path: String): List<SftpEntry>

    /** Метаданные одного объекта или `null`, если по [path] ничего нет. */
    suspend fun stat(path: String): SftpEntry?

    /**
     * Канонический абсолютный путь для [path] (разворачивает `.`, `..`, относительные пути).
     * Передать `.` — получить стартовый рабочий каталог сессии.
     * @throws SftpException путь не разрешается
     */
    suspend fun realpath(path: String): String

    /**
     * Прочитать файл [path] целиком в память (MVP — небольшие файлы).
     * @throws SftpException путь не существует, это каталог или нет прав
     */
    suspend fun read(path: String): ByteArray

    /**
     * Записать [data] в файл [path], создав или перезаписав его (truncate). Каталог-родитель
     * должен существовать.
     * @throws SftpException нет прав или родителя, либо [path] — каталог
     */
    suspend fun write(path: String, data: ByteArray)

    /**
     * Создать каталог [path]. Родитель должен существовать (без `-p`).
     * @throws SftpException путь занят или нет прав
     */
    suspend fun mkdir(path: String)

    /**
     * Удалить файл (не каталог) [path].
     * @throws SftpException путь не существует, это каталог или нет прав
     */
    suspend fun remove(path: String)

    /**
     * Удалить пустой каталог [path].
     * @throws SftpException каталог не пуст, не существует или нет прав
     */
    suspend fun rmdir(path: String)

    /**
     * Переименовать/переместить [from] в [to] в пределах сервера.
     * @throws SftpException источника нет, цель занята или нет прав
     */
    suspend fun rename(from: String, to: String)

    /** Закрыть SFTP-сессию (канал). Идемпотентно. SSH-соединение остаётся открытым. */
    suspend fun close()
}

/** Тип объекта в SFTP-листинге; «прочее» — устройства, сокеты, FIFO и т.п. */
enum class SftpEntryType { File, Directory, Symlink, Other }

/**
 * Метаданные объекта в SFTP. [path] — путь, переданный/разрешённый при листинге; [size] в байтах;
 * [modifiedEpochSeconds] — mtime (Unix-секунды); [permissions] — POSIX mode bits (как `st_mode &
 * 0o7777`), для UI прав доступа. Для симлинка атрибуты — самого линка (без перехода по цели).
 */
data class SftpEntry(
    val name: String,
    val path: String,
    val type: SftpEntryType,
    val size: Long,
    val modifiedEpochSeconds: Long,
    val permissions: Int,
)

/** Ошибка SFTP-операции: нет пути/прав, неверный тип объекта или обрыв канала. */
class SftpException(message: String, cause: Throwable? = null) : Exception(message, cause)
