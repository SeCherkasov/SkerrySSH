package app.skerry.shared.tunnel

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.serialization.json.Json

/**
 * [TunnelStore] поверх зашифрованного [Vault]: каждый проброс — запись [RecordType.TUNNEL], чей
 * payload — JSON-сериализация [Tunnel] (он хранит лишь ссылку `hostId`, не секрет). Перенесён в vault
 * ради E2E-синка рабочего пространства (Phase A). По образцу [app.skerry.shared.vault.CredentialStore].
 *
 * Порядка у туннелей нет (интерфейс — set-семантика); отдаём в порядке [Vault.records]. Чтение на
 * залоченном vault — пустой список; битый payload молча пропускается.
 */
class VaultTunnelStore(private val vault: Vault) : TunnelStore {

    override fun all(): List<Tunnel> {
        if (!vault.isUnlocked) return emptyList()
        return vault.records()
            .filter { it.type == RecordType.TUNNEL && !it.deleted }
            .mapNotNull { decode(vault.openPayload(it.id)) }
    }

    override fun put(tunnel: Tunnel) {
        vault.put(tunnel.id, RecordType.TUNNEL, encode(tunnel))
    }

    override fun remove(id: String) {
        vault.remove(id)
    }

    private fun encode(tunnel: Tunnel): ByteArray = json.encodeToString(tunnel).encodeToByteArray()

    private fun decode(payload: ByteArray?): Tunnel? =
        payload?.let { runCatching { json.decodeFromString<Tunnel>(it.decodeToString()) }.getOrNull() }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
