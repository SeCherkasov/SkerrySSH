package app.skerry.ui

import android.content.Context
import app.skerry.ui.sftp.SafBridge

/** Android: model-catalog cache in app-private SharedPreferences (per device, never synced). */
actual object AiModelCache {

    private val prefs: android.content.SharedPreferences?
        get() = SafBridge.context()?.getSharedPreferences("ai_model_cache", Context.MODE_PRIVATE)

    actual fun load(baseUrl: String): List<String> =
        prefs?.getString(cacheKey(baseUrl), null)?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    actual fun save(baseUrl: String, models: List<String>) {
        prefs?.edit()?.putString(cacheKey(baseUrl), models.joinToString("\n"))?.apply()
    }

    actual fun loadFavorites(baseUrl: String): Set<String> =
        prefs?.getString(favKey(baseUrl), null)?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    actual fun saveFavorite(baseUrl: String, id: String, favorite: Boolean) {
        val fav = (prefs?.getString(favKey(baseUrl), null)?.lines()?.filter { it.isNotBlank() } ?: emptyList()).toMutableSet()
        if (favorite) fav.add(id) else fav.remove(id)
        prefs?.edit()?.putString(favKey(baseUrl), fav.joinToString("\n"))?.apply()
    }

    /** Stable per-address key (16-hex of the trimmed address hash; no URL chars in prefs keys). */
    private fun cacheKey(baseUrl: String): String =
        "mc_" + baseUrl.trim().hashCode().toUInt().toString(16)

    private fun favKey(baseUrl: String): String =
        "mf_" + baseUrl.trim().hashCode().toUInt().toString(16)
}
