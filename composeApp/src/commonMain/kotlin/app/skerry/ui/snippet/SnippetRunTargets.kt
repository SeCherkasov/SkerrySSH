package app.skerry.ui.snippet

import androidx.compose.runtime.Immutable

/**
 * A session the snippets panel can run into: [id] is the pane id, [label] what the chip shows. Only
 * connected terminals become targets — a remote desktop or a pane still connecting has no shell to
 * take the line.
 */
@Immutable
data class SnippetRunTarget(val id: String, val label: String)

/**
 * Which target Run uses: the user's explicit pick when that session is still open, else the active
 * one, else the first connected session. `null` — nothing is connected, and Run has nowhere to go.
 *
 * The [chosenId] fallback matters because the panel outlives the sessions it lists: a tab closed
 * while the panel is open would otherwise leave Run pointing at a dead pane.
 */
/**
 * Which snippet the panel shows: the explicitly selected one while it is among [visible], else the
 * first row on screen. A selection the search or the tag chip filtered away must not stay in the
 * panel — Run would fire a snippet that is no longer in the list.
 */
fun resolveSelectedSnippet(visible: List<SnippetEntry>, selectedId: String?): SnippetEntry? =
    visible.firstOrNull { it.id == selectedId } ?: visible.firstOrNull()

fun defaultSnippetRunTarget(
    targets: List<SnippetRunTarget>,
    activeId: String?,
    chosenId: String?,
): SnippetRunTarget? =
    targets.firstOrNull { it.id == chosenId }
        ?: targets.firstOrNull { it.id == activeId }
        ?: targets.firstOrNull()
