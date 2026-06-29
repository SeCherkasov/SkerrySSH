package app.skerry.shared.sync

import app.skerry.shared.io.PrivateConfig
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Файловый курсор дельта-синка (desktop + android, общий jvmShared): построчно `accountId=cursor` в
 * приватном файле (0600 через [PrivateConfig], каталог 0700). Переживает перезапуск процесса — иначе
 * каждый старт делал бы полный re-pull всей истории `since 0` (LWW идемпотентен, но это лишняя
 * нагрузка и усилитель ретрансляции старых тромбстоунов). Сам курсор не секрет (serverSeq), но лежит
 * рядом с приватной конфигурацией и пишется тем же атомарным путём.
 *
 * Кэш в памяти заполняется один раз при создании; [setCursor] обновляет его и атомарно переписывает
 * файл. Доступ синхронизирован — координатор зовёт [setCursor] и из ручного, и из WS-live-pull цикла.
 * Чтение best-effort: битый/отсутствующий файл → пустой курсор (0 на любой аккаунт).
 */
class FileSyncStateStore(private val path: Path) : SyncStateStore {

    private val lock = Any()
    private val cursors: MutableMap<String, Long> = load()

    override fun cursor(accountId: String): Long = synchronized(lock) { cursors[accountId] ?: 0L }

    override fun setCursor(accountId: String, cursor: Long) {
        synchronized(lock) {
            cursors[accountId] = cursor
            persist()
        }
    }

    private fun load(): MutableMap<String, Long> {
        if (!Files.exists(path)) return mutableMapOf()
        return runCatching {
            Files.readAllLines(path).mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                // accountId URL-кодирован → перенос строки/`=` экранированы, разбор построчный безопасен.
                val account = URLDecoder.decode(line.substring(0, i), Charsets.UTF_8)
                val value = line.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
                account to value
            }.toMap().toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun persist() {
        val text = buildString {
            cursors.forEach { (account, cursor) -> appendLine("${URLEncoder.encode(account, Charsets.UTF_8)}=$cursor") }
        }
        PrivateConfig.atomicWrite(path, text.encodeToByteArray())
    }
}
