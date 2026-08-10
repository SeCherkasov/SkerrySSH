package app.skerry.ui.runbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_cancel
import app.skerry.ui.generated.resources.runbook_new
import app.skerry.ui.generated.resources.runbook_save
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import app.skerry.ui.app.UiTags

/**
 * Runbook form in the section's right-hand panel: reached from "New runbook" and from Edit on the
 * run card, the same division of labour as snippets — running is the card's job, editing is this
 * one's, and the two never share a screen.
 */
@Composable
internal fun RunbookEditorPanel(
    entry: RunbookEntry?,
    manager: RunbookManager,
    mono: FontFamily,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // Shared form state (desktop and mobile); the editor is recreated externally via key().
    val form = remember { RunbookFormState.fromEntry(entry) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sym("checklist", size = 20.sp, color = Skerry.colors.cyanBright)
            Txt(
                form.label.ifBlank { stringResource(Res.string.runbook_new) },
                color = Skerry.colors.text, size = 17.sp, weight = FontWeight.SemiBold,
            )
        }
        RunbookEditorFields(form, mono, horizontalPadding = 0.dp)
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                stringResource(Res.string.runbook_save),
                onClick = { if (form.canSave) onSaved(manager.save(form.toDraft())) },
                enabled = form.canSave,
                modifier = Modifier.testTag(UiTags.FORM_SAVE),
            )
            CancelButton(stringResource(Res.string.runbook_cancel), onClick = onCancel, modifier = Modifier.testTag(UiTags.FORM_CANCEL))
        }
    }
}
