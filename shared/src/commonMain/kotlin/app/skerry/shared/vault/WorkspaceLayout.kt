package app.skerry.shared.vault

import kotlinx.serialization.Serializable

/**
 * Workspace structure synced as a single vault record: host order in the tree and the group list
 * (including empty folders and their order). Stored as the single [RecordType.GROUP] with reserved
 * id [WorkspaceLayoutStore.LAYOUT_ID], so it takes part in normal LWW sync
 * The host tree is identical on every device.
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
    /**
     * Empty folders of the remote-desktop list, kept apart from [groups] (the terminal list) so a
     * folder created in one section doesn't show up in the other. Only empty folders have a section
     * of their own here — a folder with hosts is derived from `Host.group` and follows its hosts.
     * Records written before the split have every empty folder in [groups], i.e. under the terminal
     * list.
     */
    val remoteDesktopGroups: List<String> = emptyList(),
    /** Global order of snippet ids in the library. */
    val snippetOrder: List<String> = emptyList(),
    /** Global order of runbook ids in the library. */
    val runbookOrder: List<String> = emptyList(),
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

    /**
     * The layout, or null when the record exists but cannot be read. Every write here is a
     * read-modify-write over the whole account's host order, so a reader that cannot tell an
     * unreadable record from a missing one replaces that order with whatever it is holding — and
     * LWW then carries the replacement to every device. Callers that write skip the update instead.
     */
    fun readOrNull(): WorkspaceLayout? = store.loadOrNull()

    fun write(layout: WorkspaceLayout) {
        store.save(layout)
    }

    /**
     * Replaces the empty-folder lists, keeping the host order the record also carries.
     *
     * Lives here rather than in the caller because it is a read-modify-write of the one record, and
     * every one of those has to hold the same two rules: it runs under [Vault.transaction], so a
     * merge from background sync cannot land between the read and the write, and it skips entirely
     * when the record cannot be read — writing over an unreadable layout would replace the whole
     * account's host order with an empty one, and LWW would carry that to every device.
     */
    fun updateGroups(groups: List<String>, remoteDesktopGroups: List<String>): Unit = vault.transaction {
        val current = readOrNull() ?: return@transaction
        write(current.copy(groups = groups, remoteDesktopGroups = remoteDesktopGroups))
    }

    companion object {
        /** Reserved id of the layout record. Does not collide with UUID ids of hosts/groups. */
        const val LAYOUT_ID = "skerry.workspace.layout"
    }
}
