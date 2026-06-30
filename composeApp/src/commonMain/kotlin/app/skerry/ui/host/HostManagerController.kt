package app.skerry.ui.host

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.host.Host
import app.skerry.shared.host.HostStore
import app.skerry.shared.ssh.ConnectionType

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
    val tags: List<String> = emptyList(),
    val aiPolicy: AiPolicy = AiPolicy.Strict,
    val connectionType: ConnectionType = ConnectionType.SSH,
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
                tags = draft.tags,
                aiPolicy = draft.aiPolicy,
                connectionType = draft.connectionType,
            ),
        )
        hosts = store.all()
        return id
    }

    fun delete(id: String) {
        store.remove(id)
        hosts = store.all()
    }

    /**
     * Ручная сортировка (drag-and-drop): переставить хост [hostId] в папку [targetGroup] на позицию
     * [targetIndexInGroup] среди её хостов. Покрывает и переупорядочивание внутри папки, и перенос в
     * другую (с переписыванием [Host.group]). Пересчёт — чистой [moveHostToGroup], фиксация — атомарным
     * [HostStore.replaceAll].
     */
    fun moveHost(hostId: String, targetGroup: String?, targetIndexInGroup: Int) {
        // Пересчёт внутри store.reorder — над актуальным снимком стора под его блокировкой, а не над
        // (потенциально устаревшим) Compose-state hosts; иначе гонка с конкурентной записью (миграция).
        store.reorder { moveHostToGroup(it, hostId, targetGroup, targetIndexInGroup) }
        hosts = store.all()
    }

    /** Ручная сортировка: переставить целую папку [group] на позицию [targetGroupIndex] среди папок. */
    fun moveFolder(group: String?, targetGroupIndex: Int) {
        store.reorder { moveGroup(it, group, targetGroupIndex) }
        hosts = store.all()
    }

    /**
     * Переименовать группу [oldName] → [newName] во всех профилях. Чистый пересчёт [renameHostGroup]
     * под блокировкой стора (как и прочие сортировки); набор id сохраняется. Side-channel пустых/
     * схлопнутых групп правит вызывающий UI отдельно.
     */
    fun renameGroup(oldName: String, newName: String) {
        store.reorder { renameHostGroup(it, oldName, newName) }
        hosts = store.all()
    }

    /**
     * «Удалить» группу [name]: её хосты разгруппировываются (`Host.group`=`null`, переезжают в
     * Ungrouped) — сами профили и их секреты не трогаются. Реализация — [renameHostGroup] в `null`.
     */
    fun deleteGroup(name: String) {
        store.reorder { renameHostGroup(it, name, null) }
        hosts = store.all()
    }
}
