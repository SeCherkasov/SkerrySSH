package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Chip
import app.skerry.ui.design.FilterChipRow
import app.skerry.ui.design.HoverTooltip
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.fieldName
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.terminal.MAX_NOTE_CHARS
import kotlinx.coroutines.delay
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_rename_tag
import app.skerry.ui.generated.resources.lib_snippets_rename_tag_placeholder
import app.skerry.ui.generated.resources.lib_snippets_rename_tag_subtitle
import app.skerry.ui.generated.resources.lib_snippets_rename_tag_title
import app.skerry.ui.generated.resources.lib_snippets_untitled
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_save
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.CommandLine
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.design.tagChipLabel

/**
 * Tag filter strip above the snippet list, plus the rename pencil for whichever real tag is active.
 * The library is one flat list, so a tag is only ever a filter here — renaming it is the one thing
 * the old category sections offered that the chip row has to keep.
 */
@Composable
internal fun SnippetTagFilterRow(
    chips: List<String>,
    library: SnippetLibraryState,
    all: List<SnippetEntry>,
    onRenameTag: (oldTag: String, newTag: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renamingTag by remember { mutableStateOf<String?>(null) }
    val active = library.effectiveChip(all)
    // "All" and the synthetic uncategorized bucket are not tags and cannot be renamed.
    val renameable = active.takeIf { it != ALL_SNIPPETS_CHIP && it != UNCATEGORIZED_KEY }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilterChipRow(
            chips = chips,
            activeChip = active,
            onSelect = { library.activeChip = it },
            modifier = Modifier.weight(1f),
            label = { snippetChipLabel(it) },
        )
        if (renameable != null) {
            IconBtn(
                "edit",
                onClick = { renamingTag = renameable },
                box = 24,
                icon = 15.sp,
                tint = Skerry.colors.faint,
                tooltip = stringResource(Res.string.lib_snippets_rename_tag),
            )
        }
    }

    renamingTag?.let { tag ->
        RenameTagDialog(
            initialName = tag,
            onDismiss = { renamingTag = null },
            onSave = { newTag -> onRenameTag(tag, newTag); renamingTag = null },
        )
    }
}

/**
 * One snippet row: name and command on the left, its tags on the right. Flat — a snippet carrying
 * several tags is listed once, with all of them, rather than repeated under each.
 */
@Composable
internal fun SnippetListRow(
    entry: SnippetEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val s = entry.snippet
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var noteVisible by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        noteVisible = false
        if (hovered) {
            delay(450L)
            noteVisible = true
        }
    }
    val note = s.notes
    val shownNote = remember(note) { note?.let { sanitizeServerText(it, MAX_NOTE_CHARS, allowNewlines = true) } }

    Box(Modifier.fillMaxWidth()) {
        if (noteVisible && !shownNote.isNullOrBlank()) HoverTooltip(shownNote)
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
                .hoverable(hoverInteraction)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (selected) Skerry.colors.cyan.copy(alpha = 0.14f) else Skerry.colors.overlayMed),
                contentAlignment = Alignment.Center,
            ) {
                Sym("code_blocks", size = 16.sp, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim)
            }
            Column(Modifier.weight(1f)) {
                Txt(
                    remember(s) { untrustedLabel(s.label) }.ifBlank { stringResource(Res.string.lib_snippets_untitled) },
                    color = if (selected) Skerry.colors.cyanBright else Skerry.colors.textBright,
                    size = 12.5.sp,
                    weight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CommandLine(s.command, modifier = Modifier.padding(top = 3.dp))
            }
            // Tags are metadata, not the row's subject: they get whatever width is left after the command.
            if (s.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    s.tags.take(MAX_ROW_TAGS).forEach { tag -> key(tag) { Chip(remember(tag) { tagChipLabel(tag) }) } }
                }
            }
        }
    }
}

/** Tags beyond this crowd the command out of the row; the rest are visible in the panel. */
private const val MAX_ROW_TAGS = 3

/**
 * Rename a snippet tag. Mirrors the host group rename dialog
 * ([app.skerry.ui.host.GroupDialog]) — scrim + card, prefilled name field, save disabled while blank.
 */
@Composable
private fun RenameTagDialog(initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The old tag arrives prefilled and autofocused: select it so typing replaces the name.
    val draft = rememberFieldDraft(name, selectAllOnFocus = name == initialName)
    val canSave = name.trim().isNotEmpty()
    val save = { if (canSave) onSave(name) }
    ModalScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) {
            Txt(
                stringResource(Res.string.lib_snippets_rename_tag_title),
                color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
            )
            Txt(
                stringResource(Res.string.lib_snippets_rename_tag_subtitle),
                color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            BasicTextField(
                value = draft.textFieldValue(name),
                onValueChange = { draft.accept(it, name) { name = it } },
                singleLine = true,
                textStyle = TextStyle(color = Skerry.colors.text, fontSize = 13.sp, fontFamily = LocalFonts.current.ui),
                cursorBrush = SolidColor(Skerry.colors.cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                // The dialog has a title instead of a field caption, so the title is what names it.
                modifier = Modifier.fillMaxWidth().focusRequester(focus).fieldFocus(draft)
                    .fieldName(fallback = stringResource(Res.string.lib_snippets_rename_tag_title)),
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(Skerry.colors.card)
                            .border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp))
                            .padding(horizontal = 11.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            if (name.isEmpty()) Txt(stringResource(Res.string.lib_snippets_rename_tag_placeholder), color = Skerry.colors.faint, size = 13.sp)
                            inner()
                        }
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.shell_cancel), color = Skerry.colors.dim, size = 12.5.sp)
                }
                PrimaryButton(stringResource(Res.string.shell_save), onClick = save, enabled = canSave)
            }
        }
    }
}
