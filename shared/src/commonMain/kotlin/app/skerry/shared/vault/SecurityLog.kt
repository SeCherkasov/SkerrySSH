package app.skerry.shared.vault

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Type of security event. Fixed set of what the app can honestly track: master password,
 * biometrics, and device-pairing lifecycle. UI labels are localized separately; only stable
 * identifiers live here.
 */
enum class SecurityEventType {
    /** Vault created (initial master password setup) — baseline for "last password change". */
    VaultCreated,

    /** Master password changed ([Vault.changePassword]). */
    MasterPasswordChanged,

    /** Biometric unlock enabled. */
    BiometricEnabled,

    /** Biometric unlock disabled. */
    BiometricDisabled,

    /** Successful biometric unlock. */
    UnlockedBiometric,

    /** New device paired (quick pairing) — [detail] carries the device name. */
    DevicePaired,
}

/**
 * One log entry: type, ISO-8601 timestamp ([at], as in [Vault] — a string from injectable clock)
 * and an optional detail ([detail], e.g. the paired device name).
 */
@Serializable
data class SecurityEvent(
    val type: SecurityEventType,
    val at: String,
    val detail: String? = null,
)

/**
 * Local security event log. Deliberately not synced across devices — it's a per-device audit
 * trail (like a system login log), not shared account data.
 */
interface SecurityLog {
    /** Record an event at the current time (clock is internal to the implementation). */
    fun record(type: SecurityEventType, detail: String? = null)

    /** Most recent events, newest first, at most [limit]. */
    fun recent(limit: Int = 20): List<SecurityEvent>

    /**
     * Timestamp of the last master password change: the newest [SecurityEventType.VaultCreated]
     * or [SecurityEventType.MasterPasswordChanged] event. `null` if no such event exists yet
     * (e.g. vault created before the log existed).
     */
    fun lastPasswordChangeAt(): String?

    /** Clear the log (vault reset / factory reset). */
    fun clear()
}

/**
 * [SecurityLog] over okio [FileSystem]: a JSON array of events in one file, shared code for
 * desktop and Android (like [FileVault]). Stored in chronological order; oldest entries are
 * evicted once [max] is exceeded. A corrupt or missing file reads as an empty log. Writes are
 * atomic via a temp file.
 *
 * Mutations ([record]/[clear]) are read-modify-write, so they're serialized with a
 * multiplatform [SynchronizedObject] (like [FileVault]): the log is called from the UI coroutine
 * and potentially from background pairing/sync, so unsynchronized concurrent writes could lose
 * an event.
 *
 * [harden] is a platform hook that sets private permissions (0600 on POSIX) on the finished
 * file, since audit metadata (paired device names, password-change timestamps) shouldn't be
 * world-readable under a shared home directory. No-op by default (tests on
 * [okio.fakefilesystem]); JVM call sites pass `PrivateConfig.harden`. Called on the temp file
 * before [FileSystem.atomicMove] so the target never has a window with umask permissions.
 */
class FileSecurityLog(
    private val path: Path,
    private val fileSystem: FileSystem,
    private val max: Int = 50,
    private val harden: (Path) -> Unit = {},
    private val clock: () -> String,
) : SecurityLog {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()

    override fun record(type: SecurityEventType, detail: String?): Unit = synchronized(lock) {
        val events = (read() + SecurityEvent(type, clock(), detail)).takeLast(max)
        write(events)
    }

    override fun recent(limit: Int): List<SecurityEvent> = synchronized(lock) {
        read().asReversed().take(limit)
    }

    override fun lastPasswordChangeAt(): String? = synchronized(lock) {
        read().lastOrNull {
            it.type == SecurityEventType.VaultCreated || it.type == SecurityEventType.MasterPasswordChanged
        }?.at
    }

    override fun clear(): Unit = synchronized(lock) {
        if (fileSystem.exists(path)) fileSystem.delete(path)
    }

    /** Read the log in chronological order; any error (missing file/corrupt JSON) → empty. */
    private fun read(): List<SecurityEvent> = runCatching {
        if (!fileSystem.exists(path)) return emptyList()
        val text = fileSystem.read(path) { readUtf8() }
        json.decodeFromString<List<SecurityEvent>>(text)
    }.getOrDefault(emptyList())

    // Atomic write + harden on tmp before move — see [atomicWriteUtf8].
    private fun write(events: List<SecurityEvent>) {
        atomicWriteUtf8(fileSystem, path, json.encodeToString(events), harden)
    }
}
