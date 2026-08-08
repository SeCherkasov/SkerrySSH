package app.skerry.ui.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ByokModelStateTest {

    private class Recorder {
        val requests = mutableListOf<Pair<String, String>>()
        val savedCatalogs = mutableListOf<Pair<String, List<String>>>()
        val savedFavorites = mutableListOf<Pair<String, Set<String>>>()
        val storedCatalogs = mutableMapOf<String, List<String>>()
        val storedFavorites = mutableMapOf<String, Set<String>>()
    }

    private fun state(
        recorder: Recorder,
        scope: CoroutineScope,
        baseUrl: String = "https://a.example.com/v1",
        io: kotlinx.coroutines.CoroutineDispatcher = UnconfinedTestDispatcher(),
        listModels: suspend (String, String) -> Result<List<String>> = { _, _ -> Result.success(emptyList()) },
    ) = ByokModelState(
        initialKey = "sk-key",
        initialModel = "gpt-4o-mini",
        initialBaseUrl = baseUrl,
        deps = ByokStateDeps(
            listModels = { key, url -> recorder.requests += key to url; listModels(key, url) },
            loadCatalog = { url -> recorder.storedCatalogs[url].orEmpty() },
            loadFavorites = { url -> recorder.storedFavorites[url].orEmpty() },
            saveCatalog = { url, models -> recorder.savedCatalogs += url to models },
            saveFavorites = { url, ids -> recorder.savedFavorites += url to ids },
            scope = scope,
            io = io,
            work = UnconfinedTestDispatcher(),
        ),
    )

    @Test
    fun `adopting saved settings keeps the fetched catalog and the save hint`() = runTest {
        val recorder = Recorder()
        val byok = state(recorder, this, listModels = { _, _ -> Result.success(listOf("gpt-4o", "o3")) })

        byok.refresh()
        advanceUntilIdle()
        byok.markSaved()

        // What the Save button does: the controller rewrites the settings and the screen adopts them.
        byok.adoptSettings(apiKey = "sk-key", model = "gpt-4o", baseUrl = "https://a.example.com/v1")

        assertEquals(listOf("gpt-4o", "o3"), byok.models, "the catalog must survive a settings change")
        assertEquals(ByokHint.SAVED, byok.hint)
        assertEquals("gpt-4o", byok.model)
    }

    @Test
    fun `a refresh files the catalog under the address the request started with`() = runTest {
        val recorder = Recorder()
        val answer = CompletableDeferred<Result<List<String>>>()
        val byok = state(recorder, this, listModels = { _, _ -> answer.await() })

        byok.refresh()
        advanceUntilIdle()
        byok.onEndpointChange("https://b.example.com/v1") // the field moves on mid-request
        answer.complete(Result.success(listOf("from-a")))
        advanceUntilIdle()

        assertEquals(listOf("https://a.example.com/v1" to listOf("from-a")), recorder.savedCatalogs)
        assertTrue(byok.models.isEmpty(), "the answer of the old endpoint must not be listed for the new one")
        assertEquals(ByokHint.ENDPOINT_CHANGED, byok.hint, "the outcome belongs to the address that was refreshed")
    }

    @Test
    fun `a failure about an address the user has left is not shown`() = runTest {
        val recorder = Recorder()
        val answer = CompletableDeferred<Result<List<String>>>()
        val byok = state(recorder, this, listModels = { _, _ -> answer.await() })

        byok.refresh()
        advanceUntilIdle()
        byok.onEndpointChange("https://b.example.com/v1")
        answer.complete(Result.failure(IllegalStateException("boom")))
        advanceUntilIdle()

        assertNull(byok.refreshFailure, "the error describes an endpoint that is no longer in the field")
    }

    @Test
    fun `a failed refresh reports the failure and clears the pending hint`() = runTest {
        val recorder = Recorder()
        val byok = state(recorder, this, listModels = { _, _ -> Result.failure(IllegalStateException("boom")) })

        byok.refresh()
        advanceUntilIdle()

        assertEquals(AiFailure.UNKNOWN, byok.refreshFailure)
        assertNull(byok.hint)
        assertTrue(recorder.savedCatalogs.isEmpty())
        assertFalse(byok.refreshing)
    }

    @Test
    fun `editing a field drops the previous refresh error`() = runTest {
        val recorder = Recorder()
        val byok = state(recorder, this, listModels = { _, _ -> Result.failure(IllegalStateException("401")) })

        byok.refresh()
        advanceUntilIdle()
        assertEquals(AiFailure.UNKNOWN, byok.refreshFailure)

        byok.onKeyChange("sk-corrected")

        assertNull(byok.refreshFailure, "the error was about the key as it was, not the one being typed")
        assertEquals(ByokHint.KEY_CHANGED, byok.hint)
    }

    @Test
    fun `a second refresh is ignored while one is in flight`() = runTest {
        val recorder = Recorder()
        val answer = CompletableDeferred<Result<List<String>>>()
        var calls = 0
        val byok = state(recorder, this, listModels = { _, _ -> calls++; answer.await() })

        byok.refresh()
        advanceUntilIdle()
        byok.refresh()
        advanceUntilIdle()
        answer.complete(Result.success(listOf("gpt-4o")))
        advanceUntilIdle()

        assertEquals(1, calls, "the in-flight request owns the state until it finishes")
        assertEquals(listOf("gpt-4o"), byok.models)
        assertFalse(byok.refreshing)
    }

    @Test
    fun `a cancelled refresh does not leave the busy flag set`() = runTest {
        val recorder = Recorder()
        val scope = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val byok = state(recorder, scope, listModels = { _, _ -> CompletableDeferred<Result<List<String>>>().await() })

        byok.refresh()
        advanceUntilIdle()
        assertTrue(byok.refreshing, "the request is still in flight")

        scope.cancel() // the screen leaves composition mid-request
        advanceUntilIdle()

        assertFalse(byok.refreshing, "refreshing must be released in finally, cancellation included")
    }

    @Test
    fun `toggling a favorite stores it under the typed address`() = runTest {
        val recorder = Recorder()
        val byok = state(recorder, this)

        byok.onEndpointChange("https://b.example.com/v1")
        byok.toggleFavorite("gpt-4o")
        advanceUntilIdle()
        byok.toggleFavorite("gpt-4o")
        advanceUntilIdle()

        assertEquals(
            listOf(
                "https://b.example.com/v1" to setOf("gpt-4o"),
                "https://b.example.com/v1" to emptySet(),
            ),
            recorder.savedFavorites,
            "the whole set is written each time, so a lost ordering cannot drop a star",
        )
    }

    @Test
    fun `the cache is not applied over a catalog a refresh just published`() = runTest {
        val recorder = Recorder()
        recorder.storedCatalogs["https://a.example.com/v1"] = listOf("stale-from-disk")
        val answer = CompletableDeferred<Result<List<String>>>()
        val byok = state(recorder, this, listModels = { _, _ -> answer.await() })

        byok.refresh()
        advanceUntilIdle()
        byok.reloadCache() // the effect keyed on the address can fire while the request is open

        assertTrue(byok.models.isEmpty(), "the refresh owns this address; the disk snapshot must not land")

        answer.complete(Result.success(listOf("fresh-from-endpoint")))
        advanceUntilIdle()
        assertEquals(listOf("fresh-from-endpoint"), byok.models)
    }

    @Test
    fun `a refresh sends the key that was on screen when it started`() = runTest {
        val recorder = Recorder()
        val answer = CompletableDeferred<Result<List<String>>>()
        val byok = state(recorder, this, listModels = { _, _ -> answer.await() })

        byok.refresh()
        byok.onKeyChange("sk-typed-after-the-press") // lands before the coroutine's first dispatch
        answer.complete(Result.success(emptyList()))
        advanceUntilIdle()

        assertEquals(
            listOf("sk-key" to "https://a.example.com/v1"),
            recorder.requests,
            "the key on screen at the press is the one that goes out",
        )
    }

    @Test
    fun `changing the address mid-refresh still loads that address's cache`() = runTest {
        val recorder = Recorder()
        recorder.storedCatalogs["https://b.example.com/v1"] = listOf("b-model")
        val answer = CompletableDeferred<Result<List<String>>>()
        val byok = state(recorder, this, listModels = { _, _ -> answer.await() })

        byok.refresh() // for A
        advanceUntilIdle()
        byok.onEndpointChange("https://b.example.com/v1") // the screen's effect would call reloadCache here
        byok.reloadCache() // …and it is skipped, because a refresh is in flight
        answer.complete(Result.success(listOf("a-model")))
        advanceUntilIdle()

        assertEquals(
            listOf("b-model"),
            byok.models,
            "the discarded answer must not leave A's catalog listed under B, and nothing else reloads B",
        )
    }


    @Test
    fun `the cache fills the picker for the address in the field`() = runTest {
        val recorder = Recorder()
        recorder.storedCatalogs["https://a.example.com/v1"] = listOf("cached-model")
        recorder.storedFavorites["https://a.example.com/v1"] = setOf("cached-model")
        val byok = state(recorder, this)

        byok.reloadCache()

        assertEquals(listOf("cached-model"), byok.models)
        assertEquals(setOf("cached-model"), byok.favorites)
    }
}
