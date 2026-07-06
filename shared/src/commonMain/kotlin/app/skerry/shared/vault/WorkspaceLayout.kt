package app.skerry.shared.vault

import kotlinx.serialization.Serializable

/**
 * Workspace structure synced as a single vault record: host order in the tree and the group list
 * (including empty folders and their order). Stored as the single [RecordType.GROUP] with reserved
 * id [WorkspaceLayoutStore.LAYOUT_ID], so it takes part in normal LWW sync
 * (`docs/skerry-sync-design.md`): the host tree is identical on every device.
 *
 * Host-to-group membership lives in [app.skerry.shared.host.Host.group]; this holds only order (of
 * hosts and groups) and the existence of empty groups (which have no host to store them). Per-device
 * UI state (collapsed folders, recent connections) is not included — it stays local.
 */
@Serializable
data class WorkspaceLayout(
    /** Global order of host ids in the tree. Hosts not in the list are appended at the end. */
    val hostOrder: List<String> = emptyList(),
    /** Group names in display order, including empty folders. Empty → order derived from hosts. */
    val groups: List<String> = emptyList(),
)

/**
 * Sole owner of the [WorkspaceLayout] record in [vault]. Both [VaultHostStore] and the group layer
 * write the layout through this one instance so read-modify-write doesn't clobber the other's field
 * (the UI serializes calls). Locked/absent vault → empty layout.
 *
 * Host order and empty folders share one record on purpose: sync applies it as a whole (LWW by
 * version), so the host tree moves between devices atomically. Consequence: a local layout edit that
 * coincides with an incoming remote version of this record (background
 * [app.skerry.shared.sync.SyncEngine]) resolves last-writer-wins over the whole record — by design,
 * not field-level data loss.
 */
class WorkspaceLayoutStore(private val vault: Vault) {

    private val store = VaultSingletonStore(vault, LAYOUT_ID, RecordType.GROUP, WorkspaceLayout.serializer()) {
        WorkspaceLayout()
    }

    fun read(): WorkspaceLayout = store.load()

    fun write(layout: WorkspaceLayout) {
        store.save(layout)
    }

    companion object {
        /** Reserved id of the layout record. Does not collide with UUID ids of hosts/groups. */
        const val LAYOUT_ID = "skerry.workspace.layout"
    }
}
