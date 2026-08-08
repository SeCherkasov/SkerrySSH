package app.skerry.ui.runbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.runbook.RunbookTransferDirection
import app.skerry.ui.design.Chip
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.fieldFocus
import app.skerry.ui.design.rememberFieldDraft
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.design.labelUppercase
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_direction_download
import app.skerry.ui.generated.resources.runbook_direction_upload
import app.skerry.ui.generated.resources.runbook_field_description
import app.skerry.ui.generated.resources.runbook_field_name
import app.skerry.ui.generated.resources.runbook_field_tags
import app.skerry.ui.generated.resources.runbook_kind_command
import app.skerry.ui.generated.resources.runbook_kind_transfer
import app.skerry.ui.generated.resources.runbook_ph_description
import app.skerry.ui.generated.resources.runbook_ph_local
import app.skerry.ui.generated.resources.runbook_ph_name
import app.skerry.ui.generated.resources.runbook_ph_remote
import app.skerry.ui.generated.resources.runbook_ph_tags
import app.skerry.ui.generated.resources.runbook_policy
import app.skerry.ui.generated.resources.runbook_policy_stop
import app.skerry.ui.generated.resources.runbook_policy_watchdog
import app.skerry.ui.generated.resources.runbook_policy_watchdog_off
import app.skerry.ui.generated.resources.runbook_policy_watchdog_value
import app.skerry.ui.generated.resources.runbook_step_add
import app.skerry.ui.generated.resources.runbook_step_command
import app.skerry.ui.generated.resources.runbook_step_confirm
import app.skerry.ui.generated.resources.runbook_step_continue_on_error
import app.skerry.ui.generated.resources.runbook_step_down
import app.skerry.ui.generated.resources.runbook_step_local
import app.skerry.ui.generated.resources.runbook_step_n
import app.skerry.ui.generated.resources.runbook_step_remote
import app.skerry.ui.generated.resources.runbook_step_remove
import app.skerry.ui.generated.resources.runbook_step_title
import app.skerry.ui.generated.resources.runbook_step_up
import app.skerry.ui.generated.resources.runbook_steps
import app.skerry.ui.generated.resources.runbook_transfer_note
import app.skerry.ui.generated.resources.runbook_vars_hint
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Watchdog values the editor offers, in minutes; `0` turns the warning off. */
private val WATCHDOG_CHOICES = listOf(0, 2, 5, 15)

/**
 * The runbook form itself — name, description, tags, the step list and the run policy — over
 * [RunbookFormState]. Shared by the desktop panel ([RunbookEditorPanel]) and the mobile sheet: same
 * fields, same validation, only the surrounding chrome differs, which is what [horizontalPadding]
 * is for (the panel pads itself, the sheet doesn't).
 */
@Composable
fun RunbookEditorFields(form: RunbookFormState, mono: FontFamily, horizontalPadding: Dp = 24.dp) {
    Column(Modifier.padding(horizontal = horizontalPadding, vertical = 20.dp)) {
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
            RunbookLineField(form.tagDraft, form::updateTagDraft, stringResource(Res.string.runbook_ph_tags), mono)
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
                stringResource(Res.string.runbook_step_add), icon = "add", onClick = { form.addStep() },
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Column(Modifier.padding(top = 24.dp)) {
            RunbookFieldLabel(stringResource(Res.string.runbook_policy))
            PolicyFields(form)
        }
    }
}

/** Run policy: what stops the run, how long a silent step is given, how hosts are spread. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PolicyFields(form: RunbookFormState) {
    StepFlagRow(stringResource(Res.string.runbook_policy_stop), form.stopOnFirstFailure) {
        form.stopOnFirstFailure = !form.stopOnFirstFailure
    }
    Txt(
        stringResource(Res.string.runbook_policy_watchdog), color = Skerry.colors.faint, size = 11.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        WATCHDOG_CHOICES.forEach { minutes ->
            key(minutes) {
                Chip(
                    if (minutes == 0) stringResource(Res.string.runbook_policy_watchdog_off)
                    else stringResource(Res.string.runbook_policy_watchdog_value, minutes),
                    active = form.watchdogMinutes == minutes,
                    onClick = { form.watchdogMinutes = minutes },
                )
            }
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
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Chip(
                stringResource(Res.string.runbook_kind_command),
                active = step.kind == RunbookStepKind.COMMAND,
                onClick = { step.kind = RunbookStepKind.COMMAND },
            )
            Chip(
                stringResource(Res.string.runbook_kind_transfer),
                active = step.kind == RunbookStepKind.TRANSFER,
                onClick = { step.kind = RunbookStepKind.TRANSFER },
            )
        }
        Box(Modifier.padding(top = 8.dp)) {
            RunbookLineField(step.title, { step.title = it }, stringResource(Res.string.runbook_step_title), LocalFonts.current.ui)
        }
        when (step.kind) {
            RunbookStepKind.COMMAND -> Column(Modifier.padding(top = 8.dp)) {
                RunbookCommandField(step.command, { step.command = it }, stringResource(Res.string.runbook_step_command), mono)
                Txt(
                    stringResource(Res.string.runbook_vars_hint), color = Skerry.colors.faint, size = 11.sp,
                    lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
            RunbookStepKind.TRANSFER -> TransferFields(step, mono)
        }
        StepFlagRow(stringResource(Res.string.runbook_step_confirm), step.confirm) { step.confirm = !step.confirm }
        StepFlagRow(stringResource(Res.string.runbook_step_continue_on_error), step.continueOnError) {
            step.continueOnError = !step.continueOnError
        }
    }
}

/** The two ends of a transfer step plus the direction the file travels. */
@Composable
private fun TransferFields(step: RunbookStepDraft, mono: FontFamily) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        RunbookTransferDirection.entries.forEach { direction ->
            key(direction) {
                Chip(
                    when (direction) {
                        RunbookTransferDirection.UPLOAD -> stringResource(Res.string.runbook_direction_upload)
                        RunbookTransferDirection.DOWNLOAD -> stringResource(Res.string.runbook_direction_download)
                    },
                    active = step.direction == direction,
                    onClick = { step.direction = direction },
                )
            }
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        RunbookFieldLabel(stringResource(Res.string.runbook_step_local))
        RunbookLineField(step.localPath, { step.localPath = it }, stringResource(Res.string.runbook_ph_local), mono)
    }
    Column(Modifier.padding(top = 10.dp)) {
        RunbookFieldLabel(stringResource(Res.string.runbook_step_remote))
        RunbookLineField(step.remotePath, { step.remotePath = it }, stringResource(Res.string.runbook_ph_remote), mono)
    }
    Txt(
        stringResource(Res.string.runbook_transfer_note), color = Skerry.colors.faint, size = 11.sp,
        lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp),
    )
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
    val draft = rememberFieldDraft(value, singleLine = singleLine)
    BasicTextField(
        value = draft.textFieldValue(value),
        onValueChange = { draft.accept(it, value, onValueChange) },
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = Modifier.fillMaxWidth().fieldFocus(draft),
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
    val draft = rememberFieldDraft(value, singleLine = false)
    BasicTextField(
        value = draft.textFieldValue(value),
        onValueChange = { draft.accept(it, value, onValueChange) },
        textStyle = style,
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = Modifier.fillMaxWidth().fieldFocus(draft),
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
