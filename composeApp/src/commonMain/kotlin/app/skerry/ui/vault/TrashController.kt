package app.skerry.ui.vault

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.terminal.epochMillis
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashSource
import app.skerry.shared.vault.TrashStore

/**
 * A trash row as the screen shows it: everything but the deleted payload, which stays in the vault
 * until the user actually restores the record ([TrashSource.restore] reads it by [recordId]).
 */
@Immutable
data class TrashItem(
    val recordId: String,
    val originId: String,
    val type: RecordType,
    val label: String,
    val deletedAt: Long,
    /** Whole days before the entry is purged; never less than 1 while it's still listed. */
    val daysLeft: Int,
)

/**
 * State of the Trash screen over [TrashSource] (the vault trash). Mirrors
 * [app.skerry.ui.known.KnownHostsController]: mutations are synchronous (rare, file-backed) and
 * every one of them reloads the list.
 *
 * [onRestored] is the app's manager reload (the same callback sync uses after a pull): a restored
 * host/secret lives in the vault, and the in-memory managers wouldn't show it until a reopen.
 * Called only when something actually came back.
 */
@Stable
class TrashController(
    private val trash: TrashSource,
    private val retentionMillis: Long = TrashStore.RETENTION_MILLIS,
    private val now: () -> Long = ::epochMillis,
    private val onRestored: () -> Unit = {},
) {
    var items by mutableStateOf(emptyList<TrashItem>())
        private set

    /** Reloads the list, dropping entries past the retention window on the way in. */
    fun refresh() {
        // No timer runs the retention window: opening the trash (and unlock) is when it's applied.
        trash.purgeExpired()
        val at = now()
        items = trash.entries().map { entry ->
            TrashItem(
                recordId = entry.recordId,
                originId = entry.originId,
                type = entry.originType,
                label = entry.label,
                deletedAt = entry.deletedAt,
                daysLeft = daysLeft(entry.deletedAt, at),
            )
        }
    }

    /** Puts the record back; `false` if the entry was already gone (another device restored/purged it). */
    fun restore(item: TrashItem): Boolean {
        val restored = trash.restore(item.recordId)
        refresh()
        if (restored) onRestored()
        return restored
    }

    /** Forgets one entry for good. */
    fun purge(item: TrashItem) {
        trash.purge(item.recordId)
        refresh()
    }

    /** Empties the trash. */
    fun emptyAll() {
        trash.emptyAll()
        refresh()
    }

    /** Whole days left, rounded up so the last partial day still reads as "1 day". */
    private fun daysLeft(deletedAt: Long, at: Long): Int {
        val left = deletedAt + retentionMillis - at
        return ((left + DAY_MILLIS - 1) / DAY_MILLIS).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
