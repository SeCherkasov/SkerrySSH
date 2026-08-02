package app.skerry.shared.vault

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * What the app can honestly say about one keychain secret's life: when it was added
 * ([addedAt] — first stamp wins, a later edit is not a second birth), when its material was last
 * replaced ([changedAt] — a rotated password, a re-imported key; a rename is not a rotation), when
 * it last authenticated a connection ([lastUsedAt]) and when it was copied to the clipboard
 * ([copiedAt], oldest first).
 *
 * Timestamps are ISO-8601 strings from the app's clock, as in [SecurityEvent] — the UI turns them
 * into "today 09:14" / "3 days ago" via [securityMoment]. A secret with no entry has never been
 * touched since this log existed; the UI shows "—" rather than inventing a date.
 */
@Serializable
data class CredentialUsage(
    val credentialId: String,
    val addedAt: String? = null,
    val changedAt: String? = null,
    val lastUsedAt: String? = null,
    val copiedAt: List<String> = emptyList(),
)

/**
 * Per-device usage trail for keychain secrets. Deliberately **not** synced and **not** stored in the
 * vault: a copy on this laptop says nothing about the phone, and writing a record on every copy
 * would push a new vault version (and a sync round) for an event that isn't account state.
 *
 * It holds secret ids and timestamps — never labels or material. Ids are already plaintext in the
 * vault file's record metadata, so this file reveals no more than the vault does, but it is still
 * audit metadata: implementations give the file private permissions (see [FileCredentialUsageLog]).
 */
interface CredentialUsageLog {
    /** Usage of one secret, or `null` if nothing was ever recorded for it. */
    fun of(credentialId: String): CredentialUsage?

    /**
     * Everything recorded so far, in one read. Callers hold the result in memory and re-read only
     * after they change it — this is a file, and the UI asks for a secret's dates on every frame it
     * draws the panel.
     */
    fun all(): List<CredentialUsage>

    /**
     * Stamp the moment a secret entered the keychain. Ignored if it already carries one. Returns the
     * stored entry, so a caller keeping an in-memory copy doesn't have to read the file back.
     */
    fun recordAdded(credentialId: String): CredentialUsage

    /** Stamp the moment a secret's material was replaced (rotation), overwriting the previous one. */
    fun recordChanged(credentialId: String): CredentialUsage

    /** Stamp the moment a secret authenticated a connection (overwrites the previous one). */
    fun recordUsed(credentialId: String): CredentialUsage

    /** Append a clipboard copy of the secret. */
    fun recordCopied(credentialId: String): CredentialUsage

    /** Drop everything known about a secret (it was deleted from the keychain). */
    fun forget(credentialId: String)

    /** Drop the whole log (vault reset). */
    fun clear()
}

/**
 * [CredentialUsageLog] over okio [FileSystem]: one JSON array of [CredentialUsage] entries, shared
 * by desktop and Android (like [FileSecurityLog]). A corrupt or missing file reads as an empty log,
 * so a damaged audit trail degrades to "nothing known" instead of blocking the keychain.
 *
 * Only the last [maxCopies] copies of a secret are kept — the panel counts copies inside a window,
 * not since the beginning of time, and an unbounded list would grow with every clipboard action.
 *
 * Mutations are read-modify-write and are serialized with a multiplatform [SynchronizedObject]
 * (like [FileSecurityLog]): copies are recorded from the UI coroutine while a connection being
 * dialled in the background can stamp the same secret as used.
 *
 * [harden] sets private permissions (0600 on POSIX) on the temp file before the atomic move — see
 * [atomicWriteUtf8]. Both platforms pass `PrivateConfig.harden`; the no-op default is for tests on
 * `FakeFileSystem`.
 */
class FileCredentialUsageLog(
    private val path: Path,
    private val fileSystem: FileSystem,
    private val maxCopies: Int = 64,
    private val harden: (Path) -> Unit = {},
    private val clock: () -> String,
) : CredentialUsageLog {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()

    override fun of(credentialId: String): CredentialUsage? = synchronized(lock) {
        read().firstOrNull { it.credentialId == credentialId }
    }

    override fun all(): List<CredentialUsage> = synchronized(lock) { read() }

    override fun recordAdded(credentialId: String): CredentialUsage = update(credentialId) { current ->
        // First stamp wins: re-saving a secret (an edit, an import re-run) must not rewrite its age.
        if (current.addedAt != null) current else current.copy(addedAt = clock())
    }

    override fun recordChanged(credentialId: String): CredentialUsage = update(credentialId) { it.copy(changedAt = clock()) }

    override fun recordUsed(credentialId: String): CredentialUsage = update(credentialId) { it.copy(lastUsedAt = clock()) }

    override fun recordCopied(credentialId: String): CredentialUsage = update(credentialId) {
        it.copy(copiedAt = (it.copiedAt + clock()).takeLast(maxCopies))
    }

    override fun forget(credentialId: String): Unit = synchronized(lock) {
        val entries = read()
        if (entries.none { it.credentialId == credentialId }) return
        write(entries.filterNot { it.credentialId == credentialId })
    }

    override fun clear(): Unit = synchronized(lock) {
        if (fileSystem.exists(path)) fileSystem.delete(path)
    }

    /**
     * Read-modify-write of one secret's entry, creating it if this is the first thing recorded.
     * Returns the stored entry — unchanged when the edit was a no-op (a second "added"), in which
     * case nothing is written.
     */
    private fun update(credentialId: String, edit: (CredentialUsage) -> CredentialUsage): CredentialUsage =
        synchronized(lock) {
            val entries = read()
            val current = entries.firstOrNull { it.credentialId == credentialId } ?: CredentialUsage(credentialId)
            val updated = edit(current)
            if (updated == current && entries.contains(current)) return updated
            write(entries.filterNot { it.credentialId == credentialId } + updated)
            updated
        }

    /** Any error (missing file / corrupt JSON) reads as an empty log. */
    private fun read(): List<CredentialUsage> = runCatching {
        if (!fileSystem.exists(path)) return emptyList()
        json.decodeFromString<List<CredentialUsage>>(fileSystem.read(path) { readUtf8() })
    }.getOrDefault(emptyList())

    private fun write(entries: List<CredentialUsage>) {
        atomicWriteUtf8(fileSystem, path, json.encodeToString(entries), harden)
    }
}
