package app.skerry.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshConfigHost
import app.skerry.shared.ssh.SshConfigImport

/**
 * Editable profile fields without [Host.id]: the create/edit form operates on a draft, and
 * [HostManagerController] assigns identity. [id] == null creates a new host, otherwise updates
 * the existing one.
 */
data class HostDraft(
    val id: String? = null,
    val label: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val group: String? = null,
    val credentialId: String? = null,
    val tags: List<String> = emptyList(),
    val aiPolicy: AiPolicy = AiPolicy.Strict,
    val connectionType: ConnectionType = ConnectionType.SSH,
    val jumpHostId: String? = null,
    val keepAliveSeconds: Int = 30,
)

/**
 * Host manager state over [HostStore]: keeps the profile list as Compose state and routes
 * mutations to the store, rereading [hosts] after each one. Id generation is injected ([newId]) —
 * deterministic in tests, a UUID generator on the platform.
 *
 * The store is synchronous (mutations are rare, UI-initiated), so the controller holds no
 * coroutine scope of its own, unlike [app.skerry.ui.connection.ConnectionController], which hosts
 * the terminal output stream.
 */
@Stable
class HostManagerController(
    private val store: HostStore,
    private val newId: () -> String,
) {
    var hosts by mutableStateOf(store.all())
        private set

    fun find(id: String): Host? = hosts.firstOrNull { it.id == id }

    /**
     * Reread the list from the store. Needed after writes bypassing the controller (e.g. vault
     * migration writes remapped [Host.credentialId] straight into [HostStore] on unlock).
     */
    fun reload() {
        hosts = store.all()
    }

    /**
     * Create (if [HostDraft.id] == null) or update a profile and reread the list. Returns the
     * assigned id — for a new host this is the generated [newId], so callers can highlight the
     * newly created record.
     */
    fun save(draft: HostDraft): String {
        val id = draft.id ?: newId()
        store.put(
            Host(
                id = id,
                label = draft.label,
                address = draft.address,
                port = draft.port,
                username = draft.username,
                group = draft.group,
                credentialId = draft.credentialId,
                tags = draft.tags,
                aiPolicy = draft.aiPolicy,
                connectionType = draft.connectionType,
                jumpHostId = draft.jumpHostId,
                keepAliveSeconds = draft.keepAliveSeconds,
                // Not a form field — toggled from the live VNC session; a form save must not reset it.
                vncResizeToWindow = find(id)?.vncResizeToWindow ?: false,
            ),
        )
        hosts = store.all()
        return id
    }

    /**
     * Persist a batch of already-built profiles (e.g. from an `ssh_config` import) and reread once.
     * Ids are pre-assigned by the caller so intra-batch references (ProxyJump → [Host.jumpHostId])
     * are already resolved; existing hosts are left untouched.
     */
    fun importHosts(imported: List<Host>) {
        for (host in imported) store.put(host)
        hosts = store.all()
    }

    /**
     * Import hosts parsed from an `ssh_config` file: plans the [selected] aliases into profiles
     * (resolving ProxyJump within the batch, filling [defaultUser] where the config omits `User`)
     * using this controller's id generator, persists them, and returns how many were created.
     */
    fun importSshConfig(parsed: List<SshConfigHost>, selected: Set<String>, defaultUser: String?): Int {
        val planned = SshConfigImport.plan(parsed, selected, defaultUser, newId)
        importHosts(planned)
        return planned.size
    }

    /** Persist the VNC "Resize to window" toggle changed from a live session (unknown id: no-op). */
    fun setVncResizeToWindow(id: String, enabled: Boolean) {
        val host = find(id) ?: return
        store.put(host.copy(vncResizeToWindow = enabled))
        hosts = store.all()
    }

    fun delete(id: String) {
        store.remove(id)
        hosts = store.all()
    }

    /**
     * Manual reorder (drag-and-drop): move host [hostId] into folder [targetGroup] at
     * [targetIndexInGroup] among its hosts. Covers both reordering within a folder and moving to
     * another (rewriting [Host.group]). Computed by pure [moveHostToGroup], committed atomically
     * via [HostStore.replaceAll].
     */
    fun moveHost(hostId: String, targetGroup: String?, targetIndexInGroup: Int) {
        // Computed inside store.reorder, over the store's current snapshot under its lock, not
        // over the (possibly stale) Compose-state hosts; otherwise races a concurrent write (migration).
        store.reorder { moveHostToGroup(it, hostId, targetGroup, targetIndexInGroup) }
        hosts = store.all()
    }

    /** Manual reorder: move folder [group] as a whole to [targetGroupIndex] among folders. */
    fun moveFolder(group: String?, targetGroupIndex: Int) {
        store.reorder { moveGroup(it, group, targetGroupIndex) }
        hosts = store.all()
    }

    /**
     * Rename group [oldName] to [newName] across all profiles. Computed by pure [renameHostGroup]
     * under the store's lock (like other reorders); the id set is preserved. The calling UI handles
     * empty/collapsed groups separately.
     */
    fun renameGroup(oldName: String, newName: String) {
        store.reorder { renameHostGroup(it, oldName, newName) }
        hosts = store.all()
    }

    /**
     * "Delete" group [name]: its hosts are ungrouped (`Host.group`=`null`, moving to Ungrouped) —
     * the profiles themselves and their secrets are untouched. Implemented via [renameHostGroup] to `null`.
     */
    fun deleteGroup(name: String) {
        store.reorder { renameHostGroup(it, name, null) }
        hosts = store.all()
    }
}
