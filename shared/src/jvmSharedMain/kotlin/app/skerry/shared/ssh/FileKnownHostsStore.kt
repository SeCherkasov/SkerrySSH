package app.skerry.shared.ssh

import app.skerry.shared.io.PrivateConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * Файловое [KnownHostsStore]: по строке на запись, поля разделены пробелом —
 * `host port keyType fingerprint [firstSeen]`. Пятое поле (отметка первого доверия) появилось
 * позже: строки из четырёх полей читаются с пустым [KnownHost.firstSeen] (обратная совместимость).
 * Битые и пустые строки при загрузке игнорируются. Содержимое кешируется в памяти при создании;
 * [add] дописывает строку в файл и кеш, [remove] перезаписывает файл целиком.
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
        // Раньше тут был append, но он создавал файл под umask до harden (TOCTOU-окно) и не был
        // атомарен. Перезапись из кеша даёт тот же файл атомарно и приватно — список known-hosts мал.
        rewrite()
    }

    @Synchronized
    override fun replace(host: KnownHost) {
        entries.removeAll { it.host == host.host && it.port == host.port && it.keyType == host.keyType }
        entries += host
        rewrite()
    }

    @Synchronized
    override fun remove(host: String, port: Int, keyType: String) {
        val removed = entries.removeAll { it.host == host && it.port == port && it.keyType == keyType }
        if (removed) rewrite()
    }

    /** Перезаписать файл целиком из кеша — атомарно и приватно (для add/replace/remove). */
    private fun rewrite() {
        PrivateConfig.atomicWrite(path, encodeAll())
    }

    private fun encodeAll(): ByteArray =
        entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n", transform = ::encode)
            .toByteArray()

    private fun encode(host: KnownHost): String = buildString {
        append(host.host).append(' ').append(host.port).append(' ')
        append(host.keyType).append(' ').append(host.fingerprint)
        if (host.firstSeen.isNotEmpty()) append(' ').append(host.firstSeen)
    }

    private fun load() {
        if (!Files.exists(path)) return
        PrivateConfig.harden(path) // апгрейд старого мир-читаемого файла при первом чтении
        // Ошибка чтения (нет прав/IO) не должна валить конструктор стора — трактуем как пустой.
        val lines = runCatching { Files.readAllLines(path) }.getOrElse { return }
        lines.forEach { line ->
            val parts = line.trim().split(" ")
            if (parts.size != 4 && parts.size != 5) return@forEach
            val port = parts[1].toIntOrNull() ?: return@forEach
            entries += KnownHost(
                host = parts[0],
                port = port,
                keyType = parts[2],
                fingerprint = parts[3],
                firstSeen = parts.getOrElse(4) { "" },
            )
        }
    }
}
