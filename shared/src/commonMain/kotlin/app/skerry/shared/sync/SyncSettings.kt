package app.skerry.shared.sync

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultSingletonStore
import kotlinx.serialization.Serializable

/**
 * Что синхронизировать между устройствами — настройка УРОВНЯ АККАУНТА (одна на весь аккаунт, не на
 * устройство): хранится зашифрованной записью [RecordType.SETTINGS] в самом vault, поэтому едет тем
 * же E2E-синком и применяется одинаково везде (выключил на одном устройстве — выключилось на всех).
 *
 * Семантика OFF — «тип не участвует в синке», БЕЗ удаления (привычная логика SSH-клиентов): отключённый тип ни
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
        // История команд терминала — локальная (per-host, зашифрована в vault), но НЕ синкается:
        // объёмна, чувствительна и завязана на устройство. Сознательно исключена из WHAT SYNCS.
        RecordType.TERMINAL_HISTORY -> false
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

    // Битый payload/бросок openPayload → дефолт «синкать всё» (см. VaultSingletonStore): цикл sync
    // не должен падать из-за нечитаемой записи настроек (drainPull это бы прервало).
    private val store = VaultSingletonStore(vault, SETTINGS_ID, RecordType.SETTINGS, SyncSettings.serializer()) {
        SyncSettings()
    }

    fun load(): SyncSettings = store.load()

    fun save(settings: SyncSettings) {
        store.save(settings)
    }

    companion object {
        /** Стабильный id singleton-записи настроек синка в vault. */
        const val SETTINGS_ID = "sync.settings"
    }
}
