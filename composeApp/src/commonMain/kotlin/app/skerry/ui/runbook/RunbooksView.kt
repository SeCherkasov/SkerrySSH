package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalRunbookHistory
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.design.Chip
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.EmptyState
import app.skerry.ui.design.FolderSections
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SectionHeader
import app.skerry.ui.design.SidebarSearchField
import app.skerry.ui.design.NoteBlock
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.host.GroupDialog
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_count
import app.skerry.ui.generated.resources.runbook_delete
import app.skerry.ui.generated.resources.runbook_delete_message
import app.skerry.ui.generated.resources.runbook_delete_title
import app.skerry.ui.generated.resources.runbook_empty
import app.skerry.ui.generated.resources.help_button
import app.skerry.ui.generated.resources.runbook_new
import app.skerry.ui.generated.resources.runbook_no_matches
import app.skerry.ui.generated.resources.runbook_section
import app.skerry.ui.generated.resources.runbook_search
import app.skerry.ui.generated.resources.runbook_select_or_create
import app.skerry.ui.generated.resources.runbook_field_description
import app.skerry.ui.generated.resources.runbook_step_count
import app.skerry.ui.generated.resources.runbook_steps_total
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.design.tagChipLabel
import app.skerry.ui.design.boundedVisibleText
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/** Width of the search field in the section header — the same one the snippets section uses. */
private val SEARCH_WIDTH = 240.dp

/**
 * Runbooks section: a flat library of saved procedures with a panel on the right — the same shape
 * as [app.skerry.ui.snippet.SnippetsView], because the two are reached for in the same way. The
 * panel shows what the selected runbook will do and where it will run; editing is a separate mode
 * of that panel rather than a form that is always open.
 *
 * Its own section rather than a mode of Snippets: a runbook is a step list with per-step flags and
 * a run policy, and it is picked up at a different moment than a one-liner.
 */
@Composable
fun RunbooksView(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val manager = LocalRunbooks.current
    if (manager == null) {
        // Mock/preview path (no vault behind the section): say so instead of a blank pane.
        Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
            RunbooksHeader(runbooks = 0, steps = 0, query = "", onQuery = {}, onNew = {})
            EmptyState(icon = "checklist", title = stringResource(Res.string.runbook_empty))
        }
        return
    }
    LiveRunbooksView(manager, state, mono)
}

/** What the panel is showing: the selected runbook's run card, a new runbook's form, or an edit. */
private sealed interface RunbookPanelMode {
    data object Run : RunbookPanelMode
    data object New : RunbookPanelMode

    /**
     * Editing [id]. The id is carried rather than re-read from the list selection: the search field
     * stays live while the form is open, and a filter that hides the edited runbook would otherwise
     * swap the form onto a different one, discarding unsaved changes.
     */
    data class Edit(val id: String) : RunbookPanelMode
}

@Composable
private fun LiveRunbooksView(manager: RunbookManager, state: DesktopDesignState, mono: FontFamily) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mode by remember { mutableStateOf<RunbookPanelMode>(RunbookPanelMode.Run) }
    var query by remember { mutableStateOf("") }
    var helpOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RunbookEntry?>(null) }
    var editingGroup by remember { mutableStateOf<String?>(null) }
    val history = LocalRunbookHistory.current

    val all = manager.runbooks
    // Not memoized: RunbookManager.save() updates an entry in place, so neither `all` nor `query`
    // changes identity on a rename and a cached result would go stale (snippets filter the same way).
    val visible = all.filter { it.matches(query) }
    val selected = visible.firstOrNull { it.id == selectedId } ?: visible.firstOrNull()

    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        RunbooksHeader(
            runbooks = all.size,
            steps = all.sumOf { it.runbook.steps.size },
            query = query,
            onQuery = { query = it },
            onNew = { mode = RunbookPanelMode.New },
            onHelp = { helpOpen = true },
        )
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (visible.isEmpty()) {
                    EmptyState(
                        icon = "checklist",
                        title = if (all.isEmpty()) stringResource(Res.string.runbook_empty)
                        else stringResource(Res.string.runbook_no_matches),
                        subtitle = if (all.isEmpty()) stringResource(Res.string.runbook_select_or_create) else null,
                    )
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        // Folders section the library; with nothing filed it stays the flat list it
                        // was. A header counts what the search left in its folder, not the library.
                        FolderSections(
                            items = visible,
                            scope = RUNBOOK_FOLDER_SCOPE,
                            collapse = state,
                            group = { it.runbook.group },
                            itemKey = { it.id },
                            selectedIds = selectedIds,
                            onEditGroup = { editingGroup = it },
                            onMoveItems = { ids, targetGroup, targetIndex ->
                                manager.moveRunbooks(ids, targetGroup, targetIndex)
                            },
                            onMoveGroup = { group, targetIndex ->
                                manager.moveGroup(group, targetIndex)
                            },
                        ) { entry ->
                            // Keyed by id so selecting a row doesn't recreate every row's lambda.
                            val isSelected = entry.id in selectedIds || entry.id == selected?.id
                            val onClick = remember(entry.id) {
                                {
                                    selectedId = entry.id
                                    selectedIds = setOf(entry.id)
                                    mode = RunbookPanelMode.Run
                                }
                            }
                            RunbookListRow(entry, isSelected, mono, onClick)
                            HLine()
                        }
                    }
                }
            }
            val editMode = mode as? RunbookPanelMode.Edit
            val editing = editMode?.let { manager.find(it.id) }
            when {
                mode is RunbookPanelMode.New || editing != null -> {
                    VLine(Skerry.colors.line)
                    Box(Modifier.width(RUNBOOK_PANEL_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)) {
                        // Keyed by the edited runbook's identity so the form resets instead of
                        // carrying over the previous values.
                        key(editing?.id, mode) {
                            RunbookEditorPanel(
                                entry = editing,
                                manager = manager,
                                mono = mono,
                                onSaved = { id -> selectedId = id; mode = RunbookPanelMode.Run },
                                onCancel = { mode = RunbookPanelMode.Run },
                            )
                        }
                    }
                }
                selected != null -> {
                    VLine(Skerry.colors.line)
                    key(selected.id) {
                        RunbookRunCard(
                            entry = selected,
                            state = state,
                            onEdit = { mode = RunbookPanelMode.Edit(selected.id) },
                            // Held for confirmation: deleting a whole procedure silently is one
                            // misclick away from losing it and its run history.
                            onDelete = { pendingDelete = selected },
                        )
                    }
                }
            }
        }
    }
    if (helpOpen) RunbookHelpDialog(manager, onDismiss = { helpOpen = false })
    pendingDelete?.let { entry ->
        ConfirmActionDialog(
            title = stringResource(Res.string.runbook_delete_title),
            message = stringResource(
                Res.string.runbook_delete_message,
                // Stripped like every other surface showing this label (a runbook can arrive over
                // sync): this dialog is the last thing read before a delete.
                untrustedLabel(entry.runbook.label).ifBlank { stringResource(Res.string.runbook_untitled) },
            ),
            confirmLabel = stringResource(Res.string.runbook_delete),
            onConfirm = {
                manager.delete(entry.id)
                // The runbook is gone; its run log has nothing left to belong to.
                history?.forget(entry.id)
                if (selectedId == entry.id) selectedId = null
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
    if (editingGroup != null) {
        GroupDialog(
            initialName = editingGroup!!,
            onDismiss = { editingGroup = null },
            onSave = { newName ->
                manager.renameGroup(editingGroup!!, newName)
                editingGroup = null
            },
            onDelete = {
                manager.deleteGroup(editingGroup!!)
                editingGroup = null
            },
        )
    }
}

@Composable
private fun RunbooksHeader(
    runbooks: Int,
    steps: Int,
    query: String,
    onQuery: (String) -> Unit,
    onNew: () -> Unit,
    onHelp: (() -> Unit)? = null,
) {
    SectionHeader(
        title = stringResource(Res.string.runbook_section),
        // Two numbers, because they answer different questions: how many procedures are saved, and
        // how much work they add up to.
        subtitle = pluralStringResource(Res.plurals.runbook_count, runbooks, runbooks) + " · " +
            pluralStringResource(Res.plurals.runbook_steps_total, steps, steps),
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SidebarSearchField(query, onQuery, stringResource(Res.string.runbook_search), Modifier.width(SEARCH_WIDTH))
                if (onHelp != null) {
                    GhostButton(stringResource(Res.string.help_button), onClick = onHelp, icon = "help", modifier = Modifier.testTag(UiTags.HELP))
                }
                PrimaryButton(stringResource(Res.string.runbook_new), onClick = onNew, icon = "add", modifier = Modifier.testTag(UiTags.NEW_RUNBOOK))
            }
        },
    )
}

/** One runbook row: name and its first steps on the left, tags on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunbookListRow(entry: RunbookEntry, selected: Boolean, mono: FontFamily, onClick: () -> Unit) {
    val runbook = entry.runbook
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Skerry.colors.cyan10 else Skerry.colors.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sym("checklist", size = 16.sp, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim)
        Column(Modifier.weight(1f)) {
            Txt(
                // Stripped like everywhere a runbook names itself: it can arrive over sync, and a
                // bidi override must not be able to make one procedure read as another.
                remember(runbook) { untrustedLabel(runbook.label) }.ifBlank { stringResource(Res.string.runbook_untitled) },
                color = if (selected) Skerry.colors.cyanBright else Skerry.colors.textBright,
                size = 13.sp, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Txt(
                stringResource(Res.string.runbook_step_count, runbook.steps.size) +
                    // Spelled out and bounded like every other row that draws a command: a step
                    // written by whoever shared the runbook is not the app's own text.
                    remember(runbook) {
                        runbook.steps.firstOrNull()?.let { " · " + boundedVisibleText(it.summaryLine()) }.orEmpty()
                    },
                color = Skerry.colors.faint, size = 11.sp, font = mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
            )
            // The search matches on the description, and a hit whose only match is invisible reads
            // as a stray row — the same reason the snippet library row draws its note.
            NoteBlock(
                runbook.description, stringResource(Res.string.runbook_field_description),
                Modifier.padding(top = 3.dp), size = 11.sp, maxLines = 1,
            )
        }
        if (runbook.tags.isNotEmpty()) {
            FlowRow(
                Modifier.widthIn(max = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                runbook.tags.forEach { tag -> key(tag) { Chip(remember(tag) { tagChipLabel(tag) }) } }
            }
        }
    }
}

internal fun RunbookEntry.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return runbook.label.lowercase().contains(q) ||
        runbook.description.lowercase().contains(q) ||
        runbook.tags.any { it.lowercase().contains(q) } ||
        runbook.group?.lowercase()?.contains(q) == true ||
        runbook.steps.any { it.title.lowercase().contains(q) || it.summaryLine().lowercase().contains(q) }
}
