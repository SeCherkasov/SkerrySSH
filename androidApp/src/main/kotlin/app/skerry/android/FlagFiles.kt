package app.skerry.android

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * An on/off setting kept as "true"/"false" in its own file under the app's files dir. Missing or
 * unreadable reads as [default]; writes are best-effort, off the UI thread.
 */
internal class FlagFile(val name: String, val default: Boolean) {
    fun read(dir: File): Boolean =
        runCatching { File(dir, name).readText().trim().toBoolean() }.getOrDefault(default)
}

internal fun LifecycleOwner.writeFlag(dir: File, flag: FlagFile, enabled: Boolean) {
    lifecycleScope.launch(Dispatchers.IO) {
        runCatching { File(dir, flag.name).writeText(enabled.toString()) }
    }
}

/**
 * OSC 52 server clipboard-write gate (More → Appearance → Terminal): "true"/"false" in
 * `terminal_clipboard_write`. Missing/unreadable → false (off by default). Best-effort, off the UI thread.
 */
internal val FLAG_CLIPBOARD_WRITE = FlagFile("terminal_clipboard_write", default = false)

/**
 * Offering the saved password at a sudo prompt (More → Appearance → Terminal): "true"/"false"
 * in `terminal_sudo_password`. Missing/unreadable → false (off by default, issue #360).
 */
internal val FLAG_OFFER_SUDO_PASSWORD = FlagFile("terminal_sudo_password", default = false)

/** Experimental "type ssh on the jump host" choice in the connection form: `experimental_jump_shell`, default off. */
internal val FLAG_JUMP_VIA_SHELL_OFFERED = FlagFile("experimental_jump_shell", default = false)

/** Reporting sessions on team-shared hosts: `teams_report_sessions`, default on. */
internal val FLAG_REPORT_TEAM_SESSIONS = FlagFile("teams_report_sessions", default = true)

/** Terminal shrink-to-fit: `terminal_autofit`, default off. */
internal val FLAG_TERMINAL_AUTO_FIT = FlagFile("terminal_autofit", default = false)

/** Clickable file paths in terminal output: `terminal_open_paths`, default on. */
internal val FLAG_OPEN_FILE_PATHS = FlagFile("terminal_open_paths", default = true)

/** Command-line syntax highlighting: `terminal_highlight_input`, default on. */
internal val FLAG_HIGHLIGHT_INPUT = FlagFile("terminal_highlight_input", default = true)

/** Log-level highlighting in output: `terminal_highlight_output`, default off. */
internal val FLAG_HIGHLIGHT_OUTPUT = FlagFile("terminal_highlight_output", default = false)

/** Production guard threshold: `terminal_prod_warnings`, default off (Danger only). */
internal val FLAG_PROD_WARNINGS = FlagFile("terminal_prod_warnings", default = false)

/**
 * Hiding the phone's system bars inside a session (More → Appearance → Interface):
 * `hide_system_bars`, default off — the bars belong to the phone.
 */
internal val FLAG_HIDE_SYSTEM_BARS = FlagFile("hide_system_bars", default = false)

/** Separately-picked terminal theme flag (unified theming): `custom_terminal_theme`, default off. */
internal val FLAG_CUSTOM_TERMINAL_THEME = FlagFile("custom_terminal_theme", default = false)
