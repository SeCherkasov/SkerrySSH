package app.skerry.ui.host

import app.skerry.shared.host.Host

/**
 * Чистые перестановки плоского списка профилей для ручной сортировки сайдбара (drag-and-drop).
 *
 * Источник правды по порядку — сам порядок списка хостов (как в [app.skerry.shared.host.HostStore]):
 * отдельного поля сортировки в [Host] нет, чтобы не плодить миграцию и не ломать паритет крипто/sync.
 * Папки сайдбара выводятся из [Host.group] по первому появлению ([groupHostsByFolder]); эти функции
 * держат инвариант «хосты одной группы идут непрерывным блоком», поэтому результат всегда уплощается
 * через корзины групп. Зафиксированы [app.skerry.ui.host.HostReorderingTest].
 */

/**
 * Канонический ключ группы: пустая/`null`-группа сводятся к `null`, как в [groupHostsByFolder]
 * (одна папка «Ungrouped»). Иначе `null` и `""` дали бы две корзины, и перестановка «Ungrouped»
 * сдвинула бы только часть её хостов.
 */
private fun String?.canonicalGroup(): String? = this?.takeIf { it.isNotBlank() }

/** Корзины «группа → хосты» в порядке первого появления группы (null-группа — отдельный ключ). */
private fun bucketize(hosts: List<Host>): LinkedHashMap<String?, MutableList<Host>> {
    val buckets = LinkedHashMap<String?, MutableList<Host>>()
    for (host in hosts) buckets.getOrPut(host.group.canonicalGroup()) { mutableListOf() }.add(host)
    return buckets
}

/**
 * Переставить хост [hostId] в группу [targetGroup] на позицию [targetIndexInGroup] среди её хостов.
 * Покрывает оба drag-сценария: переупорядочивание внутри папки ([targetGroup] == текущая группа) и
 * перенос в другую папку (с переписыванием [Host.group]). Индекс зажимается в допустимый диапазон;
 * опустевшая исходная группа исчезает. Неизвестный [hostId] — список без изменений.
 */
fun moveHostToGroup(
    hosts: List<Host>,
    hostId: String,
    targetGroup: String?,
    targetIndexInGroup: Int,
): List<Host> {
    val moving = hosts.firstOrNull { it.id == hostId } ?: return hosts
    val canonicalTarget = targetGroup.canonicalGroup()
    val buckets = LinkedHashMap<String?, MutableList<Host>>()
    for (host in hosts) {
        if (host.id == hostId) continue
        buckets.getOrPut(host.group.canonicalGroup()) { mutableListOf() }.add(host)
    }
    val target = buckets.getOrPut(canonicalTarget) { mutableListOf() }
    target.add(targetIndexInGroup.coerceIn(0, target.size), moving.copy(group = canonicalTarget))
    return buckets.values.flatten()
}

/**
 * Переименовать группу [oldName] → [newName] во всех профилях. Сопоставление по канону группы
 * (пустое/`null` сводится к `null`); пустой/`null` [newName] разгруппировывает хосты (`Host.group`=
 * `null`) — этим же путём «удаляется» группа: её хосты переезжают в Ungrouped, сами профили остаются.
 * Результат уплощается через корзины групп — как [moveGroup]/[moveHostToGroup], чтобы сохранить
 * инвариант «хосты одной группы идут непрерывным блоком» даже при слиянии в существующую группу.
 * Неизвестная/пустая [oldName] или совпадение old==new — список без изменений (порядок и id целы).
 */
fun renameHostGroup(hosts: List<Host>, oldName: String?, newName: String?): List<Host> {
    val from = oldName.canonicalGroup() ?: return hosts
    val to = newName.canonicalGroup()
    if (from == to) return hosts
    val renamed = hosts.map { if (it.group.canonicalGroup() == from) it.copy(group = to) else it }
    return bucketize(renamed).values.flatten()
}

/**
 * Переставить целую папку [group] на позицию [targetGroupIndex] среди папок (порядок хостов внутри
 * сохраняется). Индекс зажимается; неизвестная [group] — список без изменений.
 */
fun moveGroup(hosts: List<Host>, group: String?, targetGroupIndex: Int): List<Host> {
    val canonical = group.canonicalGroup()
    val buckets = bucketize(hosts)
    val keys = buckets.keys.toMutableList()
    val from = keys.indexOf(canonical)
    if (from < 0) return hosts
    keys.removeAt(from)
    keys.add(targetGroupIndex.coerceIn(0, keys.size), canonical)
    return keys.flatMap { buckets.getValue(it) }
}
