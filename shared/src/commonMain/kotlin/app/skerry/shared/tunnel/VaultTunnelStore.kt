package app.skerry.shared.tunnel

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec

/**
 * [TunnelStore] поверх зашифрованного [Vault]: каждый проброс — запись [RecordType.TUNNEL], чей
 * payload — JSON-сериализация [Tunnel] (он хранит лишь ссылку `hostId`, не секрет). Перенесён в vault
 * ради E2E-синка рабочего пространства (Phase A). По образцу [app.skerry.shared.vault.CredentialStore].
 *
 * Порядка у туннелей нет (интерфейс — set-семантика); отдаём в порядке [Vault.records]. Чтение на
 * залоченном vault — пустой список; битый payload молча пропускается.
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
