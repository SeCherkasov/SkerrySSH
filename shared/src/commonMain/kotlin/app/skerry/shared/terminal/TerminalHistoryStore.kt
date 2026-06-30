package app.skerry.shared.terminal

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Персист истории команд терминала per-host. Ключ [key] — стабильный идентификатор хоста
 * (протокол+учётка+адрес+порт), команды хранятся от новой к старой. Реализация поверх зашифрованного
 * vault ([VaultTerminalHistoryStore]) даёт E2E-шифрование покоя; в тестах — in-memory.
 *
 * БЕЗОПАСНОСТЬ: сюда доходит только то, что пользователь ввёл в режиме с эхом — ввод пароля/passphrase
 * (без эха) отсекается выше по сигналу [app.skerry.shared.ssh.ShellChannel.echoSuppressed]
 * (см. `TerminalScreenState.typeInput`). Персист именно в vault (а не открытый файл) снимает прежний
 * запрет на дисковую историю: секреты, если и попадут (SSH-эхо недоступен), лежат под тем же
 * шифрованием, что и хосты/ключи.
 */
interface TerminalHistoryStore {
    /** История для [key] от новой к старой; пустой список, если ничего не сохранено/vault залочен. */
    fun load(key: String): List<String>

    /** Сохранить [commands] (от новой к старой) для [key]; тихий no-op при залоченном vault. */
    fun save(key: String, commands: List<String>)
}

/**
 * [TerminalHistoryStore] поверх [Vault]: одна запись [RecordType.TERMINAL_HISTORY] на хост-ключ,
 * payload — JSON [TerminalHistoryRecord]. Id записи детерминирован по [key] → повторная запись это
 * upsert той же записи (а не новая). Тип [RecordType.TERMINAL_HISTORY] исключён из синка
 * ([app.skerry.shared.sync.SyncSettings.shouldSync]) — история остаётся локальной для устройства.
 * Список кэпается [cap] при сохранении (защита от неограниченного роста записи).
 */
class VaultTerminalHistoryStore(
    private val vault: Vault,
    private val cap: Int = 300,
) : TerminalHistoryStore {

    override fun load(key: String): List<String> {
        if (!vault.isUnlocked) return emptyList()
        val id = idOf(key)
        val record = vault.records()
            .firstOrNull { it.id == id && it.type == RecordType.TERMINAL_HISTORY && !it.deleted }
            ?: return emptyList()
        return decode(vault.openPayload(record.id))?.commands ?: emptyList()
    }

    override fun save(key: String, commands: List<String>) {
        if (!vault.isUnlocked) return
        val capped = commands.take(cap)
        vault.put(idOf(key), RecordType.TERMINAL_HISTORY, encode(TerminalHistoryRecord(key, capped)))
    }

    private fun idOf(key: String): String = "$ID_PREFIX$key"

    private fun encode(record: TerminalHistoryRecord): ByteArray =
        json.encodeToString(record).encodeToByteArray()

    private fun decode(payload: ByteArray?): TerminalHistoryRecord? =
        payload?.let { runCatching { json.decodeFromString<TerminalHistoryRecord>(it.decodeToString()) }.getOrNull() }

    private companion object {
        const val ID_PREFIX = "termhist:"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** payload записи истории: [key] хоста + команды от новой к старой. */
@Serializable
data class TerminalHistoryRecord(val key: String, val commands: List<String>)

/** Стабильный ключ истории для целевого хоста: протокол|учётка@адрес:порт (см. `SshTarget`). */
fun terminalHistoryKey(connectionType: String, username: String, host: String, port: Int): String =
    "$connectionType|$username@$host:$port"
