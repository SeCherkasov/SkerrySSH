package app.skerry.shared.host

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec
import app.skerry.shared.vault.WorkspaceLayoutStore

/**
 * [HostStore] поверх зашифрованного [Vault]: каждый профиль — запись [RecordType.HOST], чей payload —
 * JSON-сериализация [Host] (адрес/логин/группа/теги внутри зашифрованного blob). Порядок дерева
 * хранится отдельно в [WorkspaceLayout] (одна запись), чтобы переживать LWW-синк детерминированно —
 * полагаться на порядок [Vault.records] нельзя (put существующей записи не перемещает её, а merge
 * приходит в порядке server_seq). По образцу [app.skerry.shared.vault.CredentialStore].
 *
 * Требует разблокированного vault для мутаций (как [CredentialStore]). Чтение [all] на залоченном
 * vault безопасно отдаёт пустой список: контроллер строится до ввода мастер-пароля и перечитывает
 * данные через `reload()` уже после unlock. Битый/непарсящийся профиль молча пропускается — одна
 * повреждённая запись не валит список.
 */
class VaultHostStore(
    private val vault: Vault,
    private val layout: WorkspaceLayoutStore = WorkspaceLayoutStore(vault),
) : HostStore {

    private val codec = VaultRecordCodec(vault, RecordType.HOST, Host.serializer())

    override fun all(): List<Host> {
        if (!vault.isUnlocked) return emptyList()
        val hosts = codec.list()
        val order = layout.read().hostOrder
        val rank = order.withIndex().associate { (i, id) -> id to i }
        // Стабильная сортировка: хосты вне порядка (новые/синканутые) сохраняют относительный
        // порядок records() и дорисовываются в конце.
        return hosts.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    override fun put(host: Host) = vault.transaction {
        // Профиль и read-modify-write макета — под одной блокировкой vault (как [reorder]): иначе
        // конкурентный mergeRemote из фонового sync, попавший между read() и write(), был бы затёрт.
        codec.put(host.id, host)
        val current = layout.read()
        if (host.id !in current.hostOrder) {
            layout.write(current.copy(hostOrder = current.hostOrder + host.id))
        }
    }

    override fun remove(id: String) = vault.transaction {
        // См. [put]: обновление макета атомарно с удалением записи.
        codec.remove(id)
        val current = layout.read()
        if (id in current.hostOrder) {
            layout.write(current.copy(hostOrder = current.hostOrder - id))
        }
    }

    override fun reorder(transform: (List<Host>) -> List<Host>) = vault.transaction {
        // Всё чтение-вычисление-запись под одной блокировкой vault: иначе конкурентный mergeRemote из
        // фонового sync, попавший между снимком all() и записью ниже, был бы затёрт устаревшим порядком.
        val current = all()
        val updated = transform(current)
        // Размер + множество id: одно равенство множеств пропустило бы дубликат (например [A,B,C,A]),
        // который затем испортил бы hostOrder и порядок дерева (associate берёт последний индекс id).
        require(updated.size == current.size && updated.map { it.id }.toSet() == current.map { it.id }.toSet()) {
            "reorder must preserve the id set (had ${current.size}, got ${updated.size})"
        }
        // Переписываем только профили с изменившимся содержимым (например, group при moveHostToGroup/
        // renameGroup) — чистая перестановка не должна бампать version каждой записи (лишний синк-трафик).
        val byId = current.associateBy { it.id }
        for (host in updated) {
            if (byId[host.id] != host) codec.put(host.id, host)
        }
        layout.write(layout.read().copy(hostOrder = updated.map { it.id }))
    }
}
