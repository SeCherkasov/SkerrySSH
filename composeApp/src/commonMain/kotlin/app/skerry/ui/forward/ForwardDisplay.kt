package app.skerry.ui.forward

/**
 * Чистые хелперы представления одного проброса в виде колонок таблицы туннелей нового дизайна
 * (`TunnelsView`). Вынесены отдельно от [forwardRouteText] (он склеивает source→dest в одну строку
 * для списков mobile/старого экрана), потому что таблица desktop показывает source и destination
 * раздельными ячейками. Общий источник правды — чтобы формат не разъезжался между представлениями.
 */

/** Метка типа проброса для бейджа таблицы: `-L`→LOCAL, `-R`→REMOTE, `-D`→SOCKS. */
fun forwardTypeLabel(direction: ForwardDirection): String = when (direction) {
    ForwardDirection.Local -> "LOCAL"
    ForwardDirection.Remote -> "REMOTE"
    ForwardDirection.Dynamic -> "SOCKS"
}

/**
 * Порт слушателя: фактический ([ForwardStatus.Active.boundPort]) после поднятия, иначе запрошенный
 * ([ForwardEntry.requestedPort]) — пока статус Starting/Failed (реального порта ещё нет).
 */
fun forwardListenPort(entry: ForwardEntry): Int =
    (entry.status as? ForwardStatus.Active)?.boundPort ?: entry.requestedPort

/**
 * Адрес-источник (сторона слушателя). Для `-L`/`-D` слушатель на этой машине ([ForwardEntry.bindHost]);
 * для `-R` слушатель поднимает сервер, поэтому хост показываем как `server`.
 */
fun forwardSourceText(entry: ForwardEntry): String {
    val port = forwardListenPort(entry)
    return when (entry.direction) {
        ForwardDirection.Remote -> "server:$port"
        else -> "${entry.bindHost}:$port"
    }
}

/**
 * Адрес назначения для ячейки DESTINATION, или `null` для динамического (`-D`) проброса — у него
 * назначение задаёт клиент SOCKS в момент соединения, фиксированного destination нет.
 */
fun forwardDestText(entry: ForwardEntry): String? = when (entry.direction) {
    ForwardDirection.Dynamic -> null
    else -> "${entry.destHost}:${entry.destPort}"
}
