package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Badge
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_fkey_copy
import app.skerry.ui.generated.resources.ftail_fkey_delete
import app.skerry.ui.generated.resources.ftail_fkey_edit
import app.skerry.ui.generated.resources.ftail_fkey_mkdir
import app.skerry.ui.generated.resources.ftail_fkey_move
import app.skerry.ui.generated.resources.ftail_fkey_quit
import app.skerry.ui.generated.resources.ftail_fkey_refresh
import app.skerry.ui.generated.resources.ftail_fkey_rename
import app.skerry.ui.generated.resources.ftail_fkey_save
import app.skerry.ui.generated.resources.ftail_fkey_search
import app.skerry.ui.generated.resources.ftail_fkey_view
import app.skerry.ui.generated.resources.settings_badge_soon
import app.skerry.ui.generated.resources.settings_kb_accept_autocomplete
import app.skerry.ui.generated.resources.settings_kb_broadcast
import app.skerry.ui.generated.resources.settings_kb_editor_group
import app.skerry.ui.generated.resources.settings_kb_command_palette
import app.skerry.ui.generated.resources.settings_kb_copy_selection
import app.skerry.ui.generated.resources.settings_kb_cycle_suggestions
import app.skerry.ui.generated.resources.settings_kb_files_filter
import app.skerry.ui.generated.resources.settings_kb_files_group
import app.skerry.ui.generated.resources.settings_kb_files_hidden
import app.skerry.ui.generated.resources.settings_kb_files_save
import app.skerry.ui.generated.resources.settings_kb_files_switch_pane
import app.skerry.ui.generated.resources.settings_kb_focus_ai
import app.skerry.ui.generated.resources.settings_kb_global
import app.skerry.ui.generated.resources.settings_kb_lock
import app.skerry.ui.generated.resources.settings_kb_new_connection
import app.skerry.ui.generated.resources.settings_kb_next_prev_tab
import app.skerry.ui.generated.resources.settings_kb_open_sftp
import app.skerry.ui.generated.resources.settings_kb_paste
import app.skerry.ui.generated.resources.settings_kb_play_recording
import app.skerry.ui.generated.resources.settings_kb_record_session
import app.skerry.ui.generated.resources.settings_kb_search_history
import app.skerry.ui.generated.resources.settings_kb_select_tab_number
import app.skerry.ui.generated.resources.settings_kb_snippet_palette
import app.skerry.ui.generated.resources.settings_kb_split_terminal
import app.skerry.ui.generated.resources.settings_kb_terminal_group
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

// Keyboard section: hotkey reference (global and terminal).

@Composable
internal fun KeyboardSection() {
    // Platform-specific labels: ⌘/⌥ on macOS, Ctrl+Shift/Alt on Linux/Windows — matching what
    // matchDesktopShortcut recognizes. The Ctrl path requires Shift, so plain Ctrl+letter (Ctrl+L
    // clear, Ctrl+D EOF, Ctrl+C signal) stays reserved for the terminal.
    val mac = isApplePlatform()
    val mod: (String) -> String = { k -> if (mac) "⌘$k" else "Ctrl+Shift+$k" }
    // Terminal chords use literal Ctrl/Shift/Tab (not the app modifier): shown as ⌃/⇧ symbols on
    // macOS, as words elsewhere.
    val ctrl: (String) -> String = { k -> if (mac) "⌃$k" else "Ctrl+$k" }
    val ctrlShift: (String) -> String = { k -> if (mac) "⌃⇧$k" else "Ctrl+Shift+$k" }
    val shift: (String) -> String = { k -> if (mac) "⇧$k" else "Shift+$k" }

    val global = listOf(
        KeyboardBinding(stringResource(Res.string.settings_kb_new_connection), mod("N"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_command_palette), mod("K"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_snippet_palette), mod("S"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_broadcast), mod("B"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_record_session), mod("R"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_play_recording), mod("P"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_split_terminal), mod("D"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_next_prev_tab), "${ctrl("Tab")} / ${ctrlShift("Tab")}", live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_select_tab_number), if (mac) "⌥1–9" else "Alt+1–9", live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_focus_ai), mod("/"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_open_sftp), mod("F"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_lock), mod("L"), live = true),
    )
    // Terminal-internal hotkeys (handled by TerminalScreen): fish-style autocomplete, history
    // reverse-search (Ctrl-R), copy/paste. Active while a terminal session is focused.
    val terminal = listOf(
        KeyboardBinding(stringResource(Res.string.settings_kb_accept_autocomplete), "Tab", live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_cycle_suggestions), shift("Tab"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_search_history), ctrl("R"), live = true),
        // Both conventions are live, so both are listed: Ctrl+Shift+C/V and the X11 Insert pair.
        // Both conventions work, so both are listed: Ctrl+Shift+C/V and the X11 Insert pair.
        KeyboardBinding(stringResource(Res.string.settings_kb_copy_selection), "${ctrlShift("C")} / ${ctrl("Insert")}", live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_paste), "${ctrlShift("V")} / ${shift("Insert")}", live = true),
    )

    // File panel (SFTP view) F-keys, mc/Total Commander style — the same labels the bottom bar uses.
    // Ctrl+S belongs to the built-in editor opened by F4.
    val files = listOf(
        KeyboardBinding(stringResource(Res.string.ftail_fkey_rename), "F2", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_view), "F3", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_edit), "F4", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_copy), "F5", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_move), "F6", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_mkdir), "F7", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_delete), "F8", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_refresh), "F9", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_quit), "F10", live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_files_switch_pane), "Tab", live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_files_hidden), ctrl("H"), live = true),
        KeyboardBinding(stringResource(Res.string.settings_kb_files_filter), ctrl("F"), live = true),
    )
    // The built-in viewer/editor (F3/F4) opens inside the file panel and redefines the same bar of
    // function keys while it is there, so its keys are listed as their own group.
    val editor = listOf(
        KeyboardBinding(stringResource(Res.string.ftail_fkey_save), "F2", live = true),
        // The editor takes ⌘S on macOS (what a mac user reaches for) and Ctrl+S elsewhere — not the
        // app-wide mod() chord, which is Ctrl+Shift+ on Linux/Windows.
        KeyboardBinding(stringResource(Res.string.settings_kb_files_save), if (mac) "⌘S" else "Ctrl+S", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_edit), "F4", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_search), "F7", live = true),
        KeyboardBinding(stringResource(Res.string.ftail_fkey_quit), "F10 / Esc", live = true),
    )

    val mono = LocalFonts.current.mono
    KeyboardGroupLabel(stringResource(Res.string.settings_kb_global), top = 0.dp)
    global.forEach { KeyboardRow(it, mono) }
    KeyboardGroupLabel(stringResource(Res.string.settings_kb_terminal_group), top = 18.dp)
    terminal.forEach { KeyboardRow(it, mono) }
    KeyboardGroupLabel(stringResource(Res.string.settings_kb_files_group), top = 18.dp)
    files.forEach { KeyboardRow(it, mono) }
    KeyboardGroupLabel(stringResource(Res.string.settings_kb_editor_group), top = 18.dp)
    editor.forEach { KeyboardRow(it, mono) }
}

@Composable
private fun KeyboardGroupLabel(text: String, top: Dp) {
    SectionLabel(text, top = top, bottom = 8.dp)
}

@Composable
private fun KeyboardRow(b: KeyboardBinding, mono: FontFamily) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(b.label, color = if (b.live) Skerry.colors.text else Skerry.colors.dim, size = 13.sp, weight = FontWeight.Medium)
            // Command palette isn't implemented yet; mark the binding SOON instead of showing a
            // dead shortcut next to working ones.
            if (!b.live) Badge(stringResource(Res.string.settings_badge_soon), bg = Skerry.colors.amber.copy(alpha = 0.10f), fg = Skerry.colors.amber, radius = 3, size = 9.sp)
        }
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Skerry.colors.overlaySoft).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Txt(b.binding, color = Skerry.colors.dim, size = 11.sp, font = mono)
        }
    }
    HLine()
}

/** A row on the Keyboard page: label, shortcut, and whether it's live (otherwise a SOON badge). */
private data class KeyboardBinding(val label: String, val binding: String, val live: Boolean)
