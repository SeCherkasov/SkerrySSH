package app.skerry.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import app.skerry.shared.host.Host
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_ungrouped
import org.jetbrains.compose.resources.stringResource

/** A host list folder: group name plus its hosts (in source list order). */
@Immutable
data class HostFolder(val name: String, val hosts: List<Host>)

/**
 * Technical key for the synthetic bucket holding hosts without a group (`Host.group` blank/`null`).
 * Used as the grouping key in [groupHostsByFolder] and in `folder.name != UNGROUPED_LABEL`
 * comparisons; not localized, since that would break grouping on locale change. For display, use
 * [ungroupedLabel].
 */
const val UNGROUPED_LABEL = "Ungrouped"

/** Localized "ungrouped" bucket label for display (not for grouping, see [UNGROUPED_LABEL]). */
@Composable
fun ungroupedLabel(): String = stringResource(Res.string.shtail_ungrouped)

/**
 * Group hosts by [Host.group] for the sidebar. Folders appear in order of the group's first
 * appearance in the input list, hosts within a folder keep source order. Blank/`null` group falls
 * into the [ungroupedLabel] bucket. Pure function (no Compose), shared by desktop and mobile sidebars.
 */
fun groupHostsByFolder(hosts: List<Host>, ungroupedLabel: String = UNGROUPED_LABEL): List<HostFolder> {
    val buckets = LinkedHashMap<String, MutableList<Host>>()
    for (host in hosts) {
        val key = host.group?.takeIf { it.isNotBlank() } ?: ungroupedLabel
        buckets.getOrPut(key) { mutableListOf() }.add(host)
    }
    return buckets.map { (name, list) -> HostFolder(name, list) }
}
