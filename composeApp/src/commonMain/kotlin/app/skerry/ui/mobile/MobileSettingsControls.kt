package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_default_value
import app.skerry.ui.i18n.label
import app.skerry.ui.vault.AutoLockDuration
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.HLine
import app.skerry.ui.design.NumberStepper
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.settings.autoLockLabel
import app.skerry.ui.theme.Skerry
import androidx.compose.runtime.CompositionLocalProvider
import app.skerry.ui.design.LocalFieldLabel

/**
 * Hub section row: leading icon + name + subtitle on the right + chevron. [onClick] == null
 * makes the row inert (no action). [divider] is the bottom line (absent on the last row).
 */
@Composable
internal fun MoreRow(
    icon: String,
    iconColor: Color,
    label: String,
    subtitle: String?,
    subtitleColor: Color,
    labelColor: Color = Skerry.colors.text,
    divider: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) {
        base.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        base
    }
    Row(
        clickable.padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Sym(icon, size = 21.sp, color = iconColor)
        Txt(label, color = labelColor, size = 14.5.sp, modifier = Modifier.weight(1f))
        if (!subtitle.isNullOrEmpty()) Txt(subtitle, color = subtitleColor, size = 11.sp)
        if (onClick != null && labelColor != Skerry.colors.sunset) Sym("chevron_right", size = 20.sp, color = Skerry.colors.faint)
    }
    if (divider) HLine(modifier = Modifier.padding(horizontal = 12.dp))
}

// Security (More -> Security push screen).

/** Auto-lock threshold dropdown (mobile) — reuses the Appearance trigger/menu. */
@Composable
internal fun MobileAutoLockPicker(current: AutoLockDuration, onPick: (AutoLockDuration) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { MobileSelectTrigger(current.autoLockLabel(), onClick = { open = !open }) },
        menu = { width ->
            MobileDropdownMenu(width) {
                AutoLockDuration.entries.forEach { option ->
                    MobileDropdownOption(option.autoLockLabel(), selected = option == current) { onPick(option); open = false }
                }
            }
        },
    )
}

// Appearance (More -> Appearance push screen).

/** Setting row with a stepper (mobile): label + default-value hint on the left, [NumberStepper] on the right. */
@Composable
internal fun MobileStepperRow(
    label: String,
    isDefault: Boolean,
    defaultText: String,
    onReset: () -> Unit,
    stepper: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Txt(label, color = Skerry.colors.text, size = 14.5.sp)
            MobileDefaultValueHint(isDefault, defaultText, onReset)
        }
        // Published like the desktop's SettingRow does: NumberStepper names itself from here, and
        // without it the phone's font-size box is an anonymous input with a number in it.
        CompositionLocalProvider(LocalFieldLabel provides label) { stepper() }
    }
}

/** Default-value hint (mobile): gray text when at default, cyan clickable reset when changed. */
@Composable
private fun MobileDefaultValueHint(isDefault: Boolean, defaultText: String, onReset: () -> Unit) {
    val text = stringResource(Res.string.appearance_default_value, defaultText)
    if (isDefault) {
        Txt(text, color = Skerry.colors.faint, size = 12.sp, modifier = Modifier.padding(top = 3.dp))
    } else {
        Row(
            Modifier.padding(top = 3.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onReset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Sym("restart_alt", size = 14.sp, color = Skerry.colors.cyan)
            Txt(text, color = Skerry.colors.cyan, size = 12.sp)
        }
    }
}

/** Select trigger: value on the left, chevron on the right. */
@Composable
internal fun MobileSelectTrigger(value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(value, color = Skerry.colors.text, size = 13.sp)
        Sym("expand_more", size = 18.sp, color = Skerry.colors.faint)
    }
}

/** Dropdown menu column (surface + border per layout). */
@Composable
internal fun MobileDropdownMenu(width: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(8.dp)).background(Skerry.colors.surface2).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp)),
    ) { content() }
}

/** Dropdown option; the selected one is highlighted cyan. */
@Composable
internal fun MobileDropdownOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Txt(
        label,
        color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text,
        size = 13.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

// Profile.
