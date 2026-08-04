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
import app.skerry.shared.snippet.stripUnsafeFormatChars
import app.skerry.ui.app.LocalRunbookRunner
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.connection.ConnectionUiState
import kotlinx.coroutines.flow.SharedFlow
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.rememberModalPresence
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_empty
import app.skerry.ui.generated.resources.runbook_no_matches
import app.skerry.ui.generated.resources.runbook_palette_placeholder
import app.skerry.ui.generated.resources.runbook_step_count
import app.skerry.ui.generated.resources.runbook_toolbar_tip
import app.skerry.ui.generated.resources.runbook_untitled
import app.skerry.ui.session.Session
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
    LaunchedEffect(requests, terminal) { requests?.collect { if (terminal != null) open = true } }
    if (manager == null || runner == null) return
    // Nothing to run into without a connected session, and one run at a time: the button dims and
    // doesn't open rather than offering a list that can't start anything.
    val enabled = terminal != null && !runner.active && runner.pending == null
    Box {
        IconBtn(
            "checklist",
            onClick = { if (enabled) open = !open },
            tint = if (enabled) Skerry.colors.dim else Skerry.colors.faint,
            tooltip = stringResource(Res.string.runbook_toolbar_tip),
        )
        if (open && enabled && terminal != null && active != null) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                RunbookPalette(manager) { entry ->
                    runner.requestStart(
                        entry.runbook,
                        runbookTarget(active.id, terminal, active.controller),
                        recording = terminal.recording,
                    )
                    open = false
                }
            }
        }
    }
}

@Composable
private fun RunbookPalette(manager: RunbookManager, onPick: (RunbookEntry) -> Unit) {
    // Registered like the snippet palette: it lives in a focusable Popup and must hand the keyboard
    // back to the terminal when it closes.
    rememberModalPresence()
    val mono = LocalFonts.current.mono
    var query by remember { mutableStateOf("") }
    val all = manager.runbooks
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
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Txt(stringResource(Res.string.runbook_palette_placeholder), color = Skerry.colors.faint, size = 12.5.sp, font = mono)
                }
                BasicTextField(
                    query, { query = it }, singleLine = true, textStyle = style,
                    cursorBrush = SolidColor(Skerry.colors.cyan),
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                )
            }
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            if (filtered.isEmpty()) {
                Txt(
                    if (all.isEmpty()) stringResource(Res.string.runbook_empty) else stringResource(Res.string.runbook_no_matches),
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
                stripUnsafeFormatChars(runbook.label).ifBlank { stringResource(Res.string.runbook_untitled) },
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
