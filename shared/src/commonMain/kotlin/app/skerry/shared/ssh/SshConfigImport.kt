package app.skerry.shared.ssh

import app.skerry.shared.host.Host

/**
 * Turns entries parsed from `ssh_config` into ready-to-save [Host] profiles. Pure and
 * platform-independent so the mapping (including ProxyJump resolution and the username fallback) is
 * covered by commonTest, leaving the UI/controller layer to only pick a file and persist the result.
 *
 * v1 scope, matching the parser: SSH-only, no secrets. `IdentityFile` is not read into the vault, so
 * every profile gets [Host.credentialId] `null` (the key/password is entered at connect time).
 */
object SshConfigImport {

    /**
     * Builds a [Host] for each [selected] alias in [hosts] (order preserved), assigning ids from
     * [newId]. [defaultUser] is the local OS user, used when the config omits `User`. ProxyJump is
     * resolved against the ids of the hosts being imported in this same batch — a jump target that
     * isn't selected leaves [Host.jumpHostId] `null` rather than a dangling reference.
     */
    fun plan(
        hosts: List<SshConfigHost>,
        selected: Set<String>,
        defaultUser: String?,
        newId: () -> String,
    ): List<Host> {
        val chosen = hosts.filter { it.alias in selected }
        // Assign every id up front so ProxyJump can reference a host that appears later in the list.
        val idByAlias = LinkedHashMap<String, String>()
        for (entry in chosen) idByAlias[entry.alias] = newId()
        return chosen.map { entry ->
            Host(
                id = idByAlias.getValue(entry.alias),
                label = entry.alias,
                address = entry.hostName,
                port = entry.port,
                username = entry.user ?: defaultUser ?: "",
                credentialId = null,
                connectionType = ConnectionType.SSH,
                // Resolve ProxyJump within the batch; drop a self-reference (an alias jumping through
                // itself) so we never persist an obviously-broken jump. Mutual cycles that survive are
                // caught at connect time by resolveJumpChain.
                jumpHostId = entry.proxyJump?.takeIf { it != entry.alias }?.let { idByAlias[it] },
            )
        }
    }
}
