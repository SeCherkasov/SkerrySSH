package app.skerry.ui.snippet

import androidx.compose.runtime.Immutable
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.SnippetVariableKind

/**
 * One row of the panel's VARIABLES block. [editable] parameters get an input field; vault and
 * clipboard references are stated, not edited — they resolve at run time, inside the confirmation
 * dialog, which is the only place allowed to read them.
 */
@Immutable
data class SnippetPanelVariable(
    val kind: SnippetVariableKind,
    val name: String,
    val editable: Boolean,
)

/**
 * Variables worth showing next to a snippet: prompted parameters first (in first appearance order,
 * each once however often it is used), then the vault entries and the clipboard it pulls from.
 * Machine kinds (date/uuid/random) are left out — they need no input and are already visible in the
 * preview line.
 */
fun snippetPanelVariables(segments: List<SnippetSegment>): List<SnippetPanelVariable> {
    val variables = segments.filterIsInstance<SnippetSegment.Variable>()
    val params = variables.filter { it.kind == SnippetVariableKind.PARAM }
        .map { it.name }
        .distinct()
        .map { SnippetPanelVariable(SnippetVariableKind.PARAM, it, editable = true) }
    val vault = variables.filter { it.kind == SnippetVariableKind.VAULT }
        .map { it.format.orEmpty() }
        .distinct()
        .map { SnippetPanelVariable(SnippetVariableKind.VAULT, it, editable = false) }
    val clipboard = if (variables.any { it.kind == SnippetVariableKind.CLIPBOARD }) {
        listOf(SnippetPanelVariable(SnippetVariableKind.CLIPBOARD, "clipboard", editable = false))
    } else {
        emptyList()
    }
    return params + vault + clipboard
}

/**
 * The line the panel shows under "what runs": machine values resolved as they would be, [params]
 * spliced in, and everything still unresolved left as its own placeholder.
 *
 * Deliberately not the line that gets sent. Vault secrets stay masked and the clipboard is not read
 * — a snippet may have arrived from a team vault, and reading either just because the panel is on
 * screen would resolve someone else's template against this user's secrets. Both are collected in
 * the confirmation dialog, which captures them once and previews exactly what it sends
 * (coding-guidelines §3).
 */
fun snippetPanelPreview(
    segments: List<SnippetSegment>,
    machineValues: List<String?>,
    params: Map<String, String>,
    clipboardLabel: String,
): String = SnippetTemplate.assemble(segments, machineValues) { variable ->
    when (variable.kind) {
        SnippetVariableKind.PARAM -> params[variable.name]?.takeIf { it.isNotBlank() } ?: variable.raw
        SnippetVariableKind.VAULT -> SECRET_MASK
        SnippetVariableKind.CLIPBOARD -> clipboardLabel
        else -> variable.raw
    }
}
