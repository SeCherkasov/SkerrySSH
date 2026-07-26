package app.skerry.ui.desktop

import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.sectionOf
import app.skerry.ui.host.HostSection
import app.skerry.ui.session.SessionsController
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rail_desktops
import app.skerry.ui.generated.resources.rail_hosts
import app.skerry.ui.generated.resources.rail_snippets
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
 * Open a work-area section from the rail: switch the shell to it and activate that section's newest
 * tab, so returning to a section lands back on its live session instead of an empty work area. With
 * no tab of that section the active tab is left alone — the work area shows the section's empty
 * state, and the other section's tab stays selected for when the user switches back.
 */
fun openRailSection(state: DesktopDesignState, sessions: SessionsController?, section: HostSection) {
    state.showSection(section)
    sessions?.lastSessionIn(remoteDesktop = section == HostSection.RemoteDesktops)
        ?.let { sessions.activate(it.id) }
}

/**
 * Follow a tab selection (chip click, tab hotkey, a session opening): the work area moves to the
 * section the now-active tab belongs to, so clicking a remote-desktop chip shows the framebuffer
 * rather than leaving the terminal on screen.
 */
fun followActiveTabSection(state: DesktopDesignState, sessions: SessionsController?) {
    state.showSection(sectionOf(sessions?.active))
}

/**
 * Close tab [id] and follow whatever tab becomes active. Every close path goes through here (chip
 * "×", the disconnect confirmation): closing hands the selection to a neighbour, which may belong
 * to the other section, and a close that forgot to follow would leave the rail, the tab row and
 * the work area pointing at three different things.
 */
fun closeSessionTab(state: DesktopDesignState, sessions: SessionsController?, id: String) {
    sessions?.close(id)
    followActiveTabSection(state, sessions)
}
