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
 * Synchronized: [TofuHostKeyVerifier.verify] calls [record] from the sshj IO thread concurrently with
 * reads from the UI controller.
 */
class FileHostKeyMismatchStore(private val path: Path) : HostKeyMismatchStore {

    private val entries = mutableListOf<HostKeyMismatch>()

    init {
        load()
    }

    @Synchronized
    override fun all(): List<HostKeyMismatch> = entries.toList()

    @Synchronized
    override fun record(mismatch: HostKeyMismatch) {
        entries.removeAll { it.sameKeyAs(mismatch.host, mismatch.port, mismatch.keyType) }
        entries += mismatch
        persist()
    }

    @Synchronized
    override fun clear(host: String, port: Int, keyType: String) {
        val removed = entries.removeAll { it.sameKeyAs(host, port, keyType) }
        if (removed) persist()
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

    private fun load() {
        if (!Files.exists(path)) return
        PrivateConfig.harden(path) // upgrade a legacy world-readable file on first read
        // A read failure must not fail the store constructor; treat it as empty.
        val lines = runCatching { Files.readAllLines(path) }.getOrElse { return }
        lines.forEach { line ->
            val parts = line.trim().split(" ")
            if (parts.size != 5 && parts.size != 6) return@forEach
            val port = parts[1].toIntOrNull() ?: return@forEach
            entries += HostKeyMismatch(
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
