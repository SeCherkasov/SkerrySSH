package app.skerry.ui.runbook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import app.skerry.shared.runbook.RunbookStep
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.HelpCodeRow
import app.skerry.ui.design.HelpDialog
import app.skerry.ui.design.HelpExampleRow
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.help_add
import app.skerry.ui.generated.resources.help_added
import app.skerry.ui.generated.resources.help_close
import app.skerry.ui.generated.resources.runbook_help_examples
import app.skerry.ui.generated.resources.runbook_help_flag_confirm
import app.skerry.ui.generated.resources.runbook_help_flag_continue
import app.skerry.ui.generated.resources.runbook_help_flag_interactive
import app.skerry.ui.generated.resources.runbook_help_flags
import app.skerry.ui.generated.resources.runbook_help_intro
import app.skerry.ui.generated.resources.runbook_help_title
import app.skerry.ui.generated.resources.runbook_step_confirm
import app.skerry.ui.generated.resources.runbook_step_continue_on_error
import app.skerry.ui.generated.resources.runbook_step_interactive
import app.skerry.ui.snippet.TemplateVariableHelpRows
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Example runbooks offered from the help dialog, one-click each. Labels, step titles and commands
 * are user data (they land in the vault and sync), so they stay in English like
 * [app.skerry.ui.snippet.STARTER_SNIPPETS]; only the dialog chrome is localized. Step ids are
 * empty — [RunbookManager.save] assigns them, the same contract the editor uses.
 */
val RUNBOOK_HELP_EXAMPLES: List<RunbookDraft> = listOf(
    RunbookDraft(
        label = "Package install & verify",
        description = "Install a package, then check it actually landed.",
        steps = listOf(
            RunbookStep.Command(id = "", title = "Update package index", command = "sudo apt-get update"),
            RunbookStep.Command(id = "", title = "Install", command = "sudo apt-get install -y \${{package}}"),
            RunbookStep.Command(id = "", title = "Verify", command = "dpkg -s \${{package}}", confirm = false),
        ),
        tags = listOf("ops"),
    ),
    RunbookDraft(
        label = "Disk space check",
        description = "Where the space went.",
        steps = listOf(
            RunbookStep.Command(id = "", title = "Filesystems", command = "df -h", confirm = false),
            RunbookStep.Command(
                id = "", title = "Largest under /var",
                command = "sudo du -sh /var/* | sort -rh | head -10",
                confirm = false, continueOnError = true,
            ),
        ),
        tags = listOf("disk"),
    ),
    RunbookDraft(
        label = "Service restart & health",
        description = "Restart a service, then read its status and recent log.",
        steps = listOf(
            RunbookStep.Command(id = "", title = "Restart", command = "sudo systemctl restart \${{service}}"),
            RunbookStep.Command(
                id = "", title = "Status", command = "systemctl status \${{service}} --no-pager",
                confirm = false, continueOnError = true,
            ),
            RunbookStep.Command(
                id = "", title = "Recent log", command = "journalctl -u \${{service}} -n 50 --no-pager",
                confirm = false,
            ),
        ),
        tags = listOf("monitoring"),
    ),
    RunbookDraft(
        label = "Interactive triage",
        description = "Look around in htop, continue when done.",
        steps = listOf(
            RunbookStep.Command(id = "", title = "Look around", command = "htop", confirm = false, interactive = true),
            RunbookStep.Command(id = "", title = "Load snapshot", command = "uptime", confirm = false),
        ),
        tags = listOf("monitoring"),
    ),
)

/** The step list as the example row prints it: `n steps · first command…`. */
private fun RunbookDraft.summary(): String {
    val first = steps.filterIsInstance<RunbookStep.Command>().firstOrNull()?.command.orEmpty()
    return "${steps.size} · $first …"
}

/** Help for the runbooks library: what a run does, the step flags, and one-click examples. */
@Composable
fun RunbookHelpDialog(manager: RunbookManager, onDismiss: () -> Unit) {
    val added = remember { mutableStateOf(setOf<String>()) }
    HelpDialog(
        title = stringResource(Res.string.runbook_help_title),
        closeLabel = stringResource(Res.string.help_close),
        onDismiss = onDismiss,
    ) {
        Txt(
            stringResource(Res.string.runbook_help_intro),
            color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp,
        )
        FieldLabel(stringResource(Res.string.runbook_help_flags))
        HelpCodeRow(stringResource(Res.string.runbook_step_confirm), stringResource(Res.string.runbook_help_flag_confirm))
        HelpCodeRow(
            stringResource(Res.string.runbook_step_continue_on_error),
            stringResource(Res.string.runbook_help_flag_continue),
        )
        HelpCodeRow(
            stringResource(Res.string.runbook_step_interactive),
            stringResource(Res.string.runbook_help_flag_interactive),
        )
        // Steps take the same ${{…}} placeholders a snippet does — the same reference, so the
        // syntax is documented once and cannot drift between the two dialogs.
        TemplateVariableHelpRows()
        FieldLabel(stringResource(Res.string.runbook_help_examples))
        RUNBOOK_HELP_EXAMPLES.forEach { draft ->
            HelpExampleRow(
                label = draft.label,
                detail = draft.summary(),
                addLabel = stringResource(Res.string.help_add),
                addedLabel = stringResource(Res.string.help_added),
                added = draft.label in added.value,
                onAdd = {
                    manager.save(draft)
                    added.value += draft.label
                },
            )
        }
    }
}
