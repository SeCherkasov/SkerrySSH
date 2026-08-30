package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_rename_tag
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.snippet.ALL_SNIPPETS_CHIP
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetLibraryState
import app.skerry.ui.snippet.UNCATEGORIZED_KEY
import app.skerry.ui.snippet.hasCategories
import app.skerry.ui.snippet.snippetChipLabel
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry
import app.skerry.ui.design.FolderCollapse
import app.skerry.ui.design.FolderSections
import app.skerry.ui.design.mobileFolderHeaderPadding
import app.skerry.ui.snippet.SNIPPET_FOLDER_SCOPE

/**
 * Snippet library body on mobile: search field, tag chips and the cards, sectioned into folders as
 * soon as anything is filed into one ([FolderSections]). Same [SnippetLibraryState] and the same
 * fold state as the desktop section, only the layout differs — a snippet carries several tags, so
 * grouping by tag listed it once per tag; the chip row narrows the same list instead, and the
 * folder it is filed under is a single section.
 */
@Composable
internal fun MobileSnippetLibrary(
    all: List<SnippetEntry>,
    library: SnippetLibraryState,
    collapse: FolderCollapse,
    onEdit: (SnippetEntry) -> Unit,
    onRenameCategory: (String) -> Unit,
    onMoveItems: ((itemIds: Set<String>, targetGroup: String?, targetIndexInGroup: Int) -> Unit)? = null,
    onMoveGroup: ((group: String?, targetIndex: Int) -> Unit)? = null,
) {
    val tagged = hasCategories(all)
    val visible = library.visible(all)
    val active = library.effectiveChip(all)

    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
        MobileFormInput(library.query, { library.query = it }, stringResource(Res.string.lib_snippets_search))
    }
    if (tagged) {
        MobileSnippetChips(
            chips = library.chips(all),
            active = active,
            onSelect = { library.activeChip = it },
            // "All" and the synthetic uncategorized bucket are not tags and cannot be renamed.
            onRename = active.takeIf { it != ALL_SNIPPETS_CHIP && it != UNCATEGORIZED_KEY }?.let { tag -> ({ onRenameCategory(tag) }) },
        )
    }
    if (visible.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp)) {
            Txt(stringResource(Res.string.lib_snippets_no_matches), color = Skerry.colors.faint, size = 13.sp)
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The same folders as the desktop library, under the same scope key. The fold itself is a
        // view preference this device keeps to itself, so what carries over is where the folders
        // are, not which of them happen to be open here.
        FolderSections(
            items = visible,
            scope = SNIPPET_FOLDER_SCOPE,
            collapse = collapse,
            group = { it.snippet.group },
            itemKey = { it.id },
            headerPadding = mobileFolderHeaderPadding(),
            longPress = true,
            onMoveItems = onMoveItems,
            onMoveGroup = onMoveGroup,
        ) { entry ->
            val onClick = remember(entry.id) { { onEdit(entry) } }
            MobileSnippetCard(entry.snippet, onClick)
        }
    }
}

/**
 * Tag chip row: "All" + `#tag` + "Uncategorized"; active chip highlighted, horizontally scrollable.
 * The pencil after the row renames whichever real tag is active — the one thing the old collapsible
 * sections offered that a filter strip has to keep.
 */
@Composable
private fun MobileSnippetChips(
    chips: List<String>,
    active: String,
    onSelect: (String) -> Unit,
    onRename: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            chips.forEach { chip ->
                key(chip) {
                    val on = chip == active
                    val onClick = remember(chip) { { onSelect(chip) } }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (on) Skerry.colors.cyan14 else Skerry.colors.overlayMed)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                            .padding(horizontal = 13.dp, vertical = 5.dp),
                    ) {
                        Txt(
                            snippetChipLabel(chip),
                            color = if (on) Skerry.colors.cyanBright else Skerry.colors.dim,
                            size = 12.5.sp,
                            weight = if (on) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        if (onRename != null) {
            IconBtn("edit", onClick = onRename, box = 30, icon = 16.sp, tint = Skerry.colors.faint, tooltip = stringResource(Res.string.lib_snippets_rename_tag))
        }
    }
}
