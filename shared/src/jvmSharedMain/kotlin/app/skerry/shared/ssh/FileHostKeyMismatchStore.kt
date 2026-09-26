package app.skerry.shared.ssh

import app.skerry.shared.io.PrivateConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * File-backed [HostKeyMismatchStore]: one line per event, space-separated fields —
 * `host port keyType recordedFp offeredFp observedAt`. At most one record per (host, port, keyType);
 * [record] overwrites the previous one. Malformed and empty lines are ignored. Content is cached in
 * memory on creation; any mutation rewrites the whole file (few records — unresolved warnings, not a log).
 *
 * A file that exists but cannot be read is not an empty one: until a read succeeds, mutations stay in
 * memory instead of replacing the warnings the store never saw, and every call reads the file again;
 * the first read that succeeds writes the merge.
 * [record] runs inside host-key verification, so it does not throw for this — the connection is
 * already refused, and the warning is still shown for this run.
 *
 * Synchronized: [TofuHostKeyVerifier.verify] calls [record] from the sshj IO thread concurrently with
 * reads from the UI controller.
 */
class FileHostKeyMismatchStore(private val path: Path) : HostKeyMismatchStore {

    private val entries = mutableListOf<HostKeyMismatch>()
    private var loaded = false

    // Identities cleared while the file was unread, so the merge does not bring their warnings back.
    private val clearedUnread = mutableListOf<Triple<String, Int, String>>()

    init {
        load()
    }

    @Synchronized
    override fun all(): List<HostKeyMismatch> {
        load()
        return entries.toList()
    }

    @Synchronized
    override fun record(mismatch: HostKeyMismatch) {
        load()
        entries.removeAll { it.sameKeyAs(mismatch.host, mismatch.port, mismatch.keyType) }
        entries += mismatch
        if (loaded) persist()
    }

    @Synchronized
    override fun clear(host: String, port: Int, keyType: String) {
        load()
        val removed = entries.removeAll { it.sameKeyAs(host, port, keyType) }
        if (!loaded) {
            clearedUnread += Triple(host, port, keyType)
        } else if (removed) {
            persist()
        }
    }

    private fun persist() {
        val body = entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n", transform = ::encode)
        PrivateConfig.atomicWrite(path, body.toByteArray())
    }

    private fun encode(m: HostKeyMismatch): String = buildString {
        append(m.host).append(' ').append(m.port).append(' ').append(m.keyType).append(' ')
        append(m.recordedFingerprint).append(' ').append(m.offeredFingerprint)
        // observedAt is the last field; omit when empty, otherwise trim()+split on load would drop the
        // trailing space and the line would fail to parse (5 != 6 fields).
        if (m.observedAt.isNotEmpty()) append(' ').append(m.observedAt)
    }

    /** Reads the file once it can be read, merging under it whatever changed in memory meanwhile. */
    private fun load() {
        if (loaded) return
        // A read failure must not fail the store constructor or a verification; the next call retries.
        val onDisk = runCatching { read() }.getOrElse { return }
        val touched = clearedUnread + entries.map { Triple(it.host, it.port, it.keyType) }
        val merged = onDisk.filterNot { d -> touched.any { (h, p, k) -> d.sameKeyAs(h, p, k) } } + entries
        entries.clear()
        entries += merged
        clearedUnread.clear()
        loaded = true
        // What changed while the file was unread is written now, not with the next change, which may
        // never come. A refused write leaves it in memory, and the next mutation writes it again.
        if (touched.isNotEmpty()) runCatching { persist() }
    }

    private fun read(): List<HostKeyMismatch> {
        if (!Files.exists(path)) return emptyList()
        PrivateConfig.harden(path) // upgrade a legacy world-readable file on first read
        // Decoded leniently: a byte that is not UTF-8 spoils its own line, not the whole file.
        return String(Files.readAllBytes(path), Charsets.UTF_8).lines().mapNotNull { line ->
            val parts = line.trim().split(" ")
            if (parts.size != 5 && parts.size != 6) return@mapNotNull null
            val port = parts[1].toIntOrNull() ?: return@mapNotNull null
            HostKeyMismatch(
                host = parts[0],
                port = port,
                keyType = parts[2],
                recordedFingerprint = parts[3],
                offeredFingerprint = parts[4],
                observedAt = parts.getOrElse(5) { "" },
            )
        }
    }

    private fun HostKeyMismatch.sameKeyAs(host: String, port: Int, keyType: String): Boolean =
        this.host == host && this.port == port && this.keyType == keyType
}
