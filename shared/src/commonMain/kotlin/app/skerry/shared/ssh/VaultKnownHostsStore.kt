package app.skerry.shared.ssh

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec

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

    private val codec = VaultRecordCodec(vault, RecordType.KNOWN_HOST, KnownHost.serializer())

    override fun all(): List<KnownHost> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list()
    }

    override fun add(host: KnownHost) {
        // no-op на залоченном vault (симметрично [all]): TOFU-запись идёт из IO-потока sshj во время
        // connect(), а auto-lock по таймауту может сработать ровно в этот момент — тогда vault.put бросил
        // бы IllegalStateException прямо в стадию рукопожатия SSH (мимо контракта connect, с утечкой
        // клиента). Не запомнить ключ безопаснее, чем уронить коннект: следующий connect переспросит TOFU.
        if (!vault.isUnlocked) return
        codec.put(idOf(host.host, host.port, host.keyType), host)
    }

    override fun replace(host: KnownHost) {
        // Та же идентичность → тот же id → upsert. Окна «записи нет» не возникает (put атомарен).
        if (!vault.isUnlocked) return // см. [add]: no-op на залоченном vault вместо броска в connect()
        codec.put(idOf(host.host, host.port, host.keyType), host)
    }

    override fun remove(host: String, port: Int, keyType: String) {
        if (!vault.isUnlocked) return // см. [add]: no-op на залоченном vault
        codec.remove(idOf(host, port, keyType))
    }

    private companion object {
        /** Детерминированный id из тройки идентичности ключа. U+001F-разделитель исключает коллизии. */
        fun idOf(host: String, port: Int, keyType: String): String = "$host\u001F$port\u001F$keyType"
    }
}
