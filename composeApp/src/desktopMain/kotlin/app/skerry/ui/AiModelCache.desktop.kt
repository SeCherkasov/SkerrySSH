package app.skerry.ui

import app.skerry.shared.io.PrivateConfig
import java.nio.file.Path
import java.security.MessageDigest

/** Desktop: model-catalog cache in the config dir — one small file per server address. */
actual object AiModelCache {

    private val dir: Path = configDir()
    private val prefs = FilePrefs(dir)

    /** `~/.config/skerry` (or $XDG_CONFIG_HOME), hardened — mirrors the app's main config dir. */
    private fun configDir(): Path {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        val base = xdg?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".config")
        return base.resolve("skerry").also { PrivateConfig.ensureDir(it) }
    }

    actual fun load(baseUrl: String): List<String> = prefs.lines(cacheKey(baseUrl))

    actual fun save(baseUrl: String, models: List<String>) = prefs.setLines(cacheKey(baseUrl), models)

    actual fun loadFavorites(baseUrl: String): Set<String> = prefs.lines(favKey(baseUrl)).toSet()

    actual fun saveFavorite(baseUrl: String, id: String, favorite: Boolean) {
        val fav = prefs.lines(favKey(baseUrl)).toMutableSet()
        if (favorite) fav.add(id) else fav.remove(id)
        prefs.setLines(favKey(baseUrl), fav.toList())
    }

    /** URL-safe key: short SHA-1 of the trimmed address (file names can't carry slashes/colons). */
    private fun cacheKey(baseUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(baseUrl.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }
        return "ai_model_cache_" + digest.take(12)
    }

    private fun favKey(baseUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(baseUrl.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }
        return "ai_model_fav_" + digest.take(12)
    }
}
