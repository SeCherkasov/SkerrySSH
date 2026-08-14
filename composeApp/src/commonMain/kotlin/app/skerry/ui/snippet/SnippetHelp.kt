package app.skerry.ui.snippet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.HelpCodeRow
import app.skerry.ui.design.HelpDialog
import app.skerry.ui.design.HelpExampleRow
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.help_add
import app.skerry.ui.generated.resources.help_added
import app.skerry.ui.generated.resources.help_close
import app.skerry.ui.generated.resources.lib_snippets_help_examples
import app.skerry.ui.generated.resources.lib_snippets_help_intro
import app.skerry.ui.generated.resources.lib_snippets_help_title
import app.skerry.ui.generated.resources.lib_snippets_help_var_choices
import app.skerry.ui.generated.resources.lib_snippets_help_var_clipboard
import app.skerry.ui.generated.resources.lib_snippets_help_var_date
import app.skerry.ui.generated.resources.lib_snippets_help_var_param
import app.skerry.ui.generated.resources.lib_snippets_help_var_random
import app.skerry.ui.generated.resources.lib_snippets_help_var_uuid
import app.skerry.ui.generated.resources.lib_snippets_help_var_vault
import app.skerry.ui.generated.resources.lib_snippets_help_vars
import app.skerry.ui.theme.Skerry
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource

/**
 * Example snippets offered from the help dialog, one-click each. Labels and commands are user data
 * (they land in the vault and sync), so they stay in English like [STARTER_SNIPPETS]; only the
 * dialog chrome is localized. Deliberately overlapping the starter pack in spirit but not in
 * content: these show the variable syntax in use, which the pack's plain commands don't.
 */
val SNIPPET_HELP_EXAMPLES: List<SnippetDraft> = listOf(
    SnippetDraft(label = "Load check", command = "uptime && free -h && df -h", tags = listOf("monitoring")),
    SnippetDraft(
        label = "Package install & verify",
        command = "sudo apt-get install -y \${{package}} && dpkg -s \${{package}}",
        tags = listOf("ops"),
    ),
    SnippetDraft(label = "Archive logs by date", command = "tar -czf logs_\${{date}}.tar.gz /var/log", tags = listOf("ops")),
    SnippetDraft(label = "Random hex token", command = "echo \${{random:16,hex}}", tags = listOf("ops")),
    SnippetDraft(
        label = "Restart chosen service",
        command = "sudo systemctl restart \${{service:nginx|postgresql|docker}}",
        tags = listOf("monitoring"),
    ),
)

/**
 * The `${{…}}` variable reference, one row per kind — shared by the snippet and runbook help
 * dialogs, because runbook steps take exactly the same placeholders and the reference must not
 * drift between the two.
 */
@Composable
fun TemplateVariableHelpRows() {
    FieldLabel(stringResource(Res.string.lib_snippets_help_vars))
    HelpCodeRow("\${{date}} · \${{time}} · \${{timestamp}}", stringResource(Res.string.lib_snippets_help_var_date))
    HelpCodeRow("\${{uuid}}", stringResource(Res.string.lib_snippets_help_var_uuid))
    HelpCodeRow("\${{random:16,hex}}", stringResource(Res.string.lib_snippets_help_var_random))
    HelpCodeRow("\${{clipboard}}", stringResource(Res.string.lib_snippets_help_var_clipboard))
    HelpCodeRow("\${{vault:prod-db}}", stringResource(Res.string.lib_snippets_help_var_vault))
    HelpCodeRow("\${{host:web-1}}", stringResource(Res.string.lib_snippets_help_var_param))
    HelpCodeRow("\${{env:dev|staging|prod}}", stringResource(Res.string.lib_snippets_help_var_choices))
}

/** Help for the snippets library: the `${{…}}` syntax and a few one-click examples. */
@Composable
fun SnippetHelpDialog(manager: SnippetManager, onDismiss: () -> Unit) {
    val added = remember { mutableStateOf(setOf<String>()) }
    HelpDialog(
        title = stringResource(Res.string.lib_snippets_help_title),
        closeLabel = stringResource(Res.string.help_close),
        onDismiss = onDismiss,
    ) {
        Txt(
            stringResource(Res.string.lib_snippets_help_intro),
            color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp,
        )
        TemplateVariableHelpRows()
        FieldLabel(stringResource(Res.string.lib_snippets_help_examples))
        SNIPPET_HELP_EXAMPLES.forEach { draft ->
            HelpExampleRow(
                label = draft.label,
                detail = draft.command,
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
