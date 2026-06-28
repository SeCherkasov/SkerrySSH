package app.skerry.shared.ssh

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.serialization.json.Json

/**
 * [KnownHostsStore] поверх зашифрованного [Vault]: каждый доверенный ключ — запись
 * [RecordType.KNOWN_HOST], чей payload — JSON-сериализация [KnownHost]. Перенесён в vault, чтобы
 * TOFU-доверие к ключам хостов синхронизировалось между устройствами (Phase A, «как в Termius»):
 * подключившись к хосту на одном устройстве, на других не придётся подтверждать ключ заново.
 *
 * id записи детерминирован по идентичности ключа (host, port, keyType) → [replace] это upsert той же
 * записи (а не новая), [remove] адресует её же. Тип [RecordType.KNOWN_HOST] изолирует эти id от
 * UUID-id хостов даже при текстовом совпадении (AAD привязан к id‖type, а [all] фильтрует по типу).
 *
 * Вызывается из IO-потока sshj при подключении (vault к этому моменту разблокирован — коннект идёт
 * из открытого UI). Чтение на залоченном vault — пустой список (безопасный no-op). НЕ хранит события
 * смены ключа ([HostKeyMismatch]) — это локальный, несинхронизируемый сигнал (см. файловый стор).
 */
class VaultKnownHostsStore(private val vault: Vault) : KnownHostsStore {

    override fun all(): List<KnownHost> {
        if (!vault.isUnlocked) return emptyList()
        return vault.records()
            .filter { it.type == RecordType.KNOWN_HOST && !it.deleted }
            .mapNotNull { decode(vault.openPayload(it.id)) }
    }

    override fun add(host: KnownHost) {
        vault.put(idOf(host.host, host.port, host.keyType), RecordType.KNOWN_HOST, encode(host))
    }

    override fun replace(host: KnownHost) {
        // Та же идентичность → тот же id → upsert. Окна «записи нет» не возникает (put атомарен).
        vault.put(idOf(host.host, host.port, host.keyType), RecordType.KNOWN_HOST, encode(host))
    }

    override fun remove(host: String, port: Int, keyType: String) {
        vault.remove(idOf(host, port, keyType))
    }

    private fun encode(host: KnownHost): ByteArray = json.encodeToString(host).encodeToByteArray()

    private fun decode(payload: ByteArray?): KnownHost? =
        payload?.let { runCatching { json.decodeFromString<KnownHost>(it.decodeToString()) }.getOrNull() }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Детерминированный id из тройки идентичности ключа. U+001F-разделитель исключает коллизии. */
        fun idOf(host: String, port: Int, keyType: String): String = "$host\u001F$port\u001F$keyType"
    }
}
