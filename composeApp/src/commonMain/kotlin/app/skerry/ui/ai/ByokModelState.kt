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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Localized hint strings for the BYOK fields, resolved once in composition (stringResource is @Composable). */
data class ByokHints(
    val endpointChanged: String,
    val keyChanged: String,
    val modelSelected: String,
    val refreshing: String,
    val refreshed: String,
    val saved: String,
)

/**
 * Injected dependencies of [ByokModelState] (persistence functions + refresh entry + scope),
 * grouped so the state constructor stays under the detekt LongParameterList threshold.
 */
data class ByokStateDeps(
    val hints: ByokHints,
    val listModels: suspend (apiKey: String, baseUrl: String) -> Result<List<String>>,
    val saveCatalog: (baseUrl: String, models: List<String>) -> Unit,
    val saveFavorite: (baseUrl: String, id: String, favorite: Boolean) -> Unit,
    val scope: CoroutineScope,
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
    var refreshing by mutableStateOf(false); internal set
    var refreshFailure by mutableStateOf<AiFailure?>(null); internal set
    var modelMenuOpen by mutableStateOf(false); internal set
    // Status hint: Pending stays until superseded (e.g. "key changed — press Save", "refreshing…"),
    // Flash clears itself after 3s (e.g. "models refreshed", "saved"). Text is resolved by the
    // caller's ByokHints; the class only tracks which state the UI is in.
    var hint by mutableStateOf<String?>(null); internal set
    var hintFlash by mutableStateOf(false); internal set

    fun onEndpointChange(value: String) {
        baseUrl = value
        hint = deps.hints.endpointChanged; hintFlash = false
    }

    fun onKeyChange(value: String) {
        key = value
        hint = deps.hints.keyChanged; hintFlash = false
    }

    fun onSelectModel(id: String) {
        model = id
        modelMenuOpen = false
        // Picking fills the field; nothing is persisted until Save (visible hint reminds that).
        hint = deps.hints.modelSelected; hintFlash = false
    }

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        deps.saveFavorite(baseUrl, id, id in favorites)
    }

    fun refresh() {
        refreshFailure = null
        refreshing = true
        hint = deps.hints.refreshing; hintFlash = false
        deps.scope.launch {
            val result = deps.listModels(key, baseUrl)
            refreshing = false
            result.fold(
                onSuccess = { fetched ->
                    models = fetched
                    deps.saveCatalog(baseUrl, fetched)
                    hint = deps.hints.refreshed; hintFlash = true
                },
                onFailure = { e ->
                    refreshFailure = if (e is AiException) e.toFailure() else AiFailure.UNKNOWN
                    hint = null
                },
            )
        }
    }

    fun markSaved() {
        hint = deps.hints.saved; hintFlash = true
    }
}

/**
 * Creates the shared BYOK state and keeps it in sync with the controller's settings. The cache is
 * loaded in a [LaunchedEffect] keyed on the typed [ByokModelState.baseUrl] — not inline in
 * composition, where a file/SharedPreferences read would repeat on every settings change.
 */
@Composable
fun rememberByokModelState(ai: AiAssistantController, hints: ByokHints): ByokModelState {
    val scope = rememberCoroutineScope()
    val state = remember(ai.settings) {
        ByokModelState(
            initialKey = ai.settings.apiKey,
            initialModel = ai.settings.model,
            initialBaseUrl = ai.settings.baseUrl,
            deps = ByokStateDeps(
                hints = hints,
                listModels = ai::listModels,
                saveCatalog = AiModelCache::save,
                saveFavorite = AiModelCache::saveFavorite,
                scope = scope,
            ),
        )
    }
    // Reload the cache whenever the *typed* address changes (also on first composition and after a
    // settings reload that reset the fields). Runs off the composition path.
    LaunchedEffect(state.baseUrl) {
        state.models = AiModelCache.load(state.baseUrl)
        state.favorites = AiModelCache.loadFavorites(state.baseUrl)
    }
    // Flash hints self-dismiss after 3s; pending hints stay until superseded.
    LaunchedEffect(state.hint, state.hintFlash) {
        if (state.hint != null && state.hintFlash) {
            delay(3000)
            state.hint = null
        }
    }
    return state
}
