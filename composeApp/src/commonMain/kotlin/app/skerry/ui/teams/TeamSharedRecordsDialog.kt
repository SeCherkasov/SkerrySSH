package app.skerry.ui.teams

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalSharedSessions
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_nothing_shared
import app.skerry.ui.generated.resources.share_live_sessions
import app.skerry.ui.share.SharedSessionsList
import app.skerry.ui.share.rememberJoinSharedSession
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.shell_tip_close
import app.skerry.ui.generated.resources.shell_tip_remove
import app.skerry.ui.generated.resources.lib_teams_history

/** A record shared into the selected space: what the list row shows, plus the id its actions need. */
internal data class SharedRecordUi(val id: String, val label: String, val detail: String)

/**
 * The records of one kind shared into the selected share space, with everything one can do to
 * them: add another ([onShare]), take one out ([onUnshare]), or look at what happened to it
 * ([onHistory], owner/admin only). Reached from the "Shared with the team" card.
 */
@Composable
internal fun TeamSharedRecordsDialog(
    title: String,
    items: List<SharedRecordUi>,
    shareLabel: String?,
    onShare: () -> Unit,
    onUnshare: ((SharedRecordUi) -> Unit)?,
    onHistory: ((SharedRecordUi) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    TeamsDialogCard(onDismiss) {
        DialogTitleRow(title, onDismiss)
        if (items.isEmpty()) {
            Txt(stringResource(Res.string.lib_teams_nothing_shared), color = Skerry.colors.dim, size = 12.5.sp)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Txt(item.label, color = Skerry.colors.textBright, size = 12.5.sp, font = mono)
                        Txt(item.detail, color = Skerry.colors.faint, size = 10.5.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        if (onHistory != null) {
                            Box(Modifier.clip(CircleShape).clickable { onHistory(item) }.padding(3.dp)) {
                                Sym("history", contentDescription = stringResource(Res.string.lib_teams_history), size = 14.sp, color = Skerry.colors.faint)
                            }
                        }
                        if (onUnshare != null) {
                            Box(Modifier.clip(CircleShape).clickable { onUnshare(item) }.padding(3.dp)) {
                                Sym("close", contentDescription = stringResource(Res.string.shell_tip_remove), size = 14.sp, color = Skerry.colors.faint)
                            }
                        }
                    }
                }
            }
        }
        if (shareLabel != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton(shareLabel, onClick = onShare, icon = "add")
            }
        }
    }
}

/**
 * The team's live shared sessions — a directory that exists only while the sockets do, so it is a
 * dialog over the live list rather than a stored count.
 */
@Composable
internal fun TeamLiveSessionsDialog(teamId: String, onDismiss: () -> Unit) {
    TeamsDialogCard(onDismiss) {
        DialogTitleRow(stringResource(Res.string.share_live_sessions), onDismiss)
        SharedSessionsList(teamId, LocalSharedSessions.current, onJoin = rememberJoinSharedSession())
    }
}

/**
 * Title of a list dialog, with the way out on the same line. These dialogs show what is already
 * shared rather than ask for a decision, so a bottom "Cancel" would offer to abandon something the
 * user never started; closing belongs in the corner.
 */
@Composable
private fun DialogTitleRow(title: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(
            title,
            color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(4.dp)) {
            Sym("close", contentDescription = stringResource(Res.string.shell_tip_close), size = 16.sp, color = Skerry.colors.dim)
        }
    }
}
