package app.skerry.android

import app.skerry.ui.sync.SyncConfig
import app.skerry.ui.sync.SyncConfigStore
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Файловое хранилище привязки к sync-серверу (Android): `sync.json` в приватном каталоге приложения
 * ([android.content.Context.getFilesDir] — изолирован по UID, дополнительные POSIX-права не нужны).
 * Хранит только НЕсекретное — URL сервера, accountId и стабильный deviceId; токены и пароль здесь не
 * лежат (переавторизация по мастер-паролю). Формат — `key=urlencoded` построчно (паритет с desktop
 * [FileSyncConfigStore]); URL-кодирование экранирует переносы строк, разбор построчный безопасен.
 * Чтение best-effort: битый/отсутствующий файл → `null` (sync просто выключен).
 */
class AndroidSyncConfigStore(private val file: File) : SyncConfigStore {

    override fun load(): SyncConfig? {
        if (!file.exists()) return null
        return runCatching {
            val map = file.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to URLDecoder.decode(line.substring(i + 1), "UTF-8")
            }.toMap()
            val url = map["serverUrl"]
            val account = map["accountId"]
            val device = map["deviceId"]
            if (url.isNullOrEmpty() || account.isNullOrEmpty() || device.isNullOrEmpty()) null
            else SyncConfig(
                serverUrl = url,
                accountId = account,
                deviceId = device,
                keepConnected = map["keepConnected"] == "true",
                sealedRefreshToken = map["sealedRefreshToken"]?.takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    override fun save(config: SyncConfig) {
        val text = buildString {
            appendLine("serverUrl=${URLEncoder.encode(config.serverUrl, "UTF-8")}")
            appendLine("accountId=${URLEncoder.encode(config.accountId, "UTF-8")}")
            appendLine("deviceId=${URLEncoder.encode(config.deviceId, "UTF-8")}")
            appendLine("keepConnected=${config.keepConnected}")
            config.sealedRefreshToken?.let { appendLine("sealedRefreshToken=${URLEncoder.encode(it, "UTF-8")}") }
        }
        // Атомарно: пишем во временный файл и переименовываем — иначе обрыв процесса между truncate
        // и записью оставил бы пустой/усечённый sync.json (паритет с desktop PrivateConfig.atomicWrite).
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // renameTo не атомарен на некоторых FS, если цель существует — фолбэк через копирование.
            file.writeText(text)
            tmp.delete()
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }
}
