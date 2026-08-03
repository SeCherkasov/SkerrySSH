package app.skerry.ui.terminal

import app.skerry.ui.session.SessionStatus

/** What one pane contributes to the work bar's title: its host label, its address, its state. */
data class PaneFacts(val title: String, val subtitle: String, val status: SessionStatus)

/**
 * What the work bar says about the tab under it. A tab with one pane is named after its host; a
 * split tab is named after the split itself and lists the hosts it holds, since no single host
 * speaks for it.
 */
sealed interface WorkBarLabel {
    /** State of the tab as a whole — the bar carries one dot, not one per pane. */
    val status: SessionStatus

    data class Solo(val title: String, val subtitle: String, override val status: SessionStatus) : WorkBarLabel

    data class Split(
        val paneCount: Int,
        val hosts: String,
        override val status: SessionStatus,
        val syncInput: Boolean,
    ) : WorkBarLabel
}

/**
 * What one pane contributes to the bar. A pane with no host yet ([blank]) is named by
 * [placeholder] and shows no address: its own label is whatever the tab was called when it was
 * opened, and clicking that title is how a host gets picked for it.
 */
fun paneFacts(
    title: String,
    subtitle: String,
    status: SessionStatus,
    blank: Boolean,
    placeholder: String,
): PaneFacts = if (blank) PaneFacts(placeholder, "", status) else PaneFacts(title, subtitle, status)

/** The bar's title for [panes] (a tab always holds at least one). */
fun workBarLabel(panes: List<PaneFacts>, syncInput: Boolean): WorkBarLabel {
    require(panes.isNotEmpty()) { "a tab always holds at least one pane" }
    val status = panes.map { it.status }.maxBy { it.severity }
    val solo = panes.singleOrNull()
    return if (solo != null) {
        WorkBarLabel.Solo(solo.title, solo.subtitle, status)
    } else {
        WorkBarLabel.Split(
            paneCount = panes.size,
            hosts = panes.joinToString(", ") { it.title },
            status = status,
            syncInput = syncInput,
        )
    }
}

/**
 * How loudly a pane's state speaks for the whole tab: the bar shows the worst of them, so a split
 * with one dead pane never reads as all-live. A pane with no session outranks one still connecting —
 * connecting is in flight and resolves on its own, an idle pane stays that way until acted on.
 */
private val SessionStatus.severity: Int
    get() = when (this) {
        SessionStatus.Live -> 0
        SessionStatus.Connecting -> 1
        SessionStatus.Idle -> 2
        SessionStatus.Failed -> 3
    }
