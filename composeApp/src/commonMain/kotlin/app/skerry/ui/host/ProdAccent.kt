package app.skerry.ui.host

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skerry.shared.guard.ProductionGuard
import app.skerry.shared.host.Host
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.design.Badge
import app.skerry.ui.theme.Skerry
import androidx.compose.runtime.remember

/**
 * Label on the production badge. Not localized on purpose: it names the `prod` tag, which is stored
 * data shared across clients and locales — a translated badge would read as a different marker.
 */
const val PROD_BADGE = "PROD"

/** Whether [host] is production (carries the `prod` tag). */
fun isProdHost(host: Host?): Boolean = host != null && ProductionGuard.isProduction(host.tags)

/**
 * Whether the catalog profile [hostId] is production. For call sites that only hold an id (session
 * tabs, terminal chrome); resolves through [LocalHosts], so it follows a tag edit live. Ad-hoc
 * sessions with no saved profile (`null`) are never production.
 */
@Composable
fun isProdHostId(hostId: String?): Boolean {
    val hosts = LocalHosts.current ?: return false
    return isProdHost(hostId?.let { hosts.find(it) })
}

/**
 * Predicate over host ids for non-composable callers (the broadcast target list), bound to the live
 * catalog: re-created when the catalog changes, so a freshly tagged host is production right away.
 */
@Composable
fun rememberProductionLookup(): (String?) -> Boolean {
    val hosts = LocalHosts.current
    return remember(hosts, hosts?.hosts) { { id -> isProdHost(id?.let { hosts?.find(it) }) } }
}

/** Red `PROD` pill for host rows — the resting-state marker of the production guard. */
@Composable
fun ProdBadge(modifier: Modifier = Modifier) {
    Badge(PROD_BADGE, bg = Skerry.colors.strictBg, fg = Skerry.colors.strictFg, modifier = modifier)
}

/**
 * Red outline around a production session's work area: the "which window am I in" cue that has to
 * land without reading a single label. A no-op when [enabled] is false, so non-production sessions
 * keep the exact layout they had.
 */
@Composable
fun Modifier.prodOutline(enabled: Boolean, radius: Int = 0): Modifier =
    if (!enabled) this else border(1.dp, Skerry.colors.sunset, RoundedCornerShape(radius.dp))
