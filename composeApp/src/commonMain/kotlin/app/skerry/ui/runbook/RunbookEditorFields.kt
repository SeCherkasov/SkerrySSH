package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Chip
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_field_description
import app.skerry.ui.generated.resources.runbook_field_name
import app.skerry.ui.generated.resources.runbook_field_tags
import app.skerry.ui.generated.resources.runbook_ph_description
import app.skerry.ui.generated.resources.runbook_ph_name
import app.skerry.ui.generated.resources.runbook_step_add
import app.skerry.ui.generated.resources.runbook_step_command
import app.skerry.ui.generated.resources.runbook_step_confirm
import app.skerry.ui.generated.resources.runbook_step_continue_on_error
import app.skerry.ui.generated.resources.runbook_step_down
import app.skerry.ui.generated.resources.runbook_step_n
import app.skerry.ui.generated.resources.runbook_step_remove
import app.skerry.ui.generated.resources.runbook_step_title
import app.skerry.ui.generated.resources.runbook_step_up
import app.skerry.ui.generated.resources.runbook_steps
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * The runbook form itself — name, description, tags and the step list — over [RunbookFormState].
 * Shared by the desktop editor ([RunbooksView]) and the mobile sheet: same fields, same validation,
 * only the surrounding chrome differs.
 */
@Composable
fun RunbookEditorFields(form: RunbookFormState, mono: FontFamily) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        RunbookFieldLabel(stringResource(Res.string.runbook_field_name))
        RunbookLineField(form.label, { form.label = it }, stringResource(Res.string.runbook_ph_name), LocalFonts.current.ui)

        Column(Modifier.padding(top = 20.dp)) {
            RunbookFieldLabel(stringResource(Res.string.runbook_field_description))
            RunbookLineField(
                form.description, { form.description = it },
                stringResource(Res.string.runbook_ph_description), LocalFonts.current.ui, singleLine = false,
            )
        }

        Column(Modifier.padding(top = 20.dp)) {
            RunbookFieldLabel(stringResource(Res.string.runbook_field_tags))
            if (form.tags.isNotEmpty()) {
                Row(
                    Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Tapping a pill drops the tag — the same gesture as in the snippet editor.
                    form.tags.forEach { tag -> key(tag) { Chip("#$tag", onClick = { form.removeTag(tag) }) } }
                }
            }
            RunbookLineField(form.tagDraft, form::updateTagDraft, "#ops", mono)
        }

        Column(Modifier.padding(top = 24.dp)) {
            RunbookFieldLabel(stringResource(Res.string.runbook_steps))
            form.steps.forEachIndexed { index, step ->
                key(step) {
                    StepEditor(
                        index = index,
                        step = step,
                        mono = mono,
                        onUp = { form.moveStep(index, index - 1) },
                        onDown = { form.moveStep(index, index + 1) },
                        onRemove = { form.removeStep(step) },
                    )
                }
            }
            GhostButton(
                stringResource(Res.string.runbook_step_add), icon = "add", onClick = form::addStep,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun StepEditor(
    index: Int,
    step: RunbookStepDraft,
    mono: FontFamily,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.line, RoundedCornerShape(9.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(
                stringResource(Res.string.runbook_step_n, index + 1), color = Skerry.colors.faint,
                size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            )
            Box(Modifier.weight(1f))
            IconBtn("arrow_upward", onClick = onUp, box = 24, icon = 15.sp, tooltip = stringResource(Res.string.runbook_step_up))
            IconBtn("arrow_downward", onClick = onDown, box = 24, icon = 15.sp, tooltip = stringResource(Res.string.runbook_step_down))
            IconBtn(
                "delete", onClick = onRemove, box = 24, icon = 15.sp, tint = Skerry.colors.sunset,
                tooltip = stringResource(Res.string.runbook_step_remove),
            )
        }
        Box(Modifier.padding(top = 8.dp)) {
            RunbookLineField(step.title, { step.title = it }, stringResource(Res.string.runbook_step_title), LocalFonts.current.ui)
        }
        Box(Modifier.padding(top = 8.dp)) {
            RunbookCommandField(step.command, { step.command = it }, stringResource(Res.string.runbook_step_command), mono)
        }
        StepFlagRow(stringResource(Res.string.runbook_step_confirm), step.confirm) { step.confirm = !step.confirm }
        StepFlagRow(stringResource(Res.string.runbook_step_continue_on_error), step.continueOnError) {
            step.continueOnError = !step.continueOnError
        }
    }
}

@Composable
private fun StepFlagRow(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Toggle(on, onToggle)
        Txt(label, color = Skerry.colors.text, size = 12.sp)
    }
}

@Composable
private fun RunbookFieldLabel(text: String) {
    Txt(
        labelUppercase(text), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun RunbookLineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    font: FontFamily,
    singleLine: Boolean = true,
) {
    val textColor = Skerry.colors.text
    val style = remember(font, textColor) { TextStyle(color = textColor, fontSize = 13.sp, fontFamily = font) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Skerry.colors.bg)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 13.sp, font = font)
                inner()
            }
        },
    )
}

@Composable
private fun RunbookCommandField(value: String, onValueChange: (String) -> Unit, placeholder: String, mono: FontFamily) {
    val textColor = Skerry.colors.textBright
    val style = remember(mono, textColor) { TextStyle(color = textColor, fontSize = 13.sp, fontFamily = mono) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                    .background(Skerry.colors.terminalBg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 13.sp, font = mono)
                inner()
            }
        },
    )
}
