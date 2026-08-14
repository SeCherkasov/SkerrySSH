package app.skerry.shared.runbook

import kotlinx.serialization.Serializable

/**
 * A saved runbook: an ordered checklist run against one or more hosts, one step at a time. Where a
 * snippet is a single line the user fires and forgets, a runbook is the procedure around it —
 * "drain the node, upload the release, restart the service, check it came back" — and the human
 * stays in the loop: a step marked [RunbookStep.confirm] waits for an explicit go-ahead, and a step
 * that fails stops the run instead of carrying on into the next command.
 *
 * Identity is the stable [id] (assigned at creation, unchanged by edits); [label] is the display
 * name, [description] an optional note shown while the run is in progress (what this procedure is
 * for, when to abort). [tags] group runbooks in the library exactly like snippet tags. [policy] is
 * how the run as a whole behaves — see [RunbookPolicy].
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
    val policy: RunbookPolicy = RunbookPolicy(),
)

/**
 * How a run behaves as a whole, as opposed to what its steps do.
 *
 * [stopOnFirstFailure] ends the run at the first step that fails — on by default, because the point
 * of reading exit codes is to stop before the next command makes things worse. Clearing it lets the
 * run continue past failures; a single step can opt out either way with
 * [RunbookStep.continueOnError].
 *
 * [watchdogMinutes] is how long a step may print nothing before the run flags it as possibly stuck
 * (an unterminated here-doc, a command still waiting on stdin, a shell without `$?`). It never ends
 * a step — `sleep 3600` and a silent migration look identical from outside — it only says so.
 * `0` turns the warning off.
 */
@Serializable
data class RunbookPolicy(
    val stopOnFirstFailure: Boolean = true,
    val watchdogMinutes: Int = 2,
)

/** Which way a [RunbookStep.Transfer] moves its file. */
enum class RunbookTransferDirection { UPLOAD, DOWNLOAD }

/**
 * One step of a [Runbook] — either a command typed into the session's shell
 * ([RunbookStep.Command]) or a file moved over the session's SFTP channel
 * ([RunbookStep.Transfer]).
 *
 * [title] names the step in the progress list ("Drain the node"); with none, the step's own command
 * or paths stand in for it.
 *
 * [confirm] pauses the run before this step and waits for the user — the default, because a runbook
 * that runs end to end unattended is just a shell script. Clearing it is for the harmless checks
 * (`uptime`, `systemctl status`) that would otherwise make the user click through noise.
 *
 * [continueOnError] keeps the run going when this particular step fails, whatever the runbook's
 * [RunbookPolicy.stopOnFirstFailure] says — for steps whose failure is expected and informational
 * (a `grep` that finds nothing, a cleanup of something that may not exist).
 *
 * Stored with a `kind` discriminator, added when transfer steps arrived; a step written before that
 * has none and reads as a [Command] (see [RunbookStepSerializer]).
 */
@Serializable(with = RunbookStepSerializer::class)
sealed interface RunbookStep {
    val id: String
    val title: String
    val confirm: Boolean
    val continueOnError: Boolean

    /**
     * A command line sent to the session's shell; its exit code decides how the run continues.
     *
     * [interactive] sends the line as-is, with no exit-code probe around it, and the run waits for
     * the user to say the step is done — for programs that hold the terminal (a TUI, a menu waiting
     * on a digit), whose probe would otherwise never run and leave the step stuck. The exit code is
     * unknowable then; the user's go-ahead is the step's verdict.
     */
    @Serializable
    data class Command(
        override val id: String,
        override val title: String = "",
        val command: String,
        override val confirm: Boolean = true,
        override val continueOnError: Boolean = false,
        val interactive: Boolean = false,
    ) : RunbookStep

    /**
     * A file moved between this machine and the host over SFTP, on the same connection the commands
     * run on. [localPath] is on the machine running Skerry, [remotePath] on the host, whichever way
     * [direction] points; both take `${{…}}` variables like a command does.
     */
    @Serializable
    data class Transfer(
        override val id: String,
        override val title: String = "",
        val localPath: String,
        val remotePath: String,
        val direction: RunbookTransferDirection = RunbookTransferDirection.UPLOAD,
        override val confirm: Boolean = true,
        override val continueOnError: Boolean = false,
    ) : RunbookStep
}

/**
 * Whether the step says what to do at all. An editor row starts empty and is saved only once it
 * does — a command line, or both ends of a transfer.
 */
val RunbookStep.isRunnable: Boolean
    get() = when (this) {
        is RunbookStep.Command -> command.isNotBlank()
        is RunbookStep.Transfer -> localPath.isNotBlank() && remotePath.isNotBlank()
    }

/** The same step under a new [id] — how a row added in the editor is given its identity on save. */
fun RunbookStep.withId(id: String): RunbookStep = when (this) {
    is RunbookStep.Command -> copy(id = id)
    is RunbookStep.Transfer -> copy(id = id)
}
