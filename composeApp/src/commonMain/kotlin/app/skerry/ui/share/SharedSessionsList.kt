package app.skerry.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSharedSessions
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.InitialsAvatar
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.share_join
import app.skerry.ui.generated.resources.share_none_live
import app.skerry.ui.generated.resources.share_started_by
import app.skerry.ui.generated.resources.share_unnamed
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * The team's live shared sessions: who is sharing what, and a way in. Shown in the team's detail
 * view on both platforms — a shared session exists only while its host holds the socket, so this is
 * a live directory rather than a saved list, and it is re-read whenever the section appears.
 *
 * [teamId] filters the account-wide directory the controller holds to the team on screen.
 */
@Composable
fun SharedSessionsList(
    teamId: String,
    controller: SharedSessionsController?,
    onJoin: (SharedSessionUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (controller == null) return
    LaunchedEffect(teamId) { controller.refresh() }
    SharedSessionRows(
        shares = controller.shares.filter { it.teamId == teamId },
        failure = controller.failure,
        onJoin = onJoin,
        modifier = modifier,
    )
}

/**
 * The rows themselves, without the controller. Split out so the layout can be rendered (and its
 * empty/failed states checked) without standing up a relay client.
 */
@Composable
internal fun SharedSessionRows(
    shares: List<SharedSessionUi>,
    failure: ShareFailure?,
    onJoin: (SharedSessionUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            failure != null && shares.isEmpty() ->
                Txt(shareFailureText(failure), size = 11.5.sp, color = Skerry.colors.sunset)
            shares.isEmpty() ->
                Txt(stringResource(Res.string.share_none_live), size = 11.5.sp, color = Skerry.colors.faint)
            else -> shares.forEach { share ->
                SharedSessionRow(share, onJoin = { onJoin(share) })
            }
        }
    }
}

@Composable
private fun SharedSessionRow(share: SharedSessionUi, onJoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Skerry.colors.surface2, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InitialsAvatar(share.hostAccountId, size = 26.dp)
        Column(Modifier.weight(1f)) {
            Txt(
                share.label.ifBlank { stringResource(Res.string.share_unnamed) },
                size = 12.5.sp,
                color = Skerry.colors.text,
            )
            Txt(
                stringResource(Res.string.share_started_by, share.hostAccountId, share.viewers),
                size = 11.sp,
                color = Skerry.colors.dim,
            )
        }
        GhostButton(stringResource(Res.string.share_join), onClick = onJoin, icon = "visibility")
    }
}

/**
 * The action a "Join" button performs: joins the share and opens the live session in a tab of its
 * own. Shared by the desktop and mobile team screens so both open a watched session the same way.
 */
@Composable
fun rememberJoinSharedSession(): (SharedSessionUi) -> Unit {
    val shares = LocalSharedSessions.current
    val sessions = LocalSessions.current
    val unnamed = stringResource(Res.string.share_unnamed)
    return { share ->
        shares?.join(
            share,
            onOpened = { viewer ->
                val paneId = sessions?.openShared(
                    title = share.label.ifBlank { unnamed },
                    subtitle = share.hostAccountId,
                    viewer = viewer,
                )
                // The pane and the viewer are the same session from here on: the terminal overlay
                // finds the viewer by the pane it is drawing.
                if (paneId != null) shares.trackWatching(paneId, viewer)
            },
            // The reason is left on the directory the user is looking at (see the controller).
            onFailed = {},
        )
    }
}
