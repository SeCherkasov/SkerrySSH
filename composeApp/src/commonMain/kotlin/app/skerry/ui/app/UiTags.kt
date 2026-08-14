package app.skerry.ui.app

import app.skerry.ui.host.HostSection
import app.skerry.ui.session.SessionView

/**
 * Stable handles on the shell's navigation, for tests that drive the real UI with clicks.
 *
 * Without them a test has to match a node by what it draws, and neither form survives: a rail button
 * draws its icon as a font ligature (its text is `vpn_key`), and every label is localized, so an
 * assertion passes or fails by the machine's locale. A tag is neither — it is a name the UI keeps
 * across a redesign of the thing carrying it.
 *
 * Deliberately narrow. Three kinds of element get one: what navigates (rail, settings nav, mobile
 * tab bar), what the navigation lands on (the screen switchers, [app.skerry.ui.desktop.Viewport] and
 * [app.skerry.ui.mobile.MobileRoutePane]), and the handful of form controls whose text is not a
 * usable handle — a Save button whose caption is localized, an input with no caption at all. Every
 * other widget is reached by its name or its role; tagging them one by one would freeze the layout
 * and put test scaffolding in every file.
 */
object UiTags {

    // --- desktop: what navigates ---

    /** Rail button opening a work-area catalog (terminal connections / remote desktops). */
    fun railSection(section: HostSection): String = "nav.rail.section.${section.name}"

    /** Rail button opening an app-level section over the tabs (Vault, Teams, Tunnels, …). */
    fun railView(view: DesktopView): String = "nav.rail.view.${view.name}"

    /** Rail's bottom button, which opens the settings panel. */
    const val RAIL_SETTINGS: String = "nav.rail.settings"

    /** Row of the settings panel's left nav. */
    fun settingsTab(tab: SettingsTab): String = "nav.settings.${tab.name}"

    /** The settings panel's close button. */
    const val SETTINGS_CLOSE: String = "nav.settings.close"

    /**
     * Strip of session tabs in the title bar. A container rather than a tag per chip: a tab is
     * named after its host, and that name is drawn in the catalog too — the strip is what tells the
     * two apart.
     */
    const val SESSION_TABS: String = "nav.sessionTabs"

    // --- mobile: what navigates ---

    /** Item of the bottom tab bar. */
    fun mobileTab(tab: MobileTab): String = "nav.tab.${tab.name}"

    // --- forms ---

    /** Button that opens the "new connection" form (sidebar footer on desktop, FAB on mobile). */
    const val NEW_CONNECTION: String = "nav.newConnection"

    /** Buttons that open the other catalogues' create forms. */
    const val NEW_TUNNEL: String = "nav.newTunnel"
    const val NEW_SNIPPET: String = "nav.newSnippet"
    const val NEW_GROUP: String = "nav.newGroup"
    const val NEW_RUNBOOK: String = "nav.newRunbook"

    /** Help button in a library screen's header and the dialog it opens (one screen at a time). */
    const val HELP: String = "nav.help"
    const val HELP_DIALOG: String = "nav.helpDialog"

    /**
     * Command box of a runbook step. The steps are a repeating list, not captioned fields, so there
     * is no label to name them after; the tag repeats per step, in step order.
     */
    const val RUNBOOK_STEP_COMMAND: String = "form.runbookStep.command"

    /** Notice under the AI endpoint field when the URL is plain http. */
    const val AI_INSECURE_ENDPOINT: String = "form.ai.insecureEndpoint"

    /**
     * A form's commit and dismiss buttons. One pair of names for every form, so a test that fills
     * one in does not have to know whose Save it is.
     *
     * Not unique on screen, though: a confirmation raised over an open editor carries the same pair
     * (the tunnel editor's Save under the delete dialog's Remove, for one). Match the tag together
     * with the button's text when both can be composed at once.
     */
    const val FORM_SAVE: String = "form.save"
    const val FORM_CANCEL: String = "form.cancel"

    /** Button that turns a filled-in card into an editable form (web access, and the like). */
    const val FORM_EDIT: String = "form.edit"

    /**
     * The single input of a one-field dialog (a group name, a file name, a team name).
     *
     * A handle, not a name: those inputs are named from their title or their placeholder
     * ([app.skerry.ui.design.fieldName]), and a test that addressed them by that name would be
     * asserting the locale as much as the field.
     */
    const val FORM_FIELD: String = "form.field"

    // --- what is on screen ---

    /** The settings panel itself, present only while it is open. */
    const val SETTINGS_PANEL: String = "screen.settings"

    /**
     * The work area's host catalog sidebar (search, chips, folders, rows).
     *
     * A container, like the screen tags below it, and for the same reason: a host's name is drawn in
     * several places at once — the session tabs, the work bar, the recent list — so "this row is
     * gone" can only be asserted about the catalog itself.
     */
    const val HOST_SIDEBAR: String = "screen.hostSidebar"

    /** Content pane of the settings section currently selected. */
    fun settingsSection(tab: SettingsTab): String = "screen.settings.${tab.name}"

    /** App-level section rendered over the tabs. */
    fun screen(view: DesktopView): String = "screen.${view.name}"

    /** Work-area sub-view of the active session (terminal, SFTP, monitor, player). */
    fun screen(view: SessionView): String = "screen.${view.name}"

    /** Work area showing a catalog's own surface rather than a session's. */
    fun screen(section: HostSection): String = "screen.${section.name}"

    /** Root screen of a mobile tab. */
    fun mobileScreen(tab: MobileTab): String = "screen.mobile.${tab.name}"

    /** Full-screen push screen over the mobile tabs. */
    fun mobileScreen(route: MobileRoute): String = "screen.mobile.${route.name}"
}
