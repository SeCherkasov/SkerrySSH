package app.skerry.shared.snippet

import kotlinx.serialization.Serializable

/**
 * A saved snippet: a named command/script for repeated execution in the terminal. A standalone
 * object rather than part of an open session; identity is the stable [id] (assigned at creation,
 * unchanged by edits). [label] is the display name, [command] is the text inserted into the
 * active terminal and executed (with a newline). [tags] are user labels for grouping/search
 * (#monitoring, #disk).
 *
 * [shortcut] is the global launch hotkey in canonical form (`Ctrl+Shift+D`), `null` for none.
 * Defaulted field; older `snippets.json` without it reads as-is (backward-compat). The launch
 * target (active terminal or a specific host) isn't stored on the snippet — it's chosen at launch
 * time: the terminal palette targets the active session, while "Run snippet…" in a host's context
 * menu runs it on that host.
 */
@Serializable
data class Snippet(
    val id: String,
    val label: String,
    val command: String,
    val tags: List<String> = emptyList(),
    val shortcut: String? = null,
)
