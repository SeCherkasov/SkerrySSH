package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.TeamActivityDay
import app.skerry.shared.terminal.epochMillis
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_history
import app.skerry.ui.generated.resources.lib_teams_history_empty
import app.skerry.ui.generated.resources.lib_teams_recent_activity
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Width of the activity column — wide enough for an event line plus its actor and time. */
private val ACTIVITY_WIDTH = 380.dp

/**
 * The team's activity beside the members, for those allowed to see it (owner/admin). It is the same
 * feed the History dialog shows, without the filters: the column answers "what happened here
 * lately", and the dialog stays the place to dig.
 */
@Composable
internal fun TeamActivityPanel(
    feed: List<TeamActivityDay>,
    onOpenFull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = LocalFonts.current.mono
    // "Today"/"Yesterday" need a reference point; read once per composition, not per row.
    val todayIndex = remember(feed) { epochMillis().floorDiv(MILLIS_PER_DAY) }
    Row(modifier.width(ACTIVITY_WIDTH).fillMaxHeight()) {
        VLine(Skerry.colors.line)
        Column(Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(
                    stringResource(Res.string.lib_teams_recent_activity).uppercase(),
                    color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                )
                GhostButton(stringResource(Res.string.lib_teams_history), onClick = onOpenFull, icon = "history")
            }
            if (feed.isEmpty()) {
                Txt(stringResource(Res.string.lib_teams_history_empty), color = Skerry.colors.faint, size = 11.5.sp)
                return@Column
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                feed.forEach { day ->
                    Txt(
                        dayLabel(day.dayIndex, todayIndex, day.rows.first().createdAt),
                        color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    day.rows.forEach { row -> ActivityRow(row, mono) }
                }
            }
        }
    }
}
