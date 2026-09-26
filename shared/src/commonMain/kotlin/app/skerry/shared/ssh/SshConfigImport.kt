package app.skerry.shared.ssh

import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret

/**
 * What an `ssh_config` import will create: [hosts] and the file-backed [credentials] they reference.
 * Both carry final ids, so the caller only persists them (credentials first — hosts point at them).
 */
data class SshConfigImportPlan(
    val hosts: List<Host>,
    val credentials: List<Credential>,
)

/**
 * Turns entries parsed from `ssh_config` into ready-to-save profiles. Pure and platform-independent
 * so the mapping (ProxyJump resolution, username fallback, key binding) is covered by commonTest,
 * leaving the UI/controller layer to only pick a file and persist the result.
 *
 * `IdentityFile` becomes a [CredentialSecret.KeyFile]: the key stays where OpenSSH keeps it and is
 * read at connect time, which is also what makes a `CertificateFile` issued for a few hours usable.
 * No key material is copied into the vault — only the location, so an import can't quietly duplicate
 * a private key into a synced store.
 */
object SshConfigImport {

    /**
     * Plans the [selected] aliases in [hosts] (order preserved), assigning ids from [newId].
     * [defaultUser] is the local OS user, used when the config omits `User`. ProxyJump is resolved
     * against the ids of the hosts in this same batch — a jump target that isn't selected leaves
     * [Host.jumpHostId] `null` rather than a dangling reference.
     *
     * Hosts naming the same (key, certificate) pair — compared literally after `%` token expansion
     * ([keyRefs]), as OpenSSH itself compares `IdentityFile` values — share one credential. Labels are derived
     * from the file name and made unique against each other and [existingLabels] — snippets address
     * secrets by label (`${{vault:…}}`), so a collision would silently retarget them.
     */
    fun plan(
        hosts: List<SshConfigHost>,
        selected: Set<String>,
        defaultUser: String?,
        newId: () -> String,
        existingLabels: Set<String> = emptySet(),
    ): SshConfigImportPlan {
        val chosen = hosts.filter { it.alias in selected }
        // Assign every id up front so ProxyJump can reference a host that appears later in the list.
        val idByAlias = LinkedHashMap<String, String>()
        for (entry in chosen) idByAlias[entry.alias] = newId()

        val credentials = LinkedHashMap<KeyRefs, Credential>()
        val takenLabels = existingLabels.toMutableSet()
        for (entry in chosen) {
            val refs = keyRefs(entry, defaultUser) ?: continue
            val key = refs.key
            credentials.getOrPut(refs) {
                Credential(
                    id = newId(),
                    label = uniqueLabel(labelFor(key), takenLabels).also { takenLabels += it },
                    secret = CredentialSecret.KeyFile(refs.key, refs.certificate),
                )
            }
        }

        val planned = chosen.map { entry ->
            val refs = keyRefs(entry, defaultUser)
            Host(
                id = idByAlias.getValue(entry.alias),
                label = entry.alias,
                address = entry.hostName,
                port = entry.port,
                username = entry.user ?: defaultUser ?: "",
                credentialId = refs?.let { credentials[it]?.id },
                connectionType = ConnectionType.SSH,
                // Resolve ProxyJump within the batch; drop a self-reference (an alias jumping through
                // itself) so we never persist an obviously-broken jump. Mutual cycles that survive are
                // caught at connect time by resolveJumpChain.
                jumpHostId = entry.proxyJump?.takeIf { it != entry.alias }?.let { idByAlias[it] },
            )
        }
        return SshConfigImportPlan(planned, credentials.values.toList())
    }

    /** Key and certificate locations together — the identity of a credential within one import. */
    private data class KeyRefs(val key: String, val certificate: String?)

    /**
     * [entry]'s key and certificate locations with the `%` tokens OpenSSH would expand at connect
     * time and that are already known here: `%h` the resolved host, `%n` the alias, `%p` the port,
     * `%r` the remote user, `%u` the local user ([localUser]), and `%d` the local home as `~`, which
     * the file reader resolves on the device that reads the key. Null when there is no key.
     */
    private fun keyRefs(entry: SshConfigHost, localUser: String?): KeyRefs? {
        val key = entry.identityFile?.takeIf { it.isNotBlank() } ?: return null
        val tokens = buildMap {
            put('h', entry.hostName)
            put('n', entry.alias)
            put('p', entry.port.toString())
            put('d', "~")
            (entry.user ?: localUser)?.let { put('r', it) }
            localUser?.let { put('u', it) }
        }
        return KeyRefs(
            expandSshTokens(key, tokens),
            entry.certificateFile?.takeIf { it.isNotBlank() }?.let { expandSshTokens(it, tokens) },
        )
    }

    /** File name of a ref, without directories: `~/.ssh/id_ed25519` → `id_ed25519`. */
    private fun labelFor(ref: String): String =
        ref.trimEnd('/').substringAfterLast('/').substringAfterLast('\\').ifBlank { "key" }

    /** [base], or `base (2)`, `base (3)`… until it doesn't clash with [taken]. */
    private fun uniqueLabel(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }
}
