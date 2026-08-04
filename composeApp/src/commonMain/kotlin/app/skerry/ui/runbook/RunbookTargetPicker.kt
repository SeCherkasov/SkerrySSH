package app.skerry.ui.runbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Chip
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_targets
import app.skerry.ui.generated.resources.runbook_targets_catalog
import app.skerry.ui.generated.resources.runbook_targets_none
import app.skerry.ui.generated.resources.runbook_targets_sessions
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Where the run will happen: the sessions already open, and the hosts from the catalog that aren't.
 * Picking a catalog host means the run opens a session for it first — the same connect path as
 * clicking the host in the sidebar, password prompt and production guard included.
 *
 * Order matters and is the order of the lists as drawn: with
 * [app.skerry.shared.runbook.RunbookParallelism.ONE_HOST_AT_A_TIME] the run walks the hosts in
 * exactly this sequence.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunbookTargetPicker(
    sessions: List<RunbookLaunchTarget.Session>,
    catalog: List<RunbookLaunchTarget.CatalogHost>,
    picked: Set<String>,
    onToggle: (String) -> Unit,
) {
    FieldLabel(labelUppercase(stringResource(Res.string.runbook_targets)), top = 14.dp, bottom = 7.dp)
    if (sessions.isEmpty() && catalog.isEmpty()) {
        Txt(stringResource(Res.string.runbook_targets_none), color = Skerry.colors.faint, size = 11.5.sp)
        return
    }
    if (sessions.isNotEmpty()) {
        Txt(
            stringResource(Res.string.runbook_targets_sessions), color = Skerry.colors.faint, size = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        TargetChips(sessions.map { it.paneId to it.label }, picked, onToggle)
    }
    if (catalog.isNotEmpty()) {
        Column(Modifier.padding(top = 10.dp)) {
            Txt(
                stringResource(Res.string.runbook_targets_catalog), color = Skerry.colors.faint, size = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            TargetChips(catalog.map { it.hostId to it.label }, picked, onToggle)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetChips(items: List<Pair<String, String>>, picked: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items.forEach { (id, label) ->
            key(id) { Chip(label, active = id in picked, onClick = { onToggle(id) }) }
        }
    }
}

/**
 * The targets [picked] stands for, in the order the picker draws them — sessions first, then catalog
 * hosts. An id that no longer resolves (a tab closed while the dialog was open) simply drops out.
 */
internal fun pickedLaunchTargets(
    sessions: List<RunbookLaunchTarget.Session>,
    catalog: List<RunbookLaunchTarget.CatalogHost>,
    picked: Set<String>,
): List<RunbookLaunchTarget> =
    sessions.filter { it.paneId in picked } + catalog.filter { it.hostId in picked }
