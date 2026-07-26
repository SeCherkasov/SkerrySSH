package app.skerry.shared.runbook

import kotlinx.serialization.Serializable

/**
 * A saved runbook: an ordered checklist of commands run in one terminal session, one step at a
 * time. Where a snippet is a single line the user fires and forgets, a runbook is the procedure
 * around it — "drain the node, restart the service, check it came back" — and the human stays in
 * the loop: a step marked [RunbookStep.confirm] waits for an explicit go-ahead, and a step that
 * exits non-zero stops the run instead of carrying on into the next command.
 *
 * Identity is the stable [id] (assigned at creation, unchanged by edits); [label] is the display
 * name, [description] an optional note shown while the run is in progress (what this procedure is
 * for, when to abort). [tags] group runbooks in the library exactly like snippet tags.
 *
 * Steps carry the same `${{…}}` variables as snippets ([app.skerry.shared.snippet.SnippetTemplate]);
 * they are resolved once for the whole run, so a placeholder used in two steps means the same value
 * in both (see [RunbookScript]).
 */
@Serializable
data class Runbook(
    val id: String,
    val label: String,
    val description: String = "",
    val steps: List<RunbookStep> = emptyList(),
    val tags: List<String> = emptyList(),
)

/**
 * One step of a [Runbook]. [title] names it in the progress list ("Drain the node"); [command] is
 * what reaches the shell.
 *
 * [confirm] pauses the run before this step and waits for the user — the default, because a runbook
 * that runs end to end unattended is just a shell script. Clearing it is for the harmless
 * checks (`uptime`, `systemctl status`) that would otherwise make the user click through noise.
 *
 * [continueOnError] keeps the run going when the step exits non-zero. Off by default: the whole
 * point of reading the exit code is to stop before the next command makes things worse. On for
 * steps whose failure is expected and informational (a `grep` that finds nothing, a cleanup of
 * something that may not exist).
 */
@Serializable
data class RunbookStep(
    val id: String,
    val title: String = "",
    val command: String,
    val confirm: Boolean = true,
    val continueOnError: Boolean = false,
)
