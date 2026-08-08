package app.skerry.ui

import java.security.MessageDigest

/** Desktop: model-catalog cache in the config dir — one small file per server address. */
actual object AiModelCache {

    // Lazy: resolving the directory creates it (and fixes its mode), which must not happen on the
    // composition thread just because a bound reference to this object was built.
    private val prefs by lazy { FilePrefs(configDir()) }

    actual fun load(baseUrl: String): List<String> = prefs.lines(cacheKey(baseUrl))

    // Trimmed and de-duplicated on the way in: FilePrefs trims every line it reads back, so two ids
    // differing only by padding would return identical — and the picker keys its rows by id.
    actual fun save(baseUrl: String, models: List<String>) =
        prefs.setLines(cacheKey(baseUrl), models.map { it.trim() }.filter { it.isNotEmpty() }.distinct())

    actual fun loadFavorites(baseUrl: String): Set<String> = prefs.lines(favKey(baseUrl)).toSet()

    actual fun saveFavorites(baseUrl: String, ids: Set<String>) =
        prefs.setLines(favKey(baseUrl), ids.map { it.trim() }.filter { it.isNotEmpty() })

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
