package app.skerry.ui.sftp

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpEntry
import app.skerry.shared.sftp.SftpEntryType
import app.skerry.shared.sftp.SftpException
import app.skerry.shared.sftp.SftpProgress
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [SftpClient] for panel controller tests: models a directory/file tree with POSIX
 * path semantics (`.`/`..` normalization, `/` separator). Covers the operations the panel calls
 * ([list]/[realpath]/[stat]/[mkdir]/[rmdir]/[remove]/[rename]); [read]/[write] are stubs (the
 * directory panel controller doesn't use download/upload). Not thread-safe; tests run it on a
 * single test dispatcher.
 *
 * Seeded via [seedDir]/[seedFile] after construction; the start directory [startDir] exists
 * immediately (init creates it and its ancestors), and [realpath] of `.` resolves to it.
 */
class FakeSftpClient(val startDir: String = "/home/skerry") : SftpClient {

    /** Contents of each existing directory: path -> (name -> entry). */
    private val children = mutableMapOf<String, MutableMap<String, SftpEntry>>("/" to mutableMapOf())

    init {
        seedDir(startDir)
    }

    /** Create directory [path] and any missing ancestors (like `mkdir -p`). */
    fun seedDir(path: String) {
        val norm = realpathSync(path)
        if (norm == "/" || norm in children) return
        seedDir(parentOf(norm))
        register(SftpEntry(nameOf(norm), norm, SftpEntryType.Directory, 0, 0, 0b111_101_101))
        children[norm] = mutableMapOf()
    }

    /** Create file [path] (ancestors must already exist; seed them with [seedDir] first). */
    fun seedFile(path: String, size: Long = 0, modifiedEpochSeconds: Long = 0) {
        val norm = realpathSync(path)
        register(SftpEntry(nameOf(norm), norm, SftpEntryType.File, size, modifiedEpochSeconds, 0b110_100_100))
    }

    override suspend fun list(path: String): List<SftpEntry> {
        val dir = children[realpathSync(path)] ?: throw SftpException("No directory $path")
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

    /** Last download call: (remotePath, localPath). For controller test assertions. */
    var lastDownload: Pair<String, String>? = null
        private set

    /** All download calls in arrival order: (remotePath, localPath). For recursion checks. */
    val downloads = mutableListOf<Pair<String, String>>()

    /** Last upload call: (localPath, remotePath). */
    var lastUpload: Pair<String, String>? = null
        private set

    /** All progress events from the last transfer: (transferred, total). */
    val progressEvents = mutableListOf<Pair<Long, Long>>()

    /** Size "transferred" on upload (the fake can't see the local file). */
    var uploadSize: Long = 0

    /**
     * When set, the transfer pauses after the first (halfway) progress callback until this
     * Deferred completes, letting a test observe the controller's intermediate state.
     */
    var transferGate: CompletableDeferred<Unit>? = null

    override suspend fun download(remotePath: String, localPath: String, onProgress: SftpProgress) {
        val norm = realpathSync(remotePath)
        val entry = children[parentOf(norm)]?.get(nameOf(norm)) ?: throw SftpException("No file $remotePath")
        if (entry.type == SftpEntryType.Directory) throw SftpException("$remotePath is a directory, not a file")
        lastDownload = remotePath to localPath
        downloads += remotePath to localPath
        emitProgress(onProgress, entry.size)
    }

    /** When set, the next [upload] throws [SftpException] with this text (tests failure cleanup). */
    var uploadError: String? = null

    override suspend fun upload(localPath: String, remotePath: String, onProgress: SftpProgress) {
        uploadError?.let { throw SftpException(it) }
        val norm = realpathSync(remotePath)
        if (children[parentOf(norm)] == null) throw SftpException("No parent for $remotePath")
        lastUpload = localPath to remotePath
        emitProgress(onProgress, uploadSize)
        seedFile(remotePath, size = uploadSize)
    }

    /** Simulate a streamed transfer: half -> (optional gate) -> full; total=0 emits one zero callback. */
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
        val parent = children[parentOf(norm)] ?: throw SftpException("No parent for $path")
        if (nameOf(norm) in parent) throw SftpException("Path taken: $path")
        register(SftpEntry(nameOf(norm), norm, SftpEntryType.Directory, 0, 0, 0b111_101_101))
        children[norm] = mutableMapOf()
    }

    override suspend fun remove(path: String) {
        val norm = realpathSync(path)
        val parent = children[parentOf(norm)] ?: throw SftpException("No parent for $path")
        val entry = parent[nameOf(norm)] ?: throw SftpException("No file $path")
        if (entry.type == SftpEntryType.Directory) throw SftpException("$path is a directory, not a file")
        parent.remove(nameOf(norm))
    }

    override suspend fun rmdir(path: String) {
        val norm = realpathSync(path)
        val dir = children[norm] ?: throw SftpException("No directory $path")
        if (dir.isNotEmpty()) throw SftpException("Directory not empty: $path")
        children.remove(norm)
        children[parentOf(norm)]?.remove(nameOf(norm))
    }

    override suspend fun rename(from: String, to: String) {
        val src = realpathSync(from)
        val dst = realpathSync(to)
        val srcParent = children[parentOf(src)] ?: throw SftpException("No source $from")
        val entry = srcParent.remove(nameOf(src)) ?: throw SftpException("No source $from")
        register(entry.copy(name = nameOf(dst), path = dst))
        if (entry.type == SftpEntryType.Directory) {
            children[dst] = children.remove(src) ?: mutableMapOf()
        }
    }

    override suspend fun close() = Unit

    /** Insert the entry into its parent directory. */
    private fun register(entry: SftpEntry) {
        children.getOrPut(parentOf(entry.path)) { mutableMapOf() }[entry.name] = entry
    }

    /** Non-suspend path normalization, needed from both init/seed and the suspend methods. */
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
