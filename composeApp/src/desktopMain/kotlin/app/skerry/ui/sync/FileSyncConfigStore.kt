package app.skerry.ui.sync

import app.skerry.shared.io.PrivateConfig
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * File-backed sync-server binding store (desktop): `sync.json` alongside other config, mode 0600
 * via [PrivateConfig]. Holds only non-secret data — server URL, accountId, and a stable deviceId;
 * no tokens or password (re-auth uses the master password). Format is line-based `key=urlencoded`
 * (no extra dependency: composeApp does not pull in kotlinx.serialization); URL-encoding escapes
 * newlines, so line-based parsing is safe. Reads are best-effort: a missing or corrupt file
 * yields `null` (sync is simply off).
 */
class FileSyncConfigStore(private val path: Path) : SyncConfigStore {

    override fun load(): SyncConfig? {
        if (!Files.exists(path)) return null
        return runCatching {
            val map = Files.readAllLines(path).mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to decode(line.substring(i + 1))
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
            appendLine("serverUrl=${encode(config.serverUrl)}")
            appendLine("accountId=${encode(config.accountId)}")
            appendLine("deviceId=${encode(config.deviceId)}")
            appendLine("keepConnected=${config.keepConnected}")
            config.sealedRefreshToken?.let { appendLine("sealedRefreshToken=${encode(it)}") }
        }
        PrivateConfig.atomicWrite(path, text.encodeToByteArray())
    }

    override fun clear() {
        runCatching { Files.deleteIfExists(path) }
    }

    private fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
    private fun decode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8)
}
