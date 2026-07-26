package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.Chip
import app.skerry.ui.design.EmptyState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SIDEBAR_WIDTH
import app.skerry.ui.design.SidebarSearchField
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_delete
import app.skerry.ui.generated.resources.runbook_empty
import app.skerry.ui.generated.resources.runbook_library
import app.skerry.ui.generated.resources.runbook_new
import app.skerry.ui.generated.resources.runbook_run
import app.skerry.ui.generated.resources.runbook_run_busy
import app.skerry.ui.generated.resources.runbook_run_needs_session
import app.skerry.ui.generated.resources.runbook_save
import app.skerry.ui.generated.resources.runbook_search
import app.skerry.ui.generated.resources.runbook_select_or_create
import app.skerry.ui.generated.resources.runbook_step_count
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Runbooks section: library of saved procedures (sidebar) plus an editor (main). A runbook is plain
 * config like a snippet — the steps are edited here, and a run happens in a terminal session, from
 * the Run button, with the run panel taking over from there.
 *
 * Its own section rather than a mode of Snippets: the two are edited differently (a step list with
 * per-step flags versus one command line) and reached for at different moments.
 */
@Composable
fun RunbooksView() {
    val manager = LocalRunbooks.current ?: return
    val mono = LocalFonts.current.mono
    var selectedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val all = manager.runbooks
    val selected = if (adding) null else (manager.find(selectedId) ?: all.firstOrNull())

    Row(Modifier.fillMaxSize()) {
        RunbookSidebar(
            all = all,
            query = query,
            onQuery = { query = it },
            selectedId = if (adding) null else selected?.id,
            mono = mono,
            onSelect = { id -> selectedId = id; adding = false },
            onNew = { adding = true; selectedId = null },
        )
        VLine(Skerry.colors.line)
        Box(Modifier.weight(1f).fillMaxHeight().background(Skerry.colors.bg)) {
            if (!adding && selected == null) {
                EmptyState(icon = "checklist", title = stringResource(Res.string.runbook_select_or_create))
            } else {
                // Keyed by the edited runbook's identity so the editor's fields reset instead of
                // carrying over the previous values.
                key(selected?.id, adding) {
                    RunbookEditor(
                        entry = selected,
                        manager = manager,
                        mono = mono,
                        onSaved = { id -> selectedId = id; adding = false },
                        onDeleted = { selectedId = null; adding = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun RunbookSidebar(
    all: List<RunbookEntry>,
    query: String,
    onQuery: (String) -> Unit,
    selectedId: String?,
    mono: FontFamily,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
) {
    val shown = remember(all, query) { all.filter { it.matches(query) } }
    Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            SidebarSearchField(query, onQuery, stringResource(Res.string.runbook_search))
        }
        HLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
            SidebarSectionTitle(
                stringResource(Res.string.runbook_library),
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
            )
            if (shown.isEmpty()) {
                Txt(
                    stringResource(Res.string.runbook_empty), color = Skerry.colors.faint, size = 11.5.sp, font = mono,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp),
                )
            }
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { entry ->
                    key(entry.id) { RunbookRow(entry, entry.id == selectedId, mono) { onSelect(entry.id) } }
                }
            }
        }
        HLine()
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            PrimaryButton(
                stringResource(Res.string.runbook_new), onClick = onNew, icon = "add",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RunbookRow(entry: RunbookEntry, selected: Boolean, mono: FontFamily, onClick: () -> Unit) {
    val runbook = entry.runbook
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .border(
                1.dp,
                if (selected) Skerry.colors.cyan.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(7.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym("checklist", size = 15.sp, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.dim)
            Txt(
                runbook.label.ifBlank { stringResource(Res.string.runbook_untitled) },
                color = if (selected) Skerry.colors.cyanBright else Skerry.colors.textBright,
                size = 12.5.sp, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Txt(
            stringResource(Res.string.runbook_step_count, runbook.steps.size),
            color = if (selected) Skerry.colors.dim else Skerry.colors.faint, size = 10.5.sp, font = mono,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

internal fun RunbookEntry.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return runbook.label.lowercase().contains(q) ||
        runbook.description.lowercase().contains(q) ||
        runbook.tags.any { it.lowercase().contains(q) } ||
        runbook.steps.any { it.title.lowercase().contains(q) || it.command.lowercase().contains(q) }
}

@Composable
private fun RunbookEditor(
    entry: RunbookEntry?,
    manager: RunbookManager,
    mono: FontFamily,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    // Shared form state (desktop and mobile); the editor is recreated externally via key().
    val form = remember { RunbookFormState.fromEntry(entry) }
    val runner = LocalRunbookRunner.current
    val sessions = LocalSessions.current
    val session = sessions?.activeTerminal
    val terminal = (session?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    val busy = runner?.active == true || runner?.pending != null
    // A run needs both halves; keeping them in one value avoids a null check inside the click handler.
    val target = if (session != null && terminal != null) runbookTarget(session.id, terminal) else null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("checklist", size = 20.sp, color = Skerry.colors.cyanBright)
                Txt(
                    form.label.ifBlank { stringResource(Res.string.runbook_new) },
                    color = Skerry.colors.text, size = 17.sp, weight = FontWeight.SemiBold,
                )
                if (form.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { form.tags.forEach { Chip("#$it") } }
                }
            }
        }
        HLine()
        RunbookEditorFields(form, mono)
        Column(Modifier.padding(horizontal = 24.dp)) {
            // The section is app-level and can be open with no session at all; say why Run is inert
            // instead of leaving a button that quietly does nothing.
            val hint = when {
                entry == null -> null
                target == null -> stringResource(Res.string.runbook_run_needs_session)
                busy -> stringResource(Res.string.runbook_run_busy)
                else -> null
            }
            Row(Modifier.padding(top = 4.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    stringResource(Res.string.runbook_save),
                    onClick = { if (form.canSave) onSaved(manager.save(form.toDraft())) },
                    enabled = form.canSave,
                )
                if (entry != null && runner != null) {
                    GhostButton(
                        stringResource(Res.string.runbook_run),
                        icon = "play_arrow",
                        onClick = {
                            if (target != null && terminal != null) {
                                runner.requestStart(entry.runbook, target, recording = terminal.recording)
                            }
                        },
                        fg = if (hint == null) Skerry.colors.cyanBright else Skerry.colors.faint,
                    )
                }
                if (entry != null) {
                    GhostButton(
                        stringResource(Res.string.runbook_delete),
                        onClick = { manager.delete(entry.id); onDeleted() },
                        fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.3f),
                    )
                }
            }
            if (hint != null) {
                Txt(hint, color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(bottom = 20.dp))
            }
        }
    }
}
