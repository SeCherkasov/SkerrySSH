package app.skerry.ui.vault

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Prepare reads/decryption off the UI thread; the returned action only publishes prepared state. */
class VaultCatalogReload(val types: Set<RecordType>, val prepare: () -> (() -> Unit))

/** Shared desktop/Android refresh path, serialized and guarded against edits or lock during loading. */
class VaultCatalogReloader(
    private val vault: Vault,
    catalogs: List<VaultCatalogReload>,
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val mutex = Mutex()
    private val catalogs = MutableStateFlow(catalogs.map { RegisteredCatalog(it) })
    internal val hasCatalogs: Boolean get() = catalogs.value.isNotEmpty()

    /** Composition-owned projections participate in the same refresh and cancellation boundary. */
    fun register(additions: List<VaultCatalogReload>): () -> Unit {
        val registered = additions.map { RegisteredCatalog(it) }
        catalogs.update { it + registered }
        return {
            registered.forEach { it.active.value = false }
            catalogs.update { current -> current.filterNot { it in registered } }
        }
    }

    /** Returns false if the vault locked while loading; callers must not resume sync/autostart then. */
    suspend fun reload(types: Set<RecordType> = RecordType.entries.toSet()): Boolean = mutex.withLock {
        val selected = catalogs.value.filter { registered -> registered.catalog.types.any { it in types } }
        while (true) {
            currentCoroutineContext().ensureActive()
            val prepared = withContext(loadDispatcher) {
                val revision = vault.revision
                if (!vault.isUnlocked) return@withContext null
                try {
                    val actions = selected.map { registered ->
                        val publish = registered.catalog.prepare()
                        val action: () -> Unit = { if (registered.active.value) publish() }
                        action
                    }
                    Prepared(revision, actions)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    // A lock between store reads is expected; an unchanged-session error is real.
                    if (revision == vault.revision) throw e
                    Prepared(revision, emptyList())
                }
            } ?: return@withLock false
            val published = withContext(uiDispatcher) {
                if (prepared.revision != vault.revision) false else {
                    prepared.actions.forEach { it() }
                    true
                }
            }
            if (published) return@withLock true
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    private class Prepared(val revision: Long, val actions: List<() -> Unit>)
    private class RegisteredCatalog(val catalog: VaultCatalogReload) { val active = atomic(true) }
}
