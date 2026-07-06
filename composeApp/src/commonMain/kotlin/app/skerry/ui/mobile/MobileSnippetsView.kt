package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.snippet.Snippet
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.snippet.SnippetDraft
import app.skerry.ui.snippet.SnippetEntry
import app.skerry.ui.snippet.SnippetFormState
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.snippet.matches
import app.skerry.ui.snippet.snippetTagSuggestions
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_add_tag
import app.skerry.ui.generated.resources.lib_snippets_delete
import app.skerry.ui.generated.resources.lib_snippets_edit
import app.skerry.ui.generated.resources.lib_snippets_empty_mobile
import app.skerry.ui.generated.resources.lib_snippets_field_command
import app.skerry.ui.generated.resources.lib_snippets_field_name
import app.skerry.ui.generated.resources.lib_snippets_field_tags
import app.skerry.ui.generated.resources.lib_snippets_new
import app.skerry.ui.generated.resources.lib_snippets_no_matches
import app.skerry.ui.generated.resources.lib_snippets_ph_name
import app.skerry.ui.generated.resources.lib_snippets_run_empty
import app.skerry.ui.generated.resources.lib_snippets_run_in_terminal
import app.skerry.ui.generated.resources.lib_snippets_run_title
import app.skerry.ui.generated.resources.lib_snippets_save_snippet
import app.skerry.ui.generated.resources.lib_snippets_screen_title
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.generated.resources.lib_snippets_untitled
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

private data class MockMobileSnippet(val icon: String, val title: String, val cmd: String)

private val MOCK_MOBILE_SNIPPETS = listOf(
    MockMobileSnippet("monitoring", "Disk usage report", "df -h | sort -k5 -r | head"),
    MockMobileSnippet("memory", "Top memory procs", "ps aux --sort=-%mem | head"),
    MockMobileSnippet("restart_alt", "Restart nginx", "sudo systemctl reload nginx"),
    MockMobileSnippet("cleaning_services", "Clear docker cache", "docker system prune -af"),
)

/**
 * Snippets tab: library of saved commands + add FAB. Tapping a card opens the edit sheet
 * (Name/Command/Tags with type-ahead, Run/Save/Delete). A snippet is self-contained plain config,
 * no secrets.
 *
 * Live path ([LocalSnippets] != null, behind the vault gate) uses the real library from
 * [SnippetManager]; preview/offscreen without a manager renders static mock cards.
 */
@Composable
fun MobileSnippetsScreen(state: MobileDesignState) {
    when (val manager = LocalSnippets.current) {
        null -> MobileSnippetsMock()
        else -> MobileSnippetsLive(state, manager)
    }
}

@Composable
private fun MobileSnippetsLive(state: MobileDesignState, manager: SnippetManager) {
    val mono = LocalFonts.current.mono
    val sessions = LocalSessions.current
    // Snippet run targets the active connected session; no live terminal means no Run button in the editor.
    val activeTerminal = (sessions?.active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal

    var editing by remember { mutableStateOf<SnippetEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val sheetOpen = adding || editing != null

    // Open edit sheet hides the tab bar (otherwise it floats above the input fields over the keyboard).
    LaunchedEffect(sheetOpen) { state.modalOverlay(sheetOpen) }
    DisposableEffect(Unit) { onDispose { state.modalOverlay(false) } }

    val snippets = manager.snippets

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
                MobileScreenTitle(stringResource(Res.string.lib_snippets_screen_title))
            }
            if (snippets.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 30.dp)) {
                    Txt(stringResource(Res.string.lib_snippets_empty_mobile), color = D.faint, size = 13.sp)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    snippets.forEach { entry ->
                        key(entry.id) {
                            val onClick = remember(entry.id) { { editing = entry; adding = false } }
                            MobileSnippetCard(entry.snippet, mono, onClick)
                        }
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }

        // Add FAB (bottom-right, above the tab bar). Hidden while the edit sheet is open.
        if (!sheetOpen) {
            MobileFabButton(
                onClick = { adding = true; editing = null },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp),
            )
        }

        if (sheetOpen) {
            val target = editing
            MobileSnippetEditSheet(
                entry = target,
                allSnippets = snippets.map { it.snippet },
                mono = mono,
                canRun = target != null && activeTerminal != null,
                onDismiss = { adding = false; editing = null },
                onSave = { draft ->
                    manager.save(draft)
                    adding = false; editing = null
                },
                onDelete = target?.let { e -> { manager.delete(e.id); adding = false; editing = null } },
                onRun = run@{
                    val e = target ?: return@run
                    manager.run(e.id) { text -> activeTerminal?.send(text) }
                    adding = false; editing = null
                    sessions?.active?.let { state.push(MobileRoute.Terminal) }
                },
            )
        }
    }
}

@Composable
private fun MobileSnippetCard(snippet: Snippet, mono: FontFamily, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(D.card)
            .border(1.dp, D.cyan08, RoundedCornerShape(13.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Sym("code_blocks", size = 18.sp, color = D.cyanBright)
            Txt(snippet.label.ifBlank { stringResource(Res.string.lib_snippets_untitled) }, color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
        }
        if (snippet.command.isNotBlank()) {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(D.terminalBg).padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Txt(snippet.command, color = D.dim, size = 11.5.sp, font = mono)
            }
        }
        if (snippet.tags.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snippet.tags.forEach { tag -> key(tag) { SnippetTagChip(tag) } }
            }
        }
    }
}

@Composable
private fun SnippetTagChip(tag: String) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(D.cyan.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        Txt("#$tag", color = D.cyanBright, size = 11.sp)
    }
}

/**
 * Snippet-run picker opened from the terminal header (`bolt` icon): list of saved commands, tap
 * runs the selected snippet in the active session via [onRun].
 */
@Composable
internal fun MobileSnippetRunSheet(manager: SnippetManager, onRun: (SnippetEntry) -> Unit, onDismiss: () -> Unit) {
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    val all = manager.snippets
    val filtered = if (query.isBlank()) all else all.filter { it.matches(query) }
    // Inline sheet (like the Vault/New connection sheets), rendered at the screen's top-level Box,
    // not via Popup: a focusable Popup shifted window insets and slightly moved the terminal header.
    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.7f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(stringResource(Res.string.lib_snippets_run_title), color = D.text, size = 18.sp, weight = FontWeight.Bold)
            MobileFormInput(query, { query = it }, stringResource(Res.string.lib_snippets_search))
            if (filtered.isEmpty()) {
                Txt(if (all.isEmpty()) stringResource(Res.string.lib_snippets_run_empty) else stringResource(Res.string.lib_snippets_no_matches), color = D.faint, size = 13.sp)
            } else {
                filtered.forEach { entry ->
                    key(entry.id) {
                        val onClick = remember(entry.id) { { onRun(entry) } }
                        MobileSnippetCard(entry.snippet, mono, onClick)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// --- Edit sheet ---

/**
 * Snippet create/edit sheet. [entry] == null means create.
 * [canRun]/[onRun] apply only when editing an existing snippet with a live terminal.
 */
@Composable
private fun MobileSnippetEditSheet(
    entry: SnippetEntry?,
    allSnippets: List<Snippet>,
    mono: FontFamily,
    canRun: Boolean,
    onDismiss: () -> Unit,
    onSave: (SnippetDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onRun: () -> Unit,
) {
    // Shared form state (desktop <-> mobile): seeds from entry (including shortcut, so Save
    // doesn't drop a hotkey assigned on desktop), canSave, tags, draft assembly.
    val form = remember { SnippetFormState.fromEntry(entry) }

    MobileBottomSheet(onDismiss = onDismiss, maxHeightFraction = 0.9f) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt(if (entry == null) stringResource(Res.string.lib_snippets_new) else stringResource(Res.string.lib_snippets_edit), color = D.text, size = 18.sp, weight = FontWeight.Bold)
            MobileFormField(stringResource(Res.string.lib_snippets_field_name)) {
                MobileFormInput(form.label, { form.label = it }, stringResource(Res.string.lib_snippets_ph_name))
            }
            MobileFormField(stringResource(Res.string.lib_snippets_field_command)) {
                MobileFormInput(form.command, { form.command = it }, "df -h | sort -k5 -r", mono = true, background = D.terminalBg, singleLine = false, minHeightDp = 88)
            }
            MobileFormField(stringResource(Res.string.lib_snippets_field_tags)) {
                // Own tags are excluded via selected; suggestions come from all snippets (including the
                // one being edited - its own tags are already selected, so won't be suggested again).
                val others = remember(allSnippets, entry?.id) { allSnippets.filter { it.id != entry?.id } }
                val suggestions = remember(others, form.tags, form.tagDraft) { snippetTagSuggestions(others, form.tags, form.tagDraft) }
                MobileTagsEditor(
                    tags = form.tags,
                    onRemove = form::removeTag,
                    draft = form.tagDraft,
                    onDraftChange = form::updateTagDraft,
                    onCommit = { form.addTags(form.tagDraft) },
                    suggestions = suggestions,
                    placeholder = stringResource(Res.string.lib_snippets_add_tag),
                    onPick = form::pickTag,
                    menuBackground = D.surface2,
                )
            }
            if (canRun) {
                MobileSheetButton(stringResource(Res.string.lib_snippets_run_in_terminal), onClick = onRun, icon = "bolt", filled = false, modifier = Modifier.fillMaxWidth())
            }
            MobileSheetButton(
                stringResource(Res.string.lib_snippets_save_snippet),
                onClick = { if (form.canSave) onSave(form.toDraft()) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (onDelete != null) {
                MobileSheetButton(stringResource(Res.string.lib_snippets_delete), onClick = onDelete, filled = false, danger = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}


// --- Static mock (preview/offscreen without a manager) ---

@Composable
private fun MobileSnippetsMock() {
    val mono = LocalFonts.current.mono
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(D.bg).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp)) {
                MobileScreenTitle(stringResource(Res.string.lib_snippets_screen_title))
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MOCK_MOBILE_SNIPPETS.forEach { s ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(D.card).border(1.dp, D.cyan08, RoundedCornerShape(13.dp)).padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Sym(s.icon, size = 18.sp, color = D.cyanBright)
                            Txt(s.title, color = D.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                        }
                        Box(
                            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(D.terminalBg).padding(horizontal = 11.dp, vertical = 9.dp),
                        ) {
                            Txt(s.cmd, color = D.dim, size = 11.5.sp, font = mono)
                        }
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }
        MobileFabButton(
            onClick = null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 104.dp),
        )
    }
}
