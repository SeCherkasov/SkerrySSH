package app.skerry.ui.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.EmptyState
import app.skerry.ui.design.FolderCollapse
import app.skerry.ui.design.FolderSections
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SectionHeader
import app.skerry.ui.design.SidebarSearchField
import app.skerry.ui.design.VLine
import app.skerry.ui.host.GroupDialog
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_command_count
import app.skerry.ui.generated.resources.lib_snippets_empty
import app.skerry.ui.generated.resources.lib_snippets_facts_variables
import app.skerry.ui.generated.resources.help_button
import app.skerry.ui.generated.resources.lib_snippets_new
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_screen_title
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.generated.resources.lib_snippets_select_or_create
import app.skerry.ui.generated.resources.lib_snippets_starter_pack
import app.skerry.ui.session.SessionsController
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vault.copyTextToClipboard
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/** Width of the search field in the section header. */
private val SEARCH_WIDTH = 240.dp

/**
 * Global Snippets section: a flat library of saved commands with a run panel on the right. A snippet
 * is a self-contained plain config, no secrets. Running happens here (into a connected session), from
 * the terminal palette, a hotkey, or "Run snippet…" in a host's context menu; editing is a separate
 * mode of the same panel. Shows a live list when a manager is provided ([LocalSnippets]); otherwise
 * (offscreen render/preview) an empty state.
 */
@Composable
fun SnippetsView(state: DesktopDesignState) {
    val mono = LocalFonts.current.mono
    val manager = LocalSnippets.current
    if (manager == null) {
        Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
            SnippetsHeader(SnippetLibraryFacts(0, 0), query = "", onQuery = {}, onNew = {})
            EmptyState(icon = "code_blocks", title = stringResource(Res.string.lib_snippets_empty))
        }
        return
    }
    LiveSnippetsView(manager, state.snippetLibrary, state, mono)
}

/** What the panel is showing: the selected snippet's run card, a new snippet's form, or an edit form. */
private sealed interface PanelMode {
    data object Run : PanelMode
    data object New : PanelMode

    /**
     * Editing [id]. The id is carried rather than re-read from the list selection: the search field
     * and the tag chips stay live while the form is open, and a filter that hides the edited snippet
     * would otherwise swap the form onto a different one, discarding unsaved changes.
     */
    data class Edit(val id: String) : PanelMode
}

@Composable
private fun LiveSnippetsView(
    manager: SnippetManager,
    library: SnippetLibraryState,
    collapse: FolderCollapse,
    mono: FontFamily,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mode by remember { mutableStateOf<PanelMode>(PanelMode.Run) }
    var helpOpen by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<String?>(null) }

    val all = manager.snippets
    val visible = library.visible(all)
    val selected = resolveSelectedSnippet(visible, selectedId)
    // Keyed on the snippet values, not on `all`: saving an edit mutates an entry in place, so the
    // list keeps its identity while the variable count changes.
    val snippets = all.map { it.snippet }
    val facts = remember(snippets) { snippetLibraryFacts(snippets) }

    val sessions = LocalSessions.current
    val targets = snippetRunTargets(sessions)

    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        SnippetsHeader(
            facts = facts,
            query = library.query,
            onQuery = { library.query = it },
            onNew = { mode = PanelMode.New },
            onHelp = { helpOpen = true },
        )
        if (all.any { it.snippet.tags.isNotEmpty() }) {
            SnippetTagFilterRow(
                chips = library.chips(all),
                library = library,
                all = all,
                onRenameTag = { oldTag, newTag -> manager.renameTag(oldTag, newTag)?.let { library.onTagRenamed(oldTag, it) } },
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
            )
        }
        HLine()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (visible.isEmpty()) {
                    SnippetsEmptyList(libraryEmpty = all.isEmpty(), onInstallStarterPack = { manager.installStarterPack() })
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        // Folders section the library; with nothing filed it stays the flat list it
                        // was. The chip row and the search narrow first — a folder header counts
                        // what the filter left in it, not what the library holds.
                        FolderSections(
                            items = visible,
                            scope = SNIPPET_FOLDER_SCOPE,
                            collapse = collapse,
                            group = { it.snippet.group },
                            itemKey = { it.id },
                            selectedIds = selectedIds,
                            onEditGroup = { editingGroup = it },
                            onMoveItems = { ids, targetGroup, targetIndex ->
                                manager.moveSnippets(ids, targetGroup, targetIndex)
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
                                    mode = PanelMode.Run
                                }
                            }
                            SnippetListRow(entry = entry, selected = isSelected, onClick = onClick)
                            HLine()
                        }
                    }
                }
            }
            val editMode = mode as? PanelMode.Edit
            val editing = editMode?.let { manager.find(it.id) }
            when {
                mode is PanelMode.New || editing != null -> {
                    VLine(Skerry.colors.line)
                    Box(Modifier.width(SNIPPET_PANEL_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)) {
                        // Keyed by the edited snippet's identity so the form resets instead of
                        // carrying over the previous values.
                        key(editing?.id, mode) {
                            SnippetEditor(
                                entry = editing,
                                manager = manager,
                                mono = mono,
                                onSaved = { id -> selectedId = id; mode = PanelMode.Run },
                                onCancel = { mode = PanelMode.Run },
                            )
                        }
                    }
                }
                selected != null -> {
                    VLine(Skerry.colors.line)
                    key(selected.id) {
                        SnippetRunPanel(
                            entry = selected,
                            targets = targets,
                            activeTargetId = sessions?.active?.focusedPaneId,
                            mono = mono,
                            onRun = { target, params -> runSnippetInSession(manager, sessions, selected.id, target.id, params) },
                            onCopy = { copyTextToClipboard(it) },
                            onEdit = { mode = PanelMode.Edit(selected.id) },
                            onDelete = { manager.delete(selected.id); selectedId = null },
                        )
                    }
                }
            }
        }
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
    if (helpOpen) SnippetHelpDialog(manager, onDismiss = { helpOpen = false })
}

@Composable
private fun SnippetsHeader(
    facts: SnippetLibraryFacts,
    query: String,
    onQuery: (String) -> Unit,
    onNew: () -> Unit,
    onHelp: (() -> Unit)? = null,
) {
    val commands = pluralStringResource(Res.plurals.lib_snippets_command_count, facts.total, facts.total)
    SectionHeader(
        title = stringResource(Res.string.lib_snippets_screen_title),
        // Two numbers, because they answer different questions: how much is saved, and how much of
        // it stops to ask for something before running.
        subtitle = if (facts.withVariables > 0) {
            "$commands · ${stringResource(Res.string.lib_snippets_facts_variables, facts.withVariables)}"
        } else {
            commands
        },
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SidebarSearchField(query, onQuery, stringResource(Res.string.lib_snippets_search), Modifier.width(SEARCH_WIDTH))
                if (onHelp != null) {
                    GhostButton(stringResource(Res.string.help_button), onClick = onHelp, icon = "help", modifier = Modifier.testTag(UiTags.HELP))
                }
                PrimaryButton(stringResource(Res.string.lib_snippets_new), onClick = onNew, icon = "add", modifier = Modifier.testTag(UiTags.NEW_SNIPPET))
            }
        },
    )
}

@Composable
private fun SnippetsEmptyList(libraryEmpty: Boolean, onInstallStarterPack: () -> Unit) {
    EmptyState(
        icon = "code_blocks",
        title = if (libraryEmpty) stringResource(Res.string.lib_snippets_empty) else stringResource(Res.string.lib_snippets_no_matches),
        subtitle = if (libraryEmpty) stringResource(Res.string.lib_snippets_select_or_create) else null,
        action = if (libraryEmpty) {
            {
                ChipButton(
                    label = stringResource(Res.string.lib_snippets_starter_pack),
                    color = Skerry.colors.cyan,
                    onClick = onInstallStarterPack,
                )
            }
        } else {
            null
        },
    )
}

/**
 * Sessions a snippet can run into: connected terminal panes across every tab. A remote desktop, a
 * recording player and a pane that is still connecting have no shell to take the line, so they never
 * become targets.
 */
@Composable
private fun snippetRunTargets(sessions: SessionsController?): List<SnippetRunTarget> {
    if (sessions == null) return emptyList()
    return sessions.tabs.flatMap { tab -> tab.panes }
        .filter { pane -> !pane.isVnc && !pane.isPlayer && pane.controller.uiState is ConnectionUiState.Connected }
        .map { pane -> SnippetRunTarget(id = pane.id, label = pane.displayTitle) }
}

/**
 * Run [snippetId] in the pane [targetId]. The terminal is looked up at click time, so a session
 * closed between the frame that enabled Run and the click cannot be sent into; `false` says so, and
 * the panel reports it rather than leaving a dropped run looking like a command with no output. A
 * snippet with variables parks in [SnippetManager.pendingRun] and reaches the terminal only after
 * the confirmation dialog.
 */
private fun runSnippetInSession(
    manager: SnippetManager,
    sessions: SessionsController?,
    snippetId: String,
    targetId: String,
    params: Map<String, String>,
): Boolean {
    val pane = sessions?.tabs?.flatMap { it.panes }?.firstOrNull { it.id == targetId } ?: return false
    val terminal = (pane.controller.uiState as? ConnectionUiState.Connected)?.terminal ?: return false
    manager.run(snippetId, recording = terminal.recording, params = params) { text, secrets -> terminal.sendUserInputGuarded(text, secrets) }
    return true
}
