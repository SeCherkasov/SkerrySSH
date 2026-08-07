package app.skerry.ui

import java.security.MessageDigest

/** Desktop: model-catalog cache in the config dir — one small file per server address. */
actual object AiModelCache {

    private val prefs = FilePrefs(configDir())

    actual fun load(baseUrl: String): List<String> = prefs.lines(cacheKey(baseUrl))

    actual fun save(baseUrl: String, models: List<String>) = prefs.setLines(cacheKey(baseUrl), models)

    actual fun loadFavorites(baseUrl: String): Set<String> = prefs.lines(favKey(baseUrl)).toSet()

    actual fun saveFavorite(baseUrl: String, id: String, favorite: Boolean) {
        val fav = prefs.lines(favKey(baseUrl)).toMutableSet()
        if (favorite) fav.add(id) else fav.remove(id)
        prefs.setLines(favKey(baseUrl), fav.toList())
    }

    /**
     * URL-safe key: short SHA-1 of the trimmed address (file names can't carry slashes/colons).
     * The digest scheme is shared with the Android actual — two platforms must agree, or the same
     * endpoint would get different cache keys on each.
     */
    private fun cacheKey(baseUrl: String): String = "ai_model_cache_" + digest(baseUrl)

    private fun favKey(baseUrl: String): String = "ai_model_fav_" + digest(baseUrl)

    private fun digest(baseUrl: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(baseUrl.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }
            .take(12)
}
