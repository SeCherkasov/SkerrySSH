package app.skerry.ui.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.FieldLabel
import app.skerry.ui.design.HelpCodeRow
import app.skerry.ui.design.HelpDialog
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.help_close
import app.skerry.ui.generated.resources.vault_help_categories
import app.skerry.ui.generated.resources.vault_help_certificates
import app.skerry.ui.generated.resources.vault_help_intro
import app.skerry.ui.generated.resources.vault_help_keys
import app.skerry.ui.generated.resources.vault_help_passwords
import app.skerry.ui.generated.resources.vault_help_snippets
import app.skerry.ui.generated.resources.vault_help_snippets_hint
import app.skerry.ui.generated.resources.vault_help_title
import app.skerry.ui.generated.resources.vtail_category_certificates
import app.skerry.ui.generated.resources.vtail_category_passwords
import app.skerry.ui.generated.resources.vtail_category_ssh_keys
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Help for the vault: what each category holds and how a snippet references a stored password.
 * Guidance only — a secret is generated or imported, never templated, so unlike the snippet and
 * runbook dialogs there is nothing to offer one-click.
 */
@Composable
fun VaultHelpDialog(onDismiss: () -> Unit) {
    HelpDialog(
        title = stringResource(Res.string.vault_help_title),
        closeLabel = stringResource(Res.string.help_close),
        onDismiss = onDismiss,
    ) {
        Txt(
            stringResource(Res.string.vault_help_intro),
            color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp,
        )
        FieldLabel(stringResource(Res.string.vault_help_categories))
        HelpCodeRow(stringResource(Res.string.vtail_category_ssh_keys), stringResource(Res.string.vault_help_keys))
        HelpCodeRow(stringResource(Res.string.vtail_category_passwords), stringResource(Res.string.vault_help_passwords))
        HelpCodeRow(stringResource(Res.string.vtail_category_certificates), stringResource(Res.string.vault_help_certificates))
        FieldLabel(stringResource(Res.string.vault_help_snippets))
        HelpCodeRow("\${{vault:prod-db}}", stringResource(Res.string.vault_help_snippets_hint))
    }
}
