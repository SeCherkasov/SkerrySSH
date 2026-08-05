package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileTab
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_screen_title
import app.skerry.ui.generated.resources.shell_hosts
import app.skerry.ui.generated.resources.shell_sessions_close
import app.skerry.ui.generated.resources.shell_sessions_count
import app.skerry.ui.generated.resources.shell_sessions_none
import app.skerry.ui.generated.resources.shell_sessions_none_hint
import app.skerry.ui.generated.resources.shell_sessions_title
import app.skerry.ui.session.SessionsController
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.session.sessionStatusText
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Root screen of the Sessions tab: everything currently open, terminals and remote desktops in one
 * list. A phone shows one session at a time and has no tab row to switch them, so without this
 * screen a live session is only reachable through the host that opened it — and a session opened on
 * an ad-hoc target, or on a host since deleted, not at all.
 *
 * Tapping a row activates that session and opens its screen ([MobileSessionRow.route]); the cross
 * closes it. With nothing open the screen explains itself and points at the two catalogs instead of
 * showing an empty list.
 */
@Composable
fun MobileSessionsScreen(state: MobileDesignState) {
    val sessions = LocalSessions.current
    // Read inside composition so opening, dropping or closing a session repaints the list.
    val rows = mobileSessionRows(sessions?.tabs.orEmpty().map { it.toSessionInfo() })

    Column(Modifier.fillMaxSize().background(Skerry.colors.bg).verticalScroll(rememberScrollState())) {
        SessionsHeader(rows.size)
        if (rows.isEmpty()) {
            MobileSessionsEmpty(state)
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { row ->
                    key(row.tabId) {
                        MobileSessionRowCard(
                            row = row,
                            onOpen = { activateMobileSession(sessions, state, row) },
                            onClose = sessions?.let { c -> { c.close(row.tabId) } },
                        )
                    }
                }
            }
        }
        // Clears the tab bar (see MobileCatalogScreen for the same reserve).
        Spacer(Modifier.height(120.dp))
    }
}

/** Activate [row]'s session and open the screen it belongs to. */
private fun activateMobileSession(sessions: SessionsController?, state: MobileDesignState, row: MobileSessionRow) {
    // Without a controller (preview/offscreen) there is nothing to activate, and pushing the screen
    // would land on an empty terminal.
    val controller = sessions ?: return
    controller.activate(row.tabId)
    state.push(row.route)
}

/** Header: screen title + how many sessions are open. */
@Composable
private fun SessionsHeader(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MobileScreenTitle(stringResource(Res.string.shell_sessions_title))
        if (count > 0) Txt(stringResource(Res.string.shell_sessions_count, count), color = Skerry.colors.dim, size = 12.sp)
    }
}

/**
 * Session line: the shared catalog row ([MobileCatalogRow]) so an open session reads like the host
 * that opened it, with a close cross after the status dot. [onClose] == null (no controller) hides
 * the cross.
 */
@Composable
private fun MobileSessionRowCard(row: MobileSessionRow, onOpen: () -> Unit, onClose: (() -> Unit)?) {
    MobileCatalogRow(
        icon = row.icon,
        label = row.title,
        subtitle = row.subtitle,
        dotColor = sessionDotColor(row.status),
        statusText = sessionStatusText(row.status),
        onClick = onOpen,
        trailing = onClose?.let { close ->
            {
                // The cross is a bare glyph — without a label a screen reader announces the ligature
                // name ("close") from the symbols font, so the action is spelled out here.
                val closeLabel = stringResource(Res.string.shell_sessions_close)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .semantics { contentDescription = closeLabel }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = close,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Sym("close", size = 18.sp, color = Skerry.colors.faint)
                }
            }
        },
    )
}

/**
 * Nothing open: says so, then offers the two places a session starts from. A bare "no sessions" on
 * a tab the user just pressed leaves them to find the catalogs on their own.
 */
@Composable
private fun MobileSessionsEmpty(state: MobileDesignState) {
    MobileEmptyNote(
        icon = "terminal",
        title = stringResource(Res.string.shell_sessions_none),
        subtitle = stringResource(Res.string.shell_sessions_none_hint),
    ) {
        Spacer(Modifier.height(6.dp))
        SessionsEmptyTarget("dns", stringResource(Res.string.shell_hosts)) { state.select(MobileTab.Hosts) }
        SessionsEmptyTarget("desktop_windows", stringResource(Res.string.rd_screen_title)) { state.select(MobileTab.Desktops) }
    }
}

/** One "go to a catalog" card of the empty state. */
@Composable
private fun SessionsEmptyTarget(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym(icon, size = 20.sp, color = Skerry.colors.cyanBright)
        Txt(label, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Sym("chevron_right", size = 20.sp, color = Skerry.colors.faint)
    }
}
