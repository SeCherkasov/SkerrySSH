package app.skerry.ui

import android.content.Context
import app.skerry.ui.sftp.SafBridge
import java.security.MessageDigest

/** Android: model-catalog cache in app-private SharedPreferences (per device, never synced). */
actual object AiModelCache {

    private val prefs: android.content.SharedPreferences?
        get() = SafBridge.context()?.getSharedPreferences("ai_model_cache", Context.MODE_PRIVATE)

    actual fun load(baseUrl: String): List<String> =
        prefs?.getString(cacheKey(baseUrl), null)?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    actual fun save(baseUrl: String, models: List<String>) {
        prefs?.edit()?.putString(cacheKey(baseUrl), models.filter { it.isStorable() }.joinToString("\n"))?.apply()
    }

    actual fun loadFavorites(baseUrl: String): Set<String> =
        prefs?.getString(favKey(baseUrl), null)?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    actual fun saveFavorite(baseUrl: String, id: String, favorite: Boolean) {
        if (!id.isStorable()) return // a newline in the id would split it into two cache entries on the next load
        val fav = (prefs?.getString(favKey(baseUrl), null)?.lines()?.filter { it.isNotBlank() } ?: emptyList()).toMutableSet()
        if (favorite) fav.add(id) else fav.remove(id)
        prefs?.edit()?.putString(favKey(baseUrl), fav.joinToString("\n"))?.apply()
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
