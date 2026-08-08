package app.skerry.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiException
import app.skerry.ui.AiModelCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the status line under the BYOK fields is saying. Typed, not pre-resolved text: the state
 * tracks the situation and the composable resolves the words (`byokHintMessage`), the same split
 * [AiFailure]/`aiFailureMessage` already uses — otherwise a hint pinned before a language change
 * keeps rendering in the old language.
 *
 * [flash] hints clear themselves after a few seconds (an outcome: "saved", "models refreshed");
 * the rest stay until superseded (a pending edit: "key changed — press Save").
 */
enum class ByokHint(val flash: Boolean) {
    ENDPOINT_CHANGED(flash = false),
    KEY_CHANGED(flash = false),
    MODEL_CHANGED(flash = false),
    MODEL_SELECTED(flash = false),
    REFRESHING(flash = false),
    REFRESHED(flash = true),
    SAVED(flash = true),
}

/**
 * Injected dependencies of [ByokModelState] (persistence functions + refresh entry + scope),
 * grouped so the state constructor stays under the detekt LongParameterList threshold.
 */
data class ByokStateDeps(
    val listModels: suspend (apiKey: String, baseUrl: String) -> Result<List<String>>,
    val loadCatalog: (baseUrl: String) -> List<String>,
    val loadFavorites: (baseUrl: String) -> Set<String>,
    val saveCatalog: (baseUrl: String, models: List<String>) -> Unit,
    val saveFavorites: (baseUrl: String, ids: Set<String>) -> Unit,
    val scope: CoroutineScope,
    /**
     * The lane every cache read and write runs on — [scope] is the composition's main dispatcher,
     * and the store is a file on desktop. Single-threaded on purpose: two stars tapped in the same
     * frame would otherwise race each other's write and one would be lost.
     */
    val io: CoroutineDispatcher = CACHE_DISPATCHER,
    /**
     * Where the catalog request is awaited. Not [io]: decoding a 1 MiB answer would otherwise sit
     * on the same lane as the disk writes (and, on [scope], on the UI thread).
     */
    val work: CoroutineDispatcher = Dispatchers.Default,
)

/**
 * Shared state for the desktop and mobile BYOK fields (endpoint / API key / model combo + refresh).
 * Both screens drive the same seven states, the same hint lifecycle and the same refresh handler —
 * this is the single copy ([docs/coding-guidelines.md] §1: a second copy is the point to extract).
 *
 * The catalog cache is keyed to [baseUrl] **as typed**, not to the saved settings: changing the
 * address without pressing Save must show the previous server's models (that is what the field
 * still points at) and must store toggled favorites under the new address's key. The cache is
 * loaded off the composition path (see [rememberByokModelState]) when the typed address changes.
 *
 * The state outlives a settings change ([adoptSettings] instead of a re-keyed `remember`): pressing
 * Save rewrites the settings, and rebuilding the state there would drop the fetched catalog and the
 * "saved" hint the press just produced.
 */
@Stable
class ByokModelState(
    initialKey: String,
    initialModel: String,
    initialBaseUrl: String,
    private val deps: ByokStateDeps,
) {
    var key by mutableStateOf(initialKey); internal set
    var model by mutableStateOf(initialModel); internal set
    var baseUrl by mutableStateOf(initialBaseUrl); internal set
    var models by mutableStateOf(emptyList<String>()); internal set
    var favorites by mutableStateOf(emptySet<String>()); internal set
    /** Address of the request in flight, `null` when idle — the guards below are per address. */
    var refreshingAddress by mutableStateOf<String?>(null); internal set
    val refreshing: Boolean get() = refreshingAddress != null
    var refreshFailure by mutableStateOf<AiFailure?>(null); internal set
    var modelMenuOpen by mutableStateOf(false); internal set
    var hint by mutableStateOf<ByokHint?>(null); internal set

    fun onEndpointChange(value: String) {
        baseUrl = value
        edited(ByokHint.ENDPOINT_CHANGED)
    }

    fun onKeyChange(value: String) {
        key = value
        edited(ByokHint.KEY_CHANGED)
    }

    fun onModelChange(value: String) {
        model = value
        edited(ByokHint.MODEL_CHANGED)
    }

    fun onSelectModel(id: String) {
        model = id
        modelMenuOpen = false
        // Picking fills the field; nothing is persisted until Save (visible hint reminds that).
        edited(ByokHint.MODEL_SELECTED)
    }

    /**
     * A field was edited: show what is pending, and drop the previous refresh error — it describes
     * a request against the values as they were, and would otherwise be read as a verdict on what
     * is typed now.
     */
    private fun edited(pending: ByokHint) {
        hint = pending
        refreshFailure = null
    }

    /**
     * Takes over externally changed settings (Save, a vault reload, a sync update) without touching
     * the catalog, the favorites or the hint — those belong to this screen, not to the settings.
     */
    fun adoptSettings(apiKey: String, model: String, baseUrl: String) {
        this.key = apiKey
        this.model = model
        this.baseUrl = baseUrl
    }

    fun toggleFavorite(id: String) {
        val next = if (id in favorites) favorites - id else favorites + id
        favorites = next
        // The whole set is written, not the one toggle: a delta would have to be applied on top of
        // whatever is on disk, and two taps in flight would then lose one of them.
        val address = baseUrl
        deps.scope.launch { withContext(deps.io) { deps.saveFavorites(address, next) } }
    }

    /**
     * Loads what the picker shows for the address in the field. Skipped while a refresh for that
     * same address is in flight — that request is about to publish a fresher catalog. The check is
     * per address and is repeated after the read: a refresh can start and finish while the store is
     * being read, and its answer must not be overwritten by the snapshot taken before it.
     */
    suspend fun reloadCache() {
        val address = baseUrl
        if (refreshingAddress == address) return
        val generation = catalogGeneration
        val cached = withContext(deps.io) { deps.loadCatalog(address) to deps.loadFavorites(address) }
        // A refresh that started and published while the store was being read owns the picker: this
        // snapshot predates its answer.
        if (address != baseUrl || refreshingAddress == address || generation != catalogGeneration) return
        models = cached.first
        favorites = cached.second
    }

    /** Bumped by every published refresh, so a cache read taken before it can tell it is stale. */
    private var catalogGeneration = 0

    fun refresh() {
        if (refreshing) return // the button is disabled while in flight; the guard holds without it
        refreshFailure = null
        hint = ByokHint.REFRESHING
        // Address and key are captured up front: both can change between the press and the first
        // dispatch, and the answer of one endpoint must not be filed under another's cache key —
        // nor a freshly adopted key be sent to the endpoint that was on screen.
        val requested = baseUrl
        val requestedKey = key
        refreshingAddress = requested
        deps.scope.launch {
            try {
                withContext(deps.work) { deps.listModels(requestedKey, requested) }.fold(
                    onSuccess = { fetched -> onFetched(requested, fetched) },
                    onFailure = { e -> onFetchFailed(requested, e) },
                )
            } finally {
                refreshingAddress = null // also on cancellation: the flag must not outlive the request
                // The address moved on while this ran, so its cache reload was skipped by the guard
                // above and nothing else will re-trigger it — the picker would keep listing the old
                // endpoint's models under the new address.
                if (requested != baseUrl) reloadCache()
            }
        }
    }

    private suspend fun onFetched(requested: String, fetched: List<String>) {
        withContext(deps.io) { deps.saveCatalog(requested, fetched) }
        // Applied only while the field still points at the endpoint that answered; if it moved on,
        // the cache reload for the new address owns what the picker lists and what the line says.
        catalogGeneration++
        if (requested == baseUrl) {
            models = fetched
            hint = ByokHint.REFRESHED
        }
    }

    private fun onFetchFailed(requested: String, e: Throwable) {
        if (requested != baseUrl) return // an error about an address the user has already left
        refreshFailure = if (e is AiException) e.toFailure() else AiFailure.UNKNOWN
        hint = null
    }

    fun markSaved() {
        hint = ByokHint.SAVED
    }
}

/**
 * Creates the shared BYOK state and keeps it in sync with the controller's settings. The cache is
 * loaded in a [LaunchedEffect] keyed on the typed [ByokModelState.baseUrl] — not inline in
 * composition, where a file/SharedPreferences read would repeat on every settings change.
 */
@Composable
fun rememberByokModelState(ai: AiAssistantController): ByokModelState {
    val scope = rememberCoroutineScope()
    val state = remember {
        ByokModelState(
            initialKey = ai.settings.apiKey,
            initialModel = ai.settings.model,
            initialBaseUrl = ai.settings.baseUrl,
            deps = ByokStateDeps(
                listModels = ai::listModels,
                loadCatalog = AiModelCache::load,
                loadFavorites = AiModelCache::loadFavorites,
                saveCatalog = AiModelCache::save,
                saveFavorites = AiModelCache::saveFavorites,
                scope = scope,
            ),
        )
    }
    // Settings changed outside the fields (Save, vault reload, sync): adopt the values, keep the
    // catalog and the hint. Re-creating the state here would clear both on every Save.
    LaunchedEffect(ai.settings) {
        state.adoptSettings(ai.settings.apiKey, ai.settings.model, ai.settings.baseUrl)
    }
    // Reload the cache whenever the *typed* address changes (also on first composition and after a
    // settings reload that reset the fields). Off the composition path and off the main thread: on
    // Android this is a SharedPreferences read, on desktop a file read.
    LaunchedEffect(state.baseUrl) { state.reloadCache() }
    // Flash hints self-dismiss; pending hints stay until superseded.
    LaunchedEffect(state.hint) {
        if (state.hint?.flash == true) {
            delay(HINT_FLASH_MS)
            state.hint = null
        }
    }
    return state
}

private const val HINT_FLASH_MS = 3000L

/**
 * The one lane every model-cache read and write runs on (see [ByokStateDeps.io]). A file-level
 * value, not a function: two `limitedParallelism(1)` views do not serialise against each other, so
 * a per-instance lane would stop being a lane the moment a second screen existed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val CACHE_DISPATCHER: CoroutineDispatcher by lazy { Dispatchers.Default.limitedParallelism(1) }
