package app.skerry.ui.sync

import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** Refresh debt survives partial pulls, key replacement and a cancelled/failed catalog reload. */
internal class SyncedCatalogRefresh(
    private val vault: Vault,
    private val publish: suspend (Set<RecordType>) -> Unit,
) {
    private val pending = MutableStateFlow(emptySet<RecordType>())

    fun invalidateAll() { pending.update { it + RecordType.entries } }

    suspend fun sync(run: suspend () -> SyncOutcome): SyncOutcome {
        val revision = vault.revision
        val outcome = try {
            run()
        } catch (e: Exception) {
            // A failed push after a committed pull must still refresh on the next successful cycle.
            if (vault.revision != revision) invalidateAll()
            throw e // includes cancellation
        }
        pending.update { it + outcome.changedTypes }
        return outcome.copy(changedTypes = pending.value)
    }

    suspend fun refresh() {
        val types = pending.getAndUpdate { emptySet() }
        if (types.isEmpty()) return
        try {
            publish(types)
        } catch (e: CancellationException) {
            pending.update { it + types }
            throw e
        } catch (_: Exception) {
            pending.update { it + types }
        }
    }
}
