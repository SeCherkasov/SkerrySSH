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
