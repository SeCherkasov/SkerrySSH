package app.skerry.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore

/**
 * Редактируемые поля профиля без [Host.id]: форма создания/правки оперирует черновиком,
 * а идентичность присваивает [HostManagerController]. [id] == null — создаётся новый хост,
 * иначе обновляется существующий.
 */
data class HostDraft(
    val id: String? = null,
    val label: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val group: String? = null,
    val credentialId: String? = null,
)

/**
 * Состояние менеджера хостов поверх [HostStore]: держит список профилей как Compose-state
 * и сводит мутации к стору, перечитывая [hosts] после каждой. Генерация id инъектируется
 * ([newId]) — в тестах детерминирована, на платформе подставляется UUID-генератор.
 *
 * Хранилище синхронно (мутации редки, инициируются из UI), поэтому контроллер не держит
 * собственную корутинную scope — в отличие от [app.skerry.ui.connection.ConnectionController],
 * где живёт поток вывода терминала.
 */
@Stable
class HostManagerController(
    private val store: HostStore,
    private val newId: () -> String,
) {
    var hosts by mutableStateOf(store.all())
        private set

    fun find(id: String): Host? = hosts.firstOrNull { it.id == id }

    /**
     * Перечитать список из стора. Нужно после записей в обход контроллера (например, миграция vault
     * пишет перенаправленные [Host.credentialId] прямо в [HostStore] при unlock).
     */
    fun reload() {
        hosts = store.all()
    }

    /**
     * Создать (если [HostDraft.id] == null) или обновить профиль и перечитать список.
     * Возвращает назначенный id — для нового хоста это сгенерированный [newId], чтобы
     * вызывающий мог выделить только что созданную запись.
     */
    fun save(draft: HostDraft): String {
        val id = draft.id ?: newId()
        store.put(
            Host(
                id = id,
                label = draft.label,
                address = draft.address,
                port = draft.port,
                username = draft.username,
                group = draft.group,
                credentialId = draft.credentialId,
            ),
        )
        hosts = store.all()
        return id
    }

    fun delete(id: String) {
        store.remove(id)
        hosts = store.all()
    }
}
