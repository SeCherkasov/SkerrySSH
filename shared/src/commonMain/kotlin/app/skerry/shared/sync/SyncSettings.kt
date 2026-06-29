package app.skerry.shared.sync

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Что синхронизировать между устройствами — настройка УРОВНЯ АККАУНТА (одна на весь аккаунт, не на
 * устройство): хранится зашифрованной записью [RecordType.SETTINGS] в самом vault, поэтому едет тем
 * же E2E-синком и применяется одинаково везде (выключил на одном устройстве — выключилось на всех).
 *
 * Семантика OFF — «тип не участвует в синке», БЕЗ удаления (логика Termius): отключённый тип ни
 * пушится, ни принимается, но локальные записи на каждом устройстве остаются как есть, а уже залитые
 * на сервер шифроблобы просто висят (zero-knowledge — сервер их не видит). Никаких tombstone'ов по
 * выключению — иначе отключение синка стёрло бы данные на других устройствах (потеря данных).
 *
 * Группировка как в UI (секция WHAT SYNCS): «Snippets» — отдельный тумблер ([syncSnippets]); всё, что
 * образует рабочее подключение (хосты/группы/учётки/ключи/known-hosts/туннели), — под «Hosts & groups»
 * ([syncHosts]). Сама запись настроек ([RecordType.SETTINGS]) синкается ВСЕГДА — иначе выключение не
 * долетело бы до других устройств.
 */
@Serializable
data class SyncSettings(
    val syncHosts: Boolean = true,
    val syncSnippets: Boolean = true,
) {
    /** Участвует ли тип в синхронизации при текущих флагах. [RecordType.SETTINGS] — всегда. */
    fun shouldSync(type: RecordType): Boolean = when (type) {
        RecordType.SETTINGS -> true
        RecordType.SNIPPET -> syncSnippets
        RecordType.HOST,
        RecordType.GROUP,
        RecordType.IDENTITY,
        RecordType.CREDENTIAL,
        RecordType.KNOWN_HOST,
        RecordType.TUNNEL -> syncHosts
    }
}

/**
 * Чтение/запись [SyncSettings] как единственной записи [RecordType.SETTINGS] в [Vault] (singleton с
 * фиксированным [SETTINGS_ID], по образцу [app.skerry.shared.vault.WorkspaceLayoutStore]). На
 * залоченном vault [load] отдаёт дефолт (всё включено), [save] требует разблокированного vault
 * ([Vault.put]). Битый/отсутствующий payload → дефолт: новый или старый vault без записи настроек
 * синкает всё, как и было до фичи (обратная совместимость).
 */
class SyncSettingsStore(private val vault: Vault) {

    fun load(): SyncSettings {
        if (!vault.isUnlocked) return SyncSettings()
        val record = vault.records().firstOrNull { it.id == SETTINGS_ID && it.type == RecordType.SETTINGS && !it.deleted }
            ?: return SyncSettings()
        // openPayload оборачиваем: даже если реализация бросит на I/O/AEAD (а не вернёт null), синк
        // должен откатиться к «синкать всё», а не падать всем циклом (drainPull это бы прервало).
        return decode(runCatching { vault.openPayload(record.id) }.getOrNull()) ?: SyncSettings()
    }

    fun save(settings: SyncSettings) {
        vault.put(SETTINGS_ID, RecordType.SETTINGS, json.encodeToString(settings).encodeToByteArray())
    }

    private fun decode(payload: ByteArray?): SyncSettings? =
        payload?.let { runCatching { json.decodeFromString<SyncSettings>(it.decodeToString()) }.getOrNull() }

    companion object {
        /** Стабильный id singleton-записи настроек синка в vault. */
        const val SETTINGS_ID = "sync.settings"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
