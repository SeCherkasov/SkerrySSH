package app.skerry.ui.mobile

import app.skerry.ui.app.MobileRoute
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.session.Tab

// Pure list model behind the Sessions root tab; the composables live in MobileSessionsView.kt.

/**
 * What the Sessions list needs to know about one open [Tab], flattened off the live controller so
 * the list logic is decided in one place and testable without a transport.
 */
internal data class MobileSessionInfo(
    val tabId: String,
    val title: String,
    val subtitle: String,
    val isVnc: Boolean,
    val isPlayer: Boolean,
    val isBlank: Boolean,
    val status: SessionStatus,
)

/** One row of the Sessions list: what it shows and which push screen a tap opens. */
internal data class MobileSessionRow(
    val tabId: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val status: SessionStatus,
    val route: MobileRoute,
)

/** Flatten a live tab into what the list reads. */
internal fun Tab.toSessionInfo(): MobileSessionInfo = MobileSessionInfo(
    tabId = id,
    title = displayTitle,
    subtitle = focusedPane.subtitle,
    isVnc = isVnc,
    isPlayer = isPlayer,
    isBlank = isBlank,
    status = focusedPane.status,
)

/** One chip of the terminal screen's session strip. */
internal data class MobileStripChip(
    val tabId: String,
    val label: String,
    val status: SessionStatus,
    val active: Boolean,
)

/**
 * Chips for the terminal screen's session strip: the open shells, in the order they were opened,
 * with [activeId] marked. Remote desktops are left out — they render on their own screen, and a chip
 * that swapped the terminal for a framebuffer would be a trapdoor rather than a tab. So is a blank
 * tab: nothing is connected in it, so its chip would carry an empty label and lead to an empty
 * screen (see [MobileSessionStrip], where "+" is the way to a new session instead).
 */
internal fun mobileTerminalStrip(
    sessions: List<MobileSessionInfo>,
    activeId: String?,
): List<MobileStripChip> =
    sessions.filterNot { it.isVnc || it.isBlank }.map {
        MobileStripChip(tabId = it.tabId, label = it.title, status = it.status, active = it.tabId == activeId)
    }

/**
 * Rows for the open sessions, in the order they were opened. A blank tab is left out: it holds no
 * connection, so a row for it would open an empty terminal screen and read as a session that died.
 * A failed or dropped session keeps its row — that is what the user comes here to find.
 */
internal fun mobileSessionRows(sessions: List<MobileSessionInfo>): List<MobileSessionRow> =
    sessions.filterNot { it.isBlank }.map { s ->
        MobileSessionRow(
            tabId = s.tabId,
            title = s.title,
            subtitle = s.subtitle,
            icon = when {
                s.isVnc -> "desktop_windows"
                s.isPlayer -> "play_circle"
                else -> "terminal"
            },
            status = s.status,
            // A recording replays in the terminal work area, so it opens the same screen a shell does.
            route = if (s.isVnc) MobileRoute.Vnc else MobileRoute.Terminal,
        )
    }
