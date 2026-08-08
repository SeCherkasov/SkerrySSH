package app.skerry.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cache is what the picker lists before the first refresh of a session, so the round trip and
 * the per-address keying are the behaviour worth pinning: one endpoint's catalog must never be
 * served for another's address. Writes land in `build/test-config` (see the `XDG_CONFIG_HOME` the
 * desktop test task sets), not in the developer's config directory.
 */
class AiModelCacheDesktopTest {

    private val a = "https://a.example.com/v1"
    private val b = "https://b.example.com/v1"

    @Test
    fun catalogRoundTripsPerAddress() {
        AiModelCache.save(a, listOf("gpt-4o", "gpt-4o-mini"))
        AiModelCache.save(b, listOf("llama3"))

        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), AiModelCache.load(a))
        assertEquals(listOf("llama3"), AiModelCache.load(b))
        assertEquals(emptyList(), AiModelCache.load("https://never-refreshed.example.com/v1"))
    }

    @Test
    fun favoritesRoundTripAndStayWithTheirAddress() {
        AiModelCache.saveFavorites(a, setOf("gpt-4o"))
        AiModelCache.saveFavorites(b, setOf("llama3"))

        assertEquals(setOf("gpt-4o"), AiModelCache.loadFavorites(a))
        assertEquals(setOf("llama3"), AiModelCache.loadFavorites(b))

        AiModelCache.saveFavorites(a, emptySet())
        assertTrue(AiModelCache.loadFavorites(a).isEmpty())
        assertEquals(setOf("llama3"), AiModelCache.loadFavorites(b), "the other address is untouched")
    }

    @Test
    fun theAddressIsTrimmedBeforeItBecomesAKey() {
        AiModelCache.save("https://trim.example.com/v1", listOf("gpt-4o"))

        assertEquals(
            listOf("gpt-4o"),
            AiModelCache.load("  https://trim.example.com/v1  "),
            "a stray space around the typed address must not orphan its catalog",
        )
    }

    @Test
    fun aRoundTripDoesNotProduceDuplicateIds() {
        // FilePrefs trims every line it reads back, so ids differing only by padding return
        // identical — and two identical keys throw inside the picker's LazyColumn.
        AiModelCache.save("https://dupes.example.com/v1", listOf("gpt-4o", " gpt-4o"))

        val loaded = AiModelCache.load("https://dupes.example.com/v1")
        assertEquals(loaded.distinct(), loaded, "cache round trip produced duplicate ids")
    }
}
