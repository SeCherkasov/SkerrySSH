package app.skerry.ui

/**
 * Local cache of the fetched model catalog (per device, never synced): the BYOK settings UI loads
 * the model dropdown from here instead of re-fetching `/models` every time it opens. Keyed by
 * server address, so switching providers keeps each catalog separate. The cache holds only model
 * ids — enough for the dropdown to pick from. Desktop: config-dir files; Android: app-private
 * SharedPreferences.
 */
expect object AiModelCache {
    fun load(baseUrl: String): List<String>
    fun save(baseUrl: String, models: List<String>)

    /** Starred (favorite) model ids per server address; starred entries sort first in the picker. */
    fun loadFavorites(baseUrl: String): Set<String>

    /** Stores the whole set, so a write never has to merge into what is already on disk. */
    fun saveFavorites(baseUrl: String, ids: Set<String>)
}
