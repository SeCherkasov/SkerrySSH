package app.skerry.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.container.ContainerSpec
import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshConfigHost
import app.skerry.shared.ssh.SshConfigImport
import app.skerry.shared.vault.Credential

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
    /** Answer the server's questions instead of using a stored secret (see [Host.interactiveAuth]). */
    val interactiveAuth: Boolean = false,
    val tags: List<String> = emptyList(),
    val aiPolicy: AiPolicy = AiPolicy.Strict,
    val connectionType: ConnectionType = ConnectionType.SSH,
    val jumpHostId: String? = null,
    val keepAliveSeconds: Int = 30,
    val notes: String? = null,
    /** Container/pod to exec into; only set for [ConnectionType.CONTAINER] profiles. */
    val container: ContainerSpec? = null,
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
                interactiveAuth = draft.interactiveAuth,
                tags = draft.tags,
                aiPolicy = draft.aiPolicy,
                connectionType = draft.connectionType,
                jumpHostId = draft.jumpHostId,
                keepAliveSeconds = draft.keepAliveSeconds,
                notes = draft.notes,
                container = draft.container,
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
    fun importSshConfig(
        parsed: List<SshConfigHost>,
        selected: Set<String>,
        defaultUser: String?,
        existingLabels: Set<String> = emptySet(),
        saveCredentials: (List<Credential>) -> Unit = {},
    ): Int {
        val plan = SshConfigImport.plan(parsed, selected, defaultUser, newId, existingLabels)
        // Credentials first: the hosts about to be written already reference them by id, and a crash
        // in between would otherwise leave profiles pointing at secrets that never existed.
        saveCredentials(plan.credentials)
        importHosts(plan.hosts)
        return plan.hosts.size
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

    /**
     * Manual reorder from a section sidebar: [targetIndexInGroup] counts only the rows of [section]
     * the user can see, so it is translated into the catalog's own index first. Dropping a shell
     * host below the last visible one must not push it past a remote desktop filed in the same
     * folder — that profile is invisible here and its place is not the user's to change.
     */
    fun moveHostInSection(hostId: String, targetGroup: String?, targetIndexInGroup: Int, section: HostSection) {
        store.reorder { all ->
            val group = targetGroup?.takeIf { it.isNotBlank() }
            // Same basis as moveHostToGroup's insertion: the target group without the dragged host.
            val siblings = all.filter { it.id != hostId && it.group?.takeIf(String::isNotBlank) == group }
            val visible = siblings.withIndex().filter { it.value.section == section }.map { it.index }
            moveHostToGroup(all, hostId, targetGroup, filteredIndexToFull(siblings.size, visible, targetIndexInGroup))
        }
        hosts = store.all()
    }

    /** Manual reorder: move folder [group] as a whole to [targetGroupIndex] among folders. */
    fun moveFolder(group: String?, targetGroupIndex: Int) {
        store.reorder { moveGroup(it, group, targetGroupIndex) }
        hosts = store.all()
    }

    /**
     * Folder reorder from a section sidebar: [targetGroupIndex] counts only folders that hold
     * profiles of [section] (the ones on screen), and is translated into the catalog's folder order
     * — a folder belonging entirely to the other section keeps its place.
     */
    fun moveFolderInSection(group: String?, targetGroupIndex: Int, section: HostSection) {
        store.reorder { all ->
            val moving = group?.takeIf { it.isNotBlank() }
            val folders = all.map { it.group?.takeIf(String::isNotBlank) }.distinct().filterNot { it == moving }
            val visible = folders.withIndex()
                .filter { (_, name) -> all.any { it.group?.takeIf(String::isNotBlank) == name && it.section == section } }
                .map { it.index }
            moveGroup(all, group, filteredIndexToFull(folders.size, visible, targetGroupIndex))
        }
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
