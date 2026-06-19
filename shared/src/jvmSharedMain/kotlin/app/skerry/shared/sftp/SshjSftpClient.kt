package app.skerry.shared.sftp

import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException

/**
 * Desktop-реализация [SftpClient] поверх sshj `SFTPClient` (один SFTP-канал на экземпляр).
 * Каждая операция уходит на [Dispatchers.IO]: API sshj блокирующее. Ошибки протокола
 * (`SFTPException`) и обрывы (`IOException`) заворачиваются в [SftpException]; единственное
 * исключение — [stat], где «нет такого файла» — это `null`, а не ошибка.
 *
 * [maxReadBytes] — потолок для [read] по **заявленному** размеру файла: защита от OOM при
 * случайном открытии многогигабайтного файла. Спецфайлы с нулевым заявленным размером
 * (`/dev/zero` и т.п.) этим не покрыты — для них нужен потоковый лимит, он придёт со
 * стримингом больших файлов (следующий шаг).
 */
internal class SshjSftpClient(
    private val sftp: SFTPClient,
    private val maxReadBytes: Long = DEFAULT_MAX_READ_BYTES,
) : SftpClient {

    private val closed = AtomicBoolean(false)

    override suspend fun list(path: String): List<SftpEntry> = io("Не удалось прочитать каталог $path") {
        // sshj сам отсеивает . и .. из листинга.
        sftp.ls(path).map { it.toEntry() }
    }

    override suspend fun stat(path: String): SftpEntry? = withContext(Dispatchers.IO) {
        if (closed.get()) throw SftpException("SFTP-канал закрыт")
        try {
            // lstat (не stat): не идём по симлинку — атрибуты самого линка, согласованно с list().
            sftp.lstat(path).toEntry(path)
        } catch (e: SFTPException) {
            // «Нет такого файла» — это null. Сервера расходятся: OpenSSH/встроенные могут вернуть
            // NO_SUCH_PATH вместо NO_SUCH_FILE, когда отсутствует компонент пути — мапим оба.
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE ||
                e.statusCode == Response.StatusCode.NO_SUCH_PATH
            ) {
                null
            } else {
                throw SftpException("Не удалось получить метаданные $path", e)
            }
        } catch (e: IOException) {
            throw SftpException("Не удалось получить метаданные $path", e)
        }
    }

    override suspend fun realpath(path: String): String = io("Не удалось разрешить путь $path") {
        sftp.canonicalize(path)
    }

    override suspend fun read(path: String): ByteArray = io("Не удалось прочитать файл $path") {
        sftp.open(path).use { file ->
            val size = file.length()
            if (size > maxReadBytes) {
                throw SftpException("Файл $path слишком большой для чтения целиком: $size Б (лимит $maxReadBytes Б)")
            }
            file.RemoteFileInputStream().use { it.readBytes() }
        }
    }

    override suspend fun write(path: String, data: ByteArray): Unit = io("Не удалось записать файл $path") {
        sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            file.RemoteFileOutputStream().use { it.write(data) }
        }
    }

    override suspend fun mkdir(path: String): Unit = io("Не удалось создать каталог $path") {
        sftp.mkdir(path)
    }

    override suspend fun remove(path: String): Unit = io("Не удалось удалить файл $path") {
        sftp.rm(path)
    }

    override suspend fun rmdir(path: String): Unit = io("Не удалось удалить каталог $path") {
        sftp.rmdir(path)
    }

    override suspend fun rename(from: String, to: String): Unit = io("Не удалось переименовать $from → $to") {
        sftp.rename(from, to)
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        // Идемпотентно: повторный close() не должен повторно дёргать канал.
        if (!closed.compareAndSet(false, true)) return@withContext
        runCatching { sftp.close() }
        Unit
    }

    /**
     * Выполнить блокирующую sshj-операцию, завернув её ошибки в [SftpException]. Сначала
     * отсекает использование после [close] — иначе sshj бросил бы невнятный IOException движка.
     */
    private inline fun <T> ioBody(message: String, block: () -> T): T {
        if (closed.get()) throw SftpException("SFTP-канал закрыт")
        return try {
            block()
        } catch (e: IOException) {
            throw SftpException(message, e)
        }
    }

    private suspend inline fun <T> io(message: String, crossinline block: () -> T): T =
        withContext(Dispatchers.IO) { ioBody(message, block) }

    private companion object {
        /** Потолок чтения по умолчанию для [read] (64 MiB) — секреты/конфиги MVP заведомо меньше. */
        const val DEFAULT_MAX_READ_BYTES = 64L * 1024 * 1024
    }
}

/** Запись листинга sshj → [SftpEntry] (имя и путь приходят из ответа сервера). */
private fun RemoteResourceInfo.toEntry(): SftpEntry =
    attributes.toEntry(name = name, path = path)

/** Атрибуты одного объекта → [SftpEntry]; имя берём из хвоста [path]. */
private fun FileAttributes.toEntry(path: String): SftpEntry =
    toEntry(name = path.substringAfterLast('/').ifEmpty { path }, path = path)

private fun FileAttributes.toEntry(name: String, path: String): SftpEntry =
    SftpEntry(
        name = name,
        path = path,
        type = type.toEntryType(),
        size = size,
        modifiedEpochSeconds = mtime,
        // Только биты прав (без бит типа файла) — для UI прав доступа.
        permissions = mode.permissionsMask,
    )

private fun FileMode.Type.toEntryType(): SftpEntryType = when (this) {
    FileMode.Type.REGULAR -> SftpEntryType.File
    FileMode.Type.DIRECTORY -> SftpEntryType.Directory
    FileMode.Type.SYMLINK -> SftpEntryType.Symlink
    else -> SftpEntryType.Other
}
