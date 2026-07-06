package app.skerry.shared.tunnel

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec

/**
 * [TunnelStore] over an encrypted [Vault]: each forward is a [RecordType.TUNNEL] record whose
 * payload is a JSON serialization of [Tunnel] (it holds only the `hostId` reference, not the
 * secret). Modeled on [app.skerry.shared.vault.CredentialStore].
 *
 * Tunnels have no order (the interface has set semantics); returned in [Vault.records] order.
 * Reading a locked vault yields an empty list; a corrupt payload is silently skipped.
 */
class VaultTunnelStore(private val vault: Vault) : TunnelStore {

    private val codec = VaultRecordCodec(vault, RecordType.TUNNEL, Tunnel.serializer())

    override fun all(): List<Tunnel> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list()
    }

    override fun put(tunnel: Tunnel) {
        codec.put(tunnel.id, tunnel)
    }

    override fun remove(id: String) {
        codec.remove(id)
    }
}
