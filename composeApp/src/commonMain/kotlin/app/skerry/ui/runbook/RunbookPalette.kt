package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.fieldName
import kotlinx.coroutines.flow.SharedFlow
import app.skerry.ui.design.CloseWhenUnavailable
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.rememberModalPresence
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_empty
import app.skerry.ui.generated.resources.runbook_no_matches
import app.skerry.ui.generated.resources.runbook_none_runnable
import app.skerry.ui.generated.resources.runbook_palette_placeholder
import app.skerry.ui.generated.resources.runbook_run_open
import app.skerry.ui.generated.resources.runbook_step_count
import app.skerry.ui.generated.resources.runbook_toolbar_tip
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.session.Session
import app.skerry.ui.session.SessionView
import app.skerry.ui.terminal.ToolbarAction
import app.skerry.ui.terminal.toolbarActionEnabled
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Toolbar entry point for runbooks: pick a saved procedure and start it in the session already on
 * screen, without going to the Runbooks section first. Modelled on the snippet palette next to it —
 * a runbook is reached for in the same moment, just for a longer job.
 *
 * Picking only *requests* the run; the confirmation dialog (variables + every resolved step) still
 * comes first, and the progress panel takes over from there.
 */
@Composable
fun RunbookPaletteButton(active: Session?, requests: SharedFlow<Unit>? = null) {
    val manager = LocalRunbooks.current
    val runner = LocalRunbookRunner.current
    val terminal = (active?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    // Keyed on active: switching tabs must not leave the palette open over a different toolbar.
    var open by remember(active) { mutableStateOf(false) }
    // Same signal channel the snippet palette uses: the shortcut and the overflow menu reach the
    // palette without this button having to be on screen (it may be parked out of a narrow toolbar).
    // The same condition the button carries, not half of it: a request that arrives mid-run would
    // otherwise set the flag for a popup the render guard then drops without a word.
    LaunchedEffect(requests, terminal) {
        requests?.collect { if (terminal != null && runner?.let { !it.active && it.pending == null } == true) open = true }
    }
    if (manager == null || runner == null) return
    // While this tab is part of a run, the icon is the way back to the run screen rather than a
    // palette: a second runbook can't start anyway, and the run is what the icon now stands for.
    val inRun = active?.id?.let(runner::runIn)
    // Nothing to run into without a connected session, and one run at a time: the button is
    // disabled rather than offering a list that can't start anything. Disabled, not merely dimmed —
    // a guard inside the handler would still take the press and the focus with nothing to show.
    val enabled = toolbarActionEnabled(ToolbarAction.Runbook, active)
    // Ahead of the early return below on purpose: that return takes this out of the composition for
    // the whole length of a run started from the Runbooks section, which is exactly when the flag
    // has to be cleared — otherwise the palette is back the moment the run ends.
    CloseWhenUnavailable(enabled && inRun == null) { open = false }
    if (inRun != null) {
        val sessions = LocalSessions.current
        IconBtn(
            "checklist",
            onClick = { sessions?.setActiveView(SessionView.Runbook) },
            tint = if (runner.phase == RunbookPhase.AWAITING_CONFIRM) Skerry.colors.cyanBright else Skerry.colors.cyan,
            tooltip = stringResource(Res.string.runbook_run_open),
        )
        return
    }
    Box {
        IconBtn(
            "checklist",
            onClick = { open = !open },
            enabled = enabled,
            tooltip = stringResource(Res.string.runbook_toolbar_tip),
        )
        if (open && enabled) {
            // The pane and its terminal spelled out again rather than leaned on through `enabled`:
            // the palette hands both to the runner, and the compiler cannot see through a predicate.
            val pane = active
            val live = terminal
            if (pane != null && live != null) {
                Popup(
                    alignment = Alignment.TopEnd,
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    RunbookPalette(manager) { entry ->
                        runner.requestStart(
                            entry.runbook,
                            runbookTarget(pane.id, live, pane.controller),
                            recording = live.recording,
                        )
                        open = false
                    }
                }
            }
        }
    }
}

@Composable
internal fun RunbookPalette(manager: RunbookManager, onPick: (RunbookEntry) -> Unit) {
    // Registered like the snippet palette: it lives in a focusable Popup and must hand the keyboard
    // back to the terminal when it closes.
    rememberModalPresence()
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    // The palette exists to start a run: a runbook with no steps would close the popup and start
    // nothing, so it is not offered here (the section's card explains it instead).
    val saved = manager.runbooks
    val all = remember(saved) { saved.filter { it.runbook.steps.isNotEmpty() } }
    val filtered = if (query.isBlank()) all else all.filter { it.matches(query) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Column(
        Modifier.width(320.dp).clip(RoundedCornerShape(9.dp)).background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(9.dp)).padding(6.dp),
    ) {
        val textColor = Skerry.colors.text
        val style = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 12.5.sp, fontFamily = mono) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg)
                .border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym("search", size = 15.sp, color = Skerry.colors.faint)
            val placeholder = stringResource(Res.string.runbook_palette_placeholder)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Txt(placeholder, color = Skerry.colors.faint, size = 12.5.sp, font = mono)
                }
                BasicTextField(
                    query, { query = it }, singleLine = true, textStyle = style,
                    cursorBrush = SolidColor(Skerry.colors.cyan),
                    // The placeholder is the only label this field draws (see fieldName).
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).fieldName(placeholder),
                )
            }
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            if (filtered.isEmpty()) {
                Txt(
                    // Three different facts: nothing saved, nothing runnable, nothing matching.
                    when {
                        saved.isEmpty() -> stringResource(Res.string.runbook_empty)
                        all.isEmpty() -> stringResource(Res.string.runbook_none_runnable)
                        else -> stringResource(Res.string.runbook_no_matches)
                    },
                    color = Skerry.colors.faint, size = 11.5.sp, font = mono, modifier = Modifier.padding(8.dp),
                )
            } else {
                filtered.forEach { entry -> key(entry.id) { PaletteRow(entry, mono) { onPick(entry) } } }
            }
        }
    }
}

@Composable
private fun PaletteRow(entry: RunbookEntry, mono: FontFamily, onClick: () -> Unit) {
    val runbook = entry.runbook
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Sym("checklist", size = 14.sp, color = Skerry.colors.dim)
            Txt(
                // Stripped like the run panel's rows: a runbook can arrive over sync, and this is
                // one of the places its name is read before starting it.
                remember(runbook) { untrustedLabel(runbook.label) }.ifBlank { stringResource(Res.string.runbook_untitled) },
                color = Skerry.colors.textBright, size = 12.5.sp, weight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Txt(
            stringResource(Res.string.runbook_step_count, runbook.steps.size),
            color = Skerry.colors.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 3.dp),
        )
    }
}
