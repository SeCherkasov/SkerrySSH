package app.skerry.shared.vault

import kotlinx.serialization.Serializable

/**
 * Manual order of the snippet and runbook libraries: the id lists the user dragged into place,
 * synced as a single [RecordType.LIBRARY_ORDER] record with reserved id [LibraryOrderStore.ORDER_ID].
 * Ids the lists don't mention keep their [Vault.records] order and follow the ones that are in them
 * (see [sortedByOrder]), so an account that never reordered anything has no record here at all and
 * reads exactly as it did before the feature — no migration to run.
 *
 * Deliberately not a field of [WorkspaceLayout]: that record is the whole account's host tree and
 * it travels as one blob under LWW, so a snippet dragged in the library would be able to carry a
 * stale host order to every device. The two libraries share this record because they share a screen
 * and a sync toggle; between the two of them LWW can only cost the order of the other list.
 */
@Serializable
data class LibraryOrder(
    /** Global order of snippet ids in the library. */
    val snippets: List<String> = emptyList(),
    /** Global order of runbook ids in the library. */
    val runbooks: List<String> = emptyList(),
)

/**
 * Reads/writes the single [LibraryOrder] record, following [WorkspaceLayoutStore]. Holds no state
 * of its own, so [app.skerry.shared.snippet.VaultSnippetStore] and
 * [app.skerry.shared.runbook.VaultRunbookStore] each keep their own instance; what makes their
 * read-modify-writes safe against each other and against background sync is that both run inside
 * [Vault.transaction], not a shared object. Locked or absent vault → empty order.
 */
class LibraryOrderStore(private val vault: Vault) {

    private val store = VaultSingletonStore(vault, ORDER_ID, RecordType.LIBRARY_ORDER, LibraryOrder.serializer()) {
        LibraryOrder()
    }

    fun read(): LibraryOrder = store.load()

    /**
     * The order, or null when the record exists and cannot be read — see
     * [WorkspaceLayoutStore.readOrNull]. A writer that cannot tell an unreadable record from a
     * missing one replaces the other library's order with whatever it happens to hold, and LWW
     * carries the replacement to every device. Callers that write skip the update instead.
     */
    fun readOrNull(): LibraryOrder? = store.loadOrNull()

    fun write(order: LibraryOrder) {
        store.save(order)
    }

    companion object {
        /**
         * Reserved id of the library-order record, prefixed like [WorkspaceLayoutStore.LAYOUT_ID].
         * Not every id in the vault is this client's to choose — a team id is whatever the server
         * answered with — and an id belongs to one record type for its whole life, so an unprefixed
         * name is one a server could hand back and make the next write throw.
         */
        const val ORDER_ID = "skerry.library.order"
    }
}
