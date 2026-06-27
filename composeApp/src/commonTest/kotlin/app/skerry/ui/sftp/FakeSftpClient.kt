package app.skerry.ui.sftp

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpException
import app.skerry.shared.sftp.SftpProgress
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [SftpClient] для тестов UI-контроллера: моделирует дерево каталогов/файлов и
 * POSIX-семантику путей (нормализация `.`/`..`, разделитель `/`). Покрывает операции, которые
 * дёргает панель ([list]/[realpath]/[stat]/[mkdir]/[rmdir]/[remove]/[rename]); [read]/[write] —
 * заглушки (download/upload в контроллере панели каталога не участвуют). Не потокобезопасен —
 * тесты гоняют его на одном тестовом диспетчере.
 *
 * Засеивается [seedDir]/[seedFile] после конструктора; стартовый каталог [startDir] существует
 * сразу (его и его предков создаёт init), [realpath] для `.` отдаёт именно его.
 */
class FakeSftpClient(val startDir: String = "/home/skerry") : SftpClient {

    /** Содержимое каждого существующего каталога: путь → (имя → запись). */
    private val children = mutableMapOf<String, MutableMap<String, SftpEntry>>("/" to mutableMapOf())

    init {
        seedDir(startDir)
    }

    /** Создать каталог [path] и всех недостающих предков (как `mkdir -p`). */
    fun seedDir(path: String) {
        val norm = realpathSync(path)
        if (norm == "/" || norm in children) return
        seedDir(parentOf(norm))
        register(SftpEntry(nameOf(norm), norm, SftpEntryType.Directory, 0, 0, 0b111_101_101))
        children[norm] = mutableMapOf()
    }

    /** Создать файл [path] (предки должны существовать — засей их [seedDir] заранее). */
    fun seedFile(path: String, size: Long = 0, modifiedEpochSeconds: Long = 0) {
        val norm = realpathSync(path)
        register(SftpEntry(nameOf(norm), norm, SftpEntryType.File, size, modifiedEpochSeconds, 0b110_100_100))
    }

    override suspend fun list(path: String): List<SftpEntry> {
        val dir = children[realpathSync(path)] ?: throw SftpException("Нет каталога $path")
        return dir.values.toList()
    }

    override suspend fun stat(path: String): SftpEntry? {
        val norm = realpathSync(path)
        if (norm == "/") return SftpEntry("/", "/", SftpEntryType.Directory, 0, 0, 0b111_101_101)
        return children[parentOf(norm)]?.get(nameOf(norm))
    }

    override suspend fun realpath(path: String): String = realpathSync(path)

    override suspend fun read(path: String): ByteArray = throw UnsupportedOperationException()

    override suspend fun write(path: String, data: ByteArray): Unit = throw UnsupportedOperationException()

    /** Последний вызов download: (remotePath, localPath). Для ассертов в тестах контроллера. */
    var lastDownload: Pair<String, String>? = null
        private set

    /** Все вызовы download в порядке поступления: (remotePath, localPath). Для проверки рекурсии. */
    val downloads = mutableListOf<Pair<String, String>>()

    /** Последний вызов upload: (localPath, remotePath). */
    var lastUpload: Pair<String, String>? = null
        private set

    /** Все события прогресса последней передачи: (transferred, total). */
    val progressEvents = mutableListOf<Pair<Long, Long>>()

    /** Размер, который «передаётся» при upload (локального файла фейк не видит). */
    var uploadSize: Long = 0

    /**
     * Если выставлен, передача приостанавливается после первого (половинного) колбэка прогресса
     * до завершения этого Deferred — тест успевает наблюдать промежуточное состояние контроллера.
     */
    var transferGate: CompletableDeferred<Unit>? = null

    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) {
        val norm = realpathSync(remotePath)
        val entry = children[parentOf(norm)]?.get(nameOf(norm)) ?: throw SftpException("Нет файла $remotePath")
        if (entry.type == SftpEntryType.Directory) throw SftpException("$remotePath — каталог, не файл")
        lastDownload = remotePath to localPath
        downloads += remotePath to localPath
        emitProgress(onProgress, entry.size)
    }

    /** Если задан, следующий [upload] бросит [SftpException] с этим текстом (тест cleanup при сбое). */
    var uploadError: String? = null

    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) {
        uploadError?.let { throw SftpException(it) }
        val norm = realpathSync(remotePath)
        if (children[parentOf(norm)] == null) throw SftpException("Нет родителя для $remotePath")
        lastUpload = localPath to remotePath
        emitProgress(onProgress, uploadSize)
        seedFile(remotePath, size = uploadSize)
    }

    /** Сымитировать потоковую передачу: половина → (опц. шлюз) → полностью; total=0 → один нулевой колбэк. */
    private suspend fun emitProgress(onProgress: SftpProgress, total: Long) {
        progressEvents.clear()
        if (total > 0) {
            progressEvents += (total / 2) to total
            onProgress.onProgress(total / 2, total)
            transferGate?.await()
        }
        progressEvents += total to total
        onProgress.onProgress(total, total)
    }

    override suspend fun mkdir(path: String) {
        val norm = realpathSync(path)
        val parent = children[parentOf(norm)] ?: throw SftpException("Нет родителя для $path")
        if (nameOf(norm) in parent) throw SftpException("Путь занят: $path")
        register(SftpEntry(nameOf(norm), norm, SftpEntryType.Directory, 0, 0, 0b111_101_101))
        children[norm] = mutableMapOf()
    }

    override suspend fun remove(path: String) {
        val norm = realpathSync(path)
        val parent = children[parentOf(norm)] ?: throw SftpException("Нет родителя для $path")
        val entry = parent[nameOf(norm)] ?: throw SftpException("Нет файла $path")
        if (entry.type == SftpEntryType.Directory) throw SftpException("$path — каталог, не файл")
        parent.remove(nameOf(norm))
    }

    override suspend fun rmdir(path: String) {
        val norm = realpathSync(path)
        val dir = children[norm] ?: throw SftpException("Нет каталога $path")
        if (dir.isNotEmpty()) throw SftpException("Каталог не пуст: $path")
        children.remove(norm)
        children[parentOf(norm)]?.remove(nameOf(norm))
    }

    override suspend fun rename(from: String, to: String) {
        val src = realpathSync(from)
        val dst = realpathSync(to)
        val srcParent = children[parentOf(src)] ?: throw SftpException("Нет источника $from")
        val entry = srcParent.remove(nameOf(src)) ?: throw SftpException("Нет источника $from")
        register(entry.copy(name = nameOf(dst), path = dst))
        if (entry.type == SftpEntryType.Directory) {
            children[dst] = children.remove(src) ?: mutableMapOf()
        }
    }

    override suspend fun close() = Unit

    /** Вписать запись в её родительский каталог. */
    private fun register(entry: SftpEntry) {
        children.getOrPut(parentOf(entry.path)) { mutableMapOf() }[entry.name] = entry
    }

    /** Нормализация пути без suspend — нужна и в init/seed, и в suspend-методах. */
    private fun realpathSync(path: String): String {
        val segments = if (path.startsWith("/")) {
            mutableListOf()
        } else {
            startDir.split('/').filter { it.isNotEmpty() }.toMutableList()
        }
        for (seg in path.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments.add(seg)
        }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    private fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) "/" else trimmed.substring(0, cut)
    }

    private fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')
}
