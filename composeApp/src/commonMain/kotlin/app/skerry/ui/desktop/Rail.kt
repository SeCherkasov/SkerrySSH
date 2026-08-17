package app.skerry.ui.desktop

import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.host.HostSection
import app.skerry.ui.session.SessionView
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rail_desktops
import app.skerry.ui.generated.resources.rail_hosts
import app.skerry.ui.generated.resources.rail_snippets
import app.skerry.ui.generated.resources.runbook_section
import app.skerry.ui.generated.resources.rail_team
import app.skerry.ui.generated.resources.rail_terminal
import app.skerry.ui.generated.resources.rail_tunnels
import app.skerry.ui.generated.resources.rail_vault
import org.jetbrains.compose.resources.StringResource

/**
 * What a rail button opens: a work-area section (its own host catalog and session tabs) or an
 * app-level section rendered over the tabs ([DesktopView.isAppLevel]).
 */
sealed interface RailTarget {
    /** Terminal-style connections or remote desktops — see [HostSection]. */
    data class Section(val section: HostSection) : RailTarget

    /** Vault/Known hosts/Teams/Tunnels/Snippets: shared sections that open over the tabs. */
    data class View(val view: DesktopView) : RailTarget
}

/** Item of the desktop shell's left icon rail. Label is a resource, localized to the UI language. */
data class RailItem(val target: RailTarget, val icon: String, val label: StringResource)

// Files is intentionally absent from the rail: SFTP opens via a quick button (folder icon) on the
// active session's terminal; a separate rail item would duplicate it. [DesktopView.Sftp] stays a session view.
val RAIL = listOf(
    RailItem(RailTarget.Section(HostSection.Terminal), "terminal", Res.string.rail_terminal),
    RailItem(RailTarget.Section(HostSection.RemoteDesktops), "desktop_windows", Res.string.rail_desktops),
    RailItem(RailTarget.View(DesktopView.Ports), "lan", Res.string.rail_tunnels),
    RailItem(RailTarget.View(DesktopView.Snippets), "code_blocks", Res.string.rail_snippets),
    RailItem(RailTarget.View(DesktopView.Runbooks), "checklist", Res.string.runbook_section),
    RailItem(RailTarget.View(DesktopView.Vault), "vpn_key", Res.string.rail_vault),
    RailItem(RailTarget.View(DesktopView.Known), "fingerprint", Res.string.rail_hosts),
    RailItem(RailTarget.View(DesktopView.Teams), "groups", Res.string.rail_team),
)

/**
 * Whether [item] is the one currently on screen: an app-level section is active while its overlay
 * is up, a work-area section only once no overlay hides it.
 */
fun railItemActive(item: RailItem, state: DesktopDesignState): Boolean = when (val t = item.target) {
    is RailTarget.View -> state.appOverlay == t.view
    is RailTarget.Section -> state.appOverlay == null && state.section == t.section
}

/**
 * Open a work-area section from the rail: swap the sidebar catalog, leave the running session on
 * screen. Walking the rail is navigation, not a session switch — the tab the user is working in
 * keeps its chip selected and keeps rendering ([app.skerry.ui.app.workAreaSection]), and the
 * catalog that just opened is how the next session starts. Only with no tab open does the section
 * take over the whole work area.
 */
fun openRailSection(state: DesktopDesignState, section: HostSection, terminalView: SessionView? = null) {
    state.showSection(section)
    // And with it the catalog: pressing a section is asking to see its hosts, so a collapsed panel
    // comes back rather than leaving the press with nothing to show for it — but only where the
    // panel is actually drawn, or the press would quietly change a preference the user cannot see
    // and would meet an open panel on their way back to the terminal.
    if (showsCatalog(section, terminalView)) state.showSidebar()
}

/**
 * Whether the work area draws the hosts panel for [section] with [terminalView] open in it. The
 * desktops section always does; on the terminal side the file panel, a runbook run and the player
 * fill the whole area instead, and only the terminal and the monitor keep the catalog beside them.
 */
fun showsCatalog(section: HostSection, terminalView: SessionView?): Boolean = when {
    section == HostSection.RemoteDesktops -> true
    terminalView == null -> true
    else -> terminalView == SessionView.Terminal || terminalView == SessionView.Monitor
}

// Selecting, cycling or closing a tab deliberately has no rail counterpart: the work area follows
// the selection on its own ([app.skerry.ui.app.workAreaSection]), and the catalog stays the one the
// user opened. The rail moves on a rail click and on a connect (which lands in that host's section).
