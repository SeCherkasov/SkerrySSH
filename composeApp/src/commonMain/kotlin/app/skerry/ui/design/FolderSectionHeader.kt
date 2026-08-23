package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_group_collapse
import app.skerry.ui.generated.resources.shtail_group_expand
import app.skerry.ui.generated.resources.shtail_group_state_collapsed
import app.skerry.ui.generated.resources.shtail_group_state_expanded
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Folder header of a list section: chevron + folder icon + name + count, the whole row folding the
 * section on a click.
 *
 * The host sidebar's own header ([app.skerry.ui.terminal] `FolderHeader`) stays separate because
 * there the row is a drag handle and a drop target, so only its chevron may take the click. Here
 * nothing is dragged, and the full-width row is the target — which is also what makes it usable on
 * a phone, where a 22dp chevron is not.
 *
 * The row names itself after what a click does and to which folder: a screen of headers all saying
 * "Collapse" says nothing about which one is which.
 */
@Composable
fun FolderSectionHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
) {
    val label = folderLabel(name)
    val action = stringResource(
        if (collapsed) Res.string.shtail_group_expand else Res.string.shtail_group_collapse,
        label,
    )
    // The click label says what a click would do; the state says where the folder stands now. A
    // reader with action hints turned down hears only the latter, and without it hears neither.
    val state = stringResource(
        if (collapsed) Res.string.shtail_group_state_collapsed else Res.string.shtail_group_state_expanded,
    )
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Sym(if (collapsed) "chevron_right" else "expand_more", size = 16.sp, color = Skerry.colors.faint)
        Sym("folder_open", size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(
            label,
            color = Skerry.colors.dim,
            size = 12.5.sp,
            weight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Skerry.colors.card)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Txt(count.toString(), color = Skerry.colors.faint, size = 10.sp)
        }
    }
}

/**
 * Header padding on a phone. The whole row is the fold target and it gets tapped over and over, so
 * it stays clear of the floor a finger needs; the horizontal inset is the list's own, because the
 * header lines up with the cards under it and each list insets those differently.
 */
fun mobileFolderHeaderPadding(horizontal: Dp = 0.dp): PaddingValues =
    PaddingValues(horizontal = horizontal, vertical = 8.dp)

/**
 * A list rendered as folder sections: a [FolderSectionHeader] per folder, then that folder's rows,
 * with a collapsed folder drawing its header alone. Emits into the caller's layout, so the list's
 * own container (scroll, spacing, padding) stays where it was.
 *
 * With nothing filed anywhere the rows are emitted flat, unchanged: folders are opt-in, and a
 * library that has never used one must not sprout an "Ungrouped" header on upgrade ([hasFolders]).
 *
 * [scope] namespaces the fold state ([folderCollapseKey]) — pass a constant per list, not a value
 * derived from what is on screen. [itemKey] is the stable identity of a row, so folding a folder
 * does not make Compose rebuild the rows of the ones below it.
 */
@Composable
fun <T> FolderSections(
    items: List<T>,
    scope: String,
    collapse: FolderCollapse,
    group: (T) -> String?,
    itemKey: (T) -> Any,
    headerPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    item: @Composable (T) -> Unit,
) {
    if (!hasFolders(items, group)) {
        items.forEach { row -> key(itemKey(row)) { item(row) } }
        return
    }
    // Sorting the whole library on every recomposition would be paid for on each fold of each
    // header; the list itself only changes when a record is saved.
    val folders = remember(items) { foldersOf(items, group) }
    folders.forEach { folder ->
        key(folder.name) {
            val collapseKey = folderCollapseKey(scope, folder.name)
            val collapsed = collapse.isGroupCollapsed(collapseKey)
            FolderSectionHeader(
                name = folder.name,
                count = folder.items.size,
                collapsed = collapsed,
                onToggle = { collapse.toggleGroupCollapsed(collapseKey) },
                padding = headerPadding,
            )
            if (!collapsed) folder.items.forEach { row -> key(itemKey(row)) { item(row) } }
        }
    }
}
