package app.skerry.ui.mobile

import androidx.compose.runtime.Composable
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_all_verified
import app.skerry.ui.generated.resources.ptail_changed
import app.skerry.ui.generated.resources.ptail_dynamic_proxy
import app.skerry.ui.generated.resources.ptail_ports_active
import app.skerry.ui.tunnel.TunnelEntry
import app.skerry.ui.tunnel.TunnelStatus
import org.jetbrains.compose.resources.stringResource

/** Иконка-стрелка карточки туннеля: динамический (`-D`) → `all_inclusive`, иначе `arrow_forward`. */
fun mobileTunnelArrow(direction: TunnelDirection): String =
    if (direction == TunnelDirection.Dynamic) "all_inclusive" else "arrow_forward"

/**
 * Текст назначения карточки: явный `host:port` либо `dynamic proxy` для `-D` (у SOCKS фиксированного
 * назначения нет — его задаёт клиент), как в макете.
 */
@Composable
fun mobileTunnelDest(tunnel: Tunnel): String =
    if (tunnel.direction == TunnelDirection.Dynamic) stringResource(Res.string.ptail_dynamic_proxy)
    else "${tunnel.destHost}:${tunnel.destPort}"

/** Число активных (включённых) сохранённых туннелей — для подзаголовка строки Port forwarding в More. */
fun mobileActiveTunnelCount(tunnels: List<TunnelEntry>): Int =
    tunnels.count { it.status is TunnelStatus.Active }

/**
 * Подзаголовок строки Port forwarding в More: число активных туннелей подключённой сессии, либо
 * пустая строка, если активной сессии нет ([count]=null) — нечего считать (честная проекция,
 * в отличие от статичного «2 active» макета).
 */
@Composable
fun mobileMorePortsSubtitle(count: Int?): String =
    if (count == null) "" else stringResource(Res.string.ptail_ports_active, count)

/** Подзаголовок строки Known hosts в More: число незакрытых смен ключа, либо «All verified», если их нет. */
@Composable
fun mobileMoreKnownSubtitle(changed: Int): String =
    if (changed == 0) stringResource(Res.string.ptail_all_verified)
    else stringResource(Res.string.ptail_changed, changed)
