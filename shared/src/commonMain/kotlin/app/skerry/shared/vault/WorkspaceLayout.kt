package app.skerry.shared.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Структура рабочего пространства, синхронизируемая как одна запись vault: порядок хостов в дереве
 * и список групп (включая пустые папки и их порядок). Хранится как единственный [RecordType.GROUP]
 * с зарезервированным id [WorkspaceLayoutStore.LAYOUT_ID] — поэтому участвует в обычном LWW-синке
 * (`docs/skerry-sync-design.md`): дерево хостов одинаково на всех устройствах.
 *
 * Само членство хоста в группе живёт в [app.skerry.shared.host.Host.group]; здесь — только порядок
 * (хостов и групп) и существование пустых групп, которые иначе негде хранить (у пустой группы нет
 * ни одного хоста). Per-device UI-состояние (свёрнутость папок, недавние подключения) сюда НЕ входит —
 * оно остаётся локальным, не синкается.
 */
@Serializable
data class WorkspaceLayout(
    /** Глобальный порядок id хостов в дереве. Хосты вне списка дорисовываются в конце. */
    val hostOrder: List<String> = emptyList(),
    /** Имена групп в порядке отображения, включая пустые папки. Пустой → порядок выводится из хостов. */
    val groups: List<String> = emptyList(),
)

/**
 * Единственный владелец записи [WorkspaceLayout] в [vault]. И [VaultHostStore], и слой групп пишут
 * макет через один и тот же экземпляр, чтобы read-modify-write не затирал чужое поле (UI сериализует
 * вызовы — гонок между хостами и группами на главном потоке нет). Залоченный/отсутствующий vault →
 * пустой макет.
 *
 * Порядок хостов и пустые папки намеренно в ОДНОЙ записи: при синке она применяется как единое целое
 * (LWW по версии), поэтому дерево хостов переезжает между устройствами атомарно. Следствие: локальная
 * правка макета, совпавшая по времени с приходом удалённой версии этой же записи (фоновый
 * [app.skerry.shared.sync.SyncEngine]), разрешается last-writer-wins по всей записи — это by design,
 * а не потеря данных конкретного поля.
 */
class WorkspaceLayoutStore(private val vault: Vault) {

    fun read(): WorkspaceLayout {
        if (!vault.isUnlocked) return WorkspaceLayout()
        val record = vault.records().firstOrNull { it.id == LAYOUT_ID && it.type == RecordType.GROUP && !it.deleted }
            ?: return WorkspaceLayout()
        val payload = vault.openPayload(record.id) ?: return WorkspaceLayout()
        return runCatching { json.decodeFromString<WorkspaceLayout>(payload.decodeToString()) }.getOrDefault(WorkspaceLayout())
    }

    fun write(layout: WorkspaceLayout) {
        vault.put(LAYOUT_ID, RecordType.GROUP, json.encodeToString(layout).encodeToByteArray())
    }

    companion object {
        /** Зарезервированный id записи-макета. Не пересекается с UUID-id хостов/групп. */
        const val LAYOUT_ID = "skerry.workspace.layout"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
