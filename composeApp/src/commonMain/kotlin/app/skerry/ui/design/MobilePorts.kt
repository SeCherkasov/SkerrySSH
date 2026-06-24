package app.skerry.ui.design

import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.tunnel.TunnelEntry
import app.skerry.ui.tunnel.TunnelStatus

/** Иконка-стрелка карточки туннеля: динамический (`-D`) → `all_inclusive`, иначе `arrow_forward`. */
fun mobileTunnelArrow(direction: TunnelDirection): String =
    if (direction == TunnelDirection.Dynamic) "all_inclusive" else "arrow_forward"

/**
 * Текст назначения карточки: явный `host:port` либо `dynamic proxy` для `-D` (у SOCKS фиксированного
 * назначения нет — его задаёт клиент), как в макете.
 */
fun mobileTunnelDest(tunnel: Tunnel): String =
    if (tunnel.direction == TunnelDirection.Dynamic) "dynamic proxy" else "${tunnel.destHost}:${tunnel.destPort}"

/** Число активных (включённых) сохранённых туннелей — для подзаголовка строки Port forwarding в More. */
fun mobileActiveTunnelCount(tunnels: List<TunnelEntry>): Int =
    tunnels.count { it.status is TunnelStatus.Active }

/**
 * Подзаголовок строки Port forwarding в More: число активных туннелей подключённой сессии, либо
 * пустая строка, если активной сессии нет ([count]=null) — нечего считать (честная проекция,
 * в отличие от статичного «2 active» макета).
 */
fun mobileMorePortsSubtitle(count: Int?): String = if (count == null) "" else "$count active"

/** Подзаголовок строки Known hosts в More: число незакрытых смен ключа, либо «All verified», если их нет. */
fun mobileMoreKnownSubtitle(changed: Int): String =
    if (changed == 0) "All verified" else "$changed changed"
