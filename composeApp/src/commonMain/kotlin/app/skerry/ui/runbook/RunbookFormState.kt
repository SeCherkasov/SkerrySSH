package app.skerry.ui.runbook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.runbook.RunbookParallelism
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.RunbookTransferDirection
import app.skerry.ui.snippet.parseSnippetTags

/** What a step row is being edited as; the saved shape follows from it ([RunbookStepDraft.toStep]). */
enum class RunbookStepKind { COMMAND, TRANSFER }

/**
 * One editable step row. Its own object (rather than an index into a list of data classes) so the
 * editor can key rows by identity: a reorder must move the row, not retype every field below it.
 * [stepId] is the saved [RunbookStep.id], empty for a row added in the form — [RunbookManager.save]
 * assigns identity, the same division of labour as [RunbookDraft.id].
 *
 * Both kinds' fields live side by side: switching a row from a command to a transfer and back must
 * not throw away what was typed before the switch.
 */
@Stable
class RunbookStepDraft internal constructor(
    internal val stepId: String,
    kind: RunbookStepKind = RunbookStepKind.COMMAND,
    title: String = "",
    command: String = "",
    localPath: String = "",
    remotePath: String = "",
    direction: RunbookTransferDirection = RunbookTransferDirection.UPLOAD,
    confirm: Boolean = true,
    continueOnError: Boolean = false,
) {
    var kind: RunbookStepKind by mutableStateOf(kind)
    var title: String by mutableStateOf(title)
    var command: String by mutableStateOf(command)
    var localPath: String by mutableStateOf(localPath)
    var remotePath: String by mutableStateOf(remotePath)
    var direction: RunbookTransferDirection by mutableStateOf(direction)
    var confirm: Boolean by mutableStateOf(confirm)
    var continueOnError: Boolean by mutableStateOf(continueOnError)

    /** Whether the row says what to do: a command line, or both ends of a transfer. */
    internal val filled: Boolean
        get() = when (kind) {
            RunbookStepKind.COMMAND -> command.isNotBlank()
            RunbookStepKind.TRANSFER -> localPath.isNotBlank() && remotePath.isNotBlank()
        }

    internal fun toStep(): RunbookStep = when (kind) {
        RunbookStepKind.COMMAND -> RunbookStep.Command(
            id = stepId,
            title = title.trim(),
            command = command,
            confirm = confirm,
            continueOnError = continueOnError,
        )
        RunbookStepKind.TRANSFER -> RunbookStep.Transfer(
            id = stepId,
            title = title.trim(),
            localPath = localPath.trim(),
            remotePath = remotePath.trim(),
            direction = direction,
            confirm = confirm,
            continueOnError = continueOnError,
        )
    }
}

/**
 * Runbook create/edit form state — editable fields as Compose state, shared by the desktop editor
 * ([RunbooksView]) and the mobile sheet, exactly like
 * [app.skerry.ui.snippet.SnippetFormState]: one source of truth for seeding, validation and draft
 * assembly, with only the layout differing between platforms.
 */
@Stable
class RunbookFormState private constructor(private val editingId: String?) {
    var label: String by mutableStateOf("")
    var description: String by mutableStateOf("")

    /** Committed tags (pills); edited via [addTags]/[removeTag]. */
    var tags: List<String> by mutableStateOf(emptyList())
        private set

    /** Uncommitted tag input (no pill yet); [toDraft] commits it so it isn't lost. */
    var tagDraft: String by mutableStateOf("")

    var steps: List<RunbookStepDraft> by mutableStateOf(listOf(RunbookStepDraft(stepId = "")))
        private set

    /** Run policy — the chips above the step list ([RunbookPolicy]). */
    var stopOnFirstFailure: Boolean by mutableStateOf(RunbookPolicy().stopOnFirstFailure)
    var watchdogMinutes: Int by mutableStateOf(RunbookPolicy().watchdogMinutes)
    var parallelism: RunbookParallelism by mutableStateOf(RunbookPolicy().parallelism)

    /** A runbook needs a name and something to run; empty rows are dropped on save. */
    val canSave: Boolean get() = label.isNotBlank() && steps.any { it.filled }

    fun addStep(kind: RunbookStepKind = RunbookStepKind.COMMAND) {
        steps = steps + RunbookStepDraft(stepId = "", kind = kind)
    }

    /** Removes [step]; the last remaining row is replaced by a fresh empty one, never by nothing. */
    fun removeStep(step: RunbookStepDraft) {
        val rest = steps.filterNot { it === step }
        steps = rest.ifEmpty { listOf(RunbookStepDraft(stepId = "")) }
    }

    /** Moves the row at [from] to [to]; out-of-range indices are a no-op (the ends of the list). */
    fun moveStep(from: Int, to: Int) {
        if (from !in steps.indices || to !in steps.indices || from == to) return
        steps = steps.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Commit tag(s) from [raw] ([parseSnippetTags], duplicates dropped) and clear the draft. */
    fun addTags(raw: String) {
        tags = (tags + parseSnippetTags(raw)).distinct()
        tagDraft = ""
    }

    /** Update the tag draft; a comma commits tag(s) immediately (a single tag on Enter, [addTags]). */
    fun updateTagDraft(value: String) {
        if (value.contains(',')) addTags(value) else tagDraft = value
    }

    fun removeTag(tag: String) {
        tags = tags - tag
    }

    /**
     * Draft for [RunbookManager.save]. Flushes an uncommitted [tagDraft] (typed but no Enter/comma
     * before Save), otherwise the tag would be lost.
     */
    fun toDraft(): RunbookDraft = RunbookDraft(
        id = editingId,
        label = label.trim(),
        description = description.trim(),
        steps = steps.map { it.toStep() },
        tags = (tags + parseSnippetTags(tagDraft)).distinct(),
        policy = RunbookPolicy(
            stopOnFirstFailure = stopOnFirstFailure,
            watchdogMinutes = watchdogMinutes,
            parallelism = parallelism,
        ),
    )

    companion object {
        /** Form prefilled from [entry] (edit), or a single empty step (create, `entry == null`). */
        fun fromEntry(entry: RunbookEntry?): RunbookFormState =
            RunbookFormState(entry?.id).apply {
                val runbook = entry?.runbook ?: return@apply
                label = runbook.label
                description = runbook.description
                tags = runbook.tags
                stopOnFirstFailure = runbook.policy.stopOnFirstFailure
                watchdogMinutes = runbook.policy.watchdogMinutes
                parallelism = runbook.policy.parallelism
                if (runbook.steps.isNotEmpty()) steps = runbook.steps.map { it.toDraft() }
            }
    }
}

/** The editable row a saved step opens as. */
private fun RunbookStep.toDraft(): RunbookStepDraft = when (this) {
    is RunbookStep.Command -> RunbookStepDraft(
        stepId = id,
        kind = RunbookStepKind.COMMAND,
        title = title,
        command = command,
        confirm = confirm,
        continueOnError = continueOnError,
    )
    is RunbookStep.Transfer -> RunbookStepDraft(
        stepId = id,
        kind = RunbookStepKind.TRANSFER,
        title = title,
        localPath = localPath,
        remotePath = remotePath,
        direction = direction,
        confirm = confirm,
        continueOnError = continueOnError,
    )
}
