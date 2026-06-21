package app.skerry.shared.ssh

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Файловое [HostKeyMismatchStore]: по строке на событие, поля разделены пробелом —
 * `host port keyType recordedFp offeredFp observedAt`. На тройку (host, port, keyType) хранится не
 * более одной записи: [record] перезаписывает прежнюю. Битые и пустые строки игнорируются.
 * Содержимое кешируется в памяти при создании; любая мутация перезаписывает файл целиком (записей
 * мало — это незакрытые предупреждения, не журнал).
 *
 * Синхронизировано: [TofuHostKeyVerifier.verify] вызывает [record] из IO-потока sshj параллельно
 * с чтением из UI-контроллера.
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
        path.parent?.let { Files.createDirectories(it) }
        Files.write(
            path,
            entries.map(::encode),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun encode(m: HostKeyMismatch): String = buildString {
        append(m.host).append(' ').append(m.port).append(' ').append(m.keyType).append(' ')
        append(m.recordedFingerprint).append(' ').append(m.offeredFingerprint)
        // observedAt — последнее поле; пусто (часы по умолчанию) опускаем, иначе trim()+split при
        // загрузке потерял бы хвостовой пробел и строка перестала бы парситься (5 ≠ 6 полей).
        if (m.observedAt.isNotEmpty()) append(' ').append(m.observedAt)
    }

    private fun load() {
        if (!Files.exists(path)) return
        Files.readAllLines(path).forEach { line ->
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
