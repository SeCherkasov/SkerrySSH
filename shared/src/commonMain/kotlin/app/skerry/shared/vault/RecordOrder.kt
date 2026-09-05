package app.skerry.shared.vault

/**
 * Sorts records by an explicit list of ids. Stable: an id the order doesn't mention keeps its
 * relative [Vault.records] position and lands after everything the order does mention — which is
 * where a record created or merged since the order was last written belongs, and why creating one
 * doesn't have to write the order at all.
 */
internal fun <T> List<T>.sortedByOrder(order: List<String>, id: (T) -> String): List<T> {
    if (order.isEmpty()) return this
    val rank = order.withIndex().associate { (i, recordId) -> recordId to i }
    return sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
}

/**
 * Rejects a reorder transform that changed the id set. Size **and** set: set equality alone misses
 * a duplicate (`[A, B, C, A]`), and a duplicated id corrupts the stored order — `associate` above
 * keeps the last index for it. A lost id is worse still: for a host it is a lost secret reference.
 *
 * The message carries counts only. The records themselves hold commands and credential references,
 * and this one travels into logs and crash reports.
 */
internal fun <T> requireSameIds(current: List<T>, updated: List<T>, id: (T) -> String) {
    require(updated.size == current.size && updated.mapTo(mutableSetOf(), id) == current.mapTo(mutableSetOf(), id)) {
        "reorder must preserve the id set (had ${current.size}, got ${updated.size})"
    }
}
