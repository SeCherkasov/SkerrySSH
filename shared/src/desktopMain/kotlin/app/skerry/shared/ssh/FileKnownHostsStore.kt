package app.skerry.shared.ssh

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Файловое [KnownHostsStore]: по строке на запись, поля разделены пробелом —
 * `host port keyType fingerprint`. Битые и пустые строки при загрузке игнорируются.
 * Содержимое кешируется в памяти при создании; [add] дописывает строку в файл и кеш.
 *
 * Методы синхронизированы: [HostKeyVerifier.verify] вызывается из IO-потока sshj,
 * параллельно с чтением из корутины-инициатора подключения.
 */
class FileKnownHostsStore(private val path: Path) : KnownHostsStore {

    private val entries = mutableListOf<KnownHost>()

    init {
        load()
    }

    @Synchronized
    override fun all(): List<KnownHost> = entries.toList()

    @Synchronized
    override fun add(host: KnownHost) {
        entries += host
        path.parent?.let { Files.createDirectories(it) }
        Files.write(
            path,
            listOf("${host.host} ${host.port} ${host.keyType} ${host.fingerprint}"),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun load() {
        if (!Files.exists(path)) return
        Files.readAllLines(path).forEach { line ->
            val parts = line.trim().split(" ")
            if (parts.size != 4) return@forEach
            val port = parts[1].toIntOrNull() ?: return@forEach
            entries += KnownHost(host = parts[0], port = port, keyType = parts[2], fingerprint = parts[3])
        }
    }
}
