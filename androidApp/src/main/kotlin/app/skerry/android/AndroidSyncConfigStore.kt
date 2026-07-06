package app.skerry.android

import app.skerry.ui.sync.SyncConfig
import app.skerry.ui.sync.SyncConfigStore
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * File-backed sync-server binding store (Android): `sync.json` in the app's private files dir.
 * Holds only non-secret data (server URL, accountId, deviceId); no tokens or password. Format is
 * `key=urlencoded` per line (mirrors desktop [FileSyncConfigStore]). Read is best-effort: a
 * missing/corrupt file yields `null` (sync disabled).
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
        // Atomic write: write to a temp file then rename, so a process kill mid-write can't leave a truncated sync.json.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // renameTo is not atomic on all filesystems when the target exists; fall back to direct write.
            file.writeText(text)
            tmp.delete()
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }
}
