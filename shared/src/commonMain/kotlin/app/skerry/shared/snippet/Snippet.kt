package app.skerry.shared.snippet

import kotlinx.serialization.Serializable

/**
 * A saved snippet: a named command/script for repeated execution in the terminal. A standalone
 * object rather than part of an open session; identity is the stable [id] (assigned at creation,
 * unchanged by edits). [label] is the display name, [command] is the text inserted into the
 * active terminal and executed (with a newline). [tags] are user labels for grouping/search
 * (#monitoring, #disk).
 *
 * [notes] is an optional free-form remark about the snippet (what it does to the box, which
 * parameters it expects, when not to run it), stored the way every other note is
 * ([app.skerry.shared.text.normalizeNotes]): trimmed, capped, blank collapsed to `null`. It is
 * never sent to the shell — only the [command] is.
 *
 * [shortcut] is the global launch hotkey in canonical form (`Ctrl+Shift+D`), `null` for none.
 * Defaulted fields, [notes] included; older `snippets.json` without them reads as-is
 * (backward-compat). The launch target (active terminal or a specific host) isn't stored on the
 * snippet — it's chosen at launch time: the terminal palette targets the active session, while
 * "Run snippet…" in a host's context menu runs it on that host.
 */
@Serializable
data class Snippet(
    val id: String,
    val label: String,
    val command: String,
    val tags: List<String> = emptyList(),
    val shortcut: String? = null,
    val notes: String? = null,
)
