package app.skerry.ui

import android.content.Context
import app.skerry.ui.sftp.SafBridge
import java.security.MessageDigest

/**
 * Android: model-catalog cache in app-private SharedPreferences (per device, never synced).
 * A failed write is silent by design, as on desktop (`FilePrefs`): the catalog is a convenience the
 * next refresh rebuilds, and the UI must not fail over a cache miss.
 */
actual object AiModelCache {

    private val prefs: android.content.SharedPreferences?
        get() = SafBridge.context()?.getSharedPreferences("ai_model_cache", Context.MODE_PRIVATE)

    actual fun load(baseUrl: String): List<String> =
        prefs?.getString(cacheKey(baseUrl), null)?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    // Trimmed and de-duplicated like the desktop actual, so both platforms hand the picker the same
    // set (its rows are keyed by id, and a duplicate key throws).
    actual fun save(baseUrl: String, models: List<String>) {
        val stored = models.map { it.trim() }.filter { it.isStorable() }.distinct()
        prefs?.edit()?.putString(cacheKey(baseUrl), stored.joinToString("\n"))?.apply()
    }

    actual fun loadFavorites(baseUrl: String): Set<String> =
        prefs?.getString(favKey(baseUrl), null)?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    actual fun saveFavorites(baseUrl: String, ids: Set<String>) {
        val stored = ids.map { it.trim() }.filter { it.isStorable() }
        prefs?.edit()?.putString(favKey(baseUrl), stored.joinToString("\n"))?.apply()
    }

    /**
     * Stable per-address key. Same truncated-SHA-1 scheme as the desktop actual (a 32-bit
     * `hashCode()` can collide and swap two endpoints' catalogs and favourites).
     */
    private fun cacheKey(baseUrl: String): String = "mc_" + digest(baseUrl)

    private fun favKey(baseUrl: String): String = "mf_" + digest(baseUrl)

    private fun digest(baseUrl: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(baseUrl.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }
            .take(12)

    /** Values are stored line-by-line; anything containing a newline would corrupt the cache. */
    private fun String.isStorable() = !contains('\n') && !contains('\r') && isNotBlank()
}
