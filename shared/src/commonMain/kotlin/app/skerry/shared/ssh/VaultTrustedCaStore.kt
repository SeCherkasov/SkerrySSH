package app.skerry.shared.ssh

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec

/**
 * [TrustedCaStore] over an encrypted [Vault]: each authority is a [RecordType.TRUSTED_CA] record
 * whose payload is the JSON serialization of [TrustedCa]. Backed by the vault so the decision to
 * trust a CA syncs across devices, like [VaultKnownHostsStore] — a CA covers a whole fleet, so
 * re-entering it per device would be the same key pasted three times.
 *
 * Read from sshj's IO thread during the handshake ([HostCertificateVerifier]), so a locked vault
 * must surface as *unreadable* ([allOrNull] == `null`, fail closed) rather than as an empty list;
 * [all] maps it to an empty list for the manager UI. Writes on a locked vault are no-ops for the
 * same reason as in [VaultKnownHostsStore]: auto-lock can fire between the screen opening and the
 * user pressing Add.
 */
class VaultTrustedCaStore(private val vault: Vault) : TrustedCaStore {

    private val codec = VaultRecordCodec(vault, RecordType.TRUSTED_CA, TrustedCa.serializer())

    override fun all(): List<TrustedCa> = allOrNull() ?: emptyList()

    override fun allOrNull(): List<TrustedCa>? {
        if (!vault.isUnlocked) return null
        // Auto-lock can fire between the check and the read; a throw from records()/openPayload()
        // means "unreadable", not "empty", and must not escape into sshj's IO thread.
        return runCatching { codec.list() }.getOrNull()
    }

    override fun put(ca: TrustedCa) {
        if (!vault.isUnlocked) return
        codec.put(ca.id, ca)
    }

    override fun remove(id: String) {
        if (!vault.isUnlocked) return
        codec.remove(id)
    }
}
