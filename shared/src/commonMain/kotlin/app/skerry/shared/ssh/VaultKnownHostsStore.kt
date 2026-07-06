package app.skerry.shared.ssh

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec

/**
 * [KnownHostsStore] over an encrypted [Vault]: each trusted key is a [RecordType.KNOWN_HOST]
 * record whose payload is the JSON serialization of [KnownHost]. Backed by the vault so TOFU
 * trust in host keys syncs across devices: connecting to a host on one device means other devices
 * won't need to reconfirm the key.
 *
 * Record id is deterministic from the key identity (host, port, keyType), so [replace] upserts the
 * same record rather than creating a new one, and [remove] addresses the same record. The
 * [RecordType.KNOWN_HOST] type isolates these ids from host UUIDs even on a textual collision (AAD
 * is bound to id‖type, and [all] filters by type).
 *
 * Called from sshj's IO thread on connect (the vault is unlocked by then, since connect only
 * happens from an open UI). Reading a locked vault returns an empty list (safe no-op). Does not
 * store key-change events ([HostKeyMismatch]) — that's a local, non-synced signal (see the file store).
 */
class VaultKnownHostsStore(private val vault: Vault) : KnownHostsStore {

    private val codec = VaultRecordCodec(vault, RecordType.KNOWN_HOST, KnownHost.serializer())

    override fun all(): List<KnownHost> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list()
    }

    override fun add(host: KnownHost) {
        // No-op on a locked vault (symmetric with [all]): the TOFU write happens on sshj's IO
        // thread during connect(), and an auto-lock timeout could fire at that exact moment, which
        // would otherwise throw IllegalStateException mid-handshake. Failing to remember the key
        // is safer than dropping the connection: the next connect just re-asks TOFU.
        if (!vault.isUnlocked) return
        codec.put(idOf(host.host, host.port, host.keyType), host)
    }

    override fun replace(host: KnownHost) {
        // Same identity -> same id -> upsert.
        if (!vault.isUnlocked) return // see [add]: no-op on a locked vault instead of throwing in connect()
        codec.put(idOf(host.host, host.port, host.keyType), host)
    }

    override fun remove(host: String, port: Int, keyType: String) {
        if (!vault.isUnlocked) return // see [add]: no-op on a locked vault
        codec.remove(idOf(host, port, keyType))
    }

    private companion object {
        /** Deterministic id from the key identity triple. U+001F separator avoids collisions. */
        fun idOf(host: String, port: Int, keyType: String): String = "$host\u001F$port\u001F$keyType"
    }
}
