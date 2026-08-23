package app.skerry.ui.design

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.delay

/** How much of a note a tooltip or a detail row will draw. */
internal const val MAX_NOTE_CHARS = 600

/**
 * Lines of a note a card peeks at before it ellipsizes — the terminal palette and both phone cards,
 * the surfaces that put a note next to a Run button. One number across them, because the platform
 * without a hover fallback must not be the one showing less. A dense desktop list row draws a single
 * line and leaves the rest to the panel beside it.
 */
internal const val NOTE_PEEK_LINES = 2

/**
 * Dwell before a row's note pops up, so sweeping the pointer down a list doesn't flash a tooltip
 * over every row on the way.
 */
private const val NOTE_TOOLTIP_DELAY_MS = 450L

/** A row's note as the list shows it: what may be drawn, and whether the pointer has earned it. */
@Stable
internal data class RowNote(
    /** Hand this to the row's `hoverable`; it is what the dwell watches. */
    val interaction: MutableInteractionSource,
    /** The filtered note, or `null` when there is nothing left to show. */
    val text: String?,
    /** True once the pointer has rested on the row long enough. */
    val visible: Boolean,
)

/**
 * The note a hovered row shows, filtered and timed. Host rows in the sidebar, snippets in the
 * library and snippets in the terminal palette all reveal a note this way; the filter and the dwell
 * live here so the three agree on both.
 *
 * A note can arrive from a peer (a shared profile): it keeps its lines — it is prose — but not the
 * characters that would let it draw as something else, and not more of them than a tooltip can
 * hold, since the field has no cap of its own.
 */
@Composable
internal fun rememberRowNote(note: String?): RowNote {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val text = remember(note) {
        note?.let { sanitizeServerText(it, MAX_NOTE_CHARS, allowNewlines = true) }?.takeIf { it.isNotBlank() }
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, text) {
        visible = false
        if (hovered && text != null) {
            delay(NOTE_TOOLTIP_DELAY_MS)
            visible = true
        }
    }
    return RowNote(interaction, text, visible && text != null)
}

/**
 * Draws [note] once its dwell has elapsed. Render it inside the hovered row's [androidx.compose.foundation.layout.Box],
 * as a sibling of the row rather than a child — see [HoverTooltip].
 *
 * [suppressed] is for a row that opens popups of its own: the note would land on top of them.
 */
@Composable
internal fun RowNoteTooltip(note: RowNote, suppressed: Boolean = false) {
    val text = note.text
    if (note.visible && !suppressed && text != null) HoverTooltip(text)
}

/**
 * A stored note drawn under the thing it belongs to — a secret in the keychain, a snippet in the
 * run panel, a runbook's description on its card, a note under a row in a list. Filtered like the
 * hover variant above, and named: sighted readers know this line is the note from where it sits and
 * how dim it is, a screen reader gets neither, so the block would otherwise read as an unlabelled
 * sentence between two facts.
 *
 * Safe inside a row that merges its children, contrary to the rule [Modifier.fieldValueName] is
 * built around: that one is about a description put *on* the merging node, which does replace the
 * text under it. Android reads `contentDescription` off each node's own unmerged config and skips it
 * entirely on a node that merges descendants, so a named leaf keeps its siblings audible.
 *
 * [maxLines] is for the surfaces that show a peek rather than the whole note (a list card).
 */
@Composable
internal fun NoteBlock(
    note: String?,
    label: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 12.sp,
    maxLines: Int = Int.MAX_VALUE,
) {
    val shown = remember(note) { note?.let { sanitizeServerText(it, MAX_NOTE_CHARS, allowNewlines = true) } }
    if (shown.isNullOrBlank()) return
    Txt(
        shown,
        color = Skerry.colors.dim, size = size, lineHeight = 17.sp,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        // Comma-joined, the way Compose joins the texts it merges (see design/fieldValueName).
        modifier = modifier.semantics { contentDescription = "$label, $shown" },
    )
}
