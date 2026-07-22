package app.skerry.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.appearance_default_value
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

// Shared settings-section widgets (used by several *Section.kt files in this package).

/**
 * Section subtitle: dimmed line under the sticky header. The section's bold title now lives in the
 * panel's static header strip (see SettingsPanel), so it is not repeated here.
 */
@Composable
internal fun SectionSubtitle(subtitle: String) {
    Txt(subtitle, color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 14.dp, bottom = 16.dp))
}

/** Settings group heading: small caps in a muted color, top padding to separate sections. */
@Composable
internal fun SectionLabel(text: String) {
    Txt(
        text,
        color = Skerry.colors.faint,
        size = 11.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

/** A toggle-setting row: title and description on the left, [Toggle] on the right. */
@Composable
internal fun SettingToggleRow(title: String, desc: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium)
            if (desc.isNotEmpty()) Txt(desc, color = Skerry.colors.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Toggle(on, onToggle, Modifier.padding(top = 2.dp))
    }
}

/**
 * Full-width setting row: label on the left, optional ([hasHint]) default-value hint with quick
 * reset below it; control ([app.skerry.ui.design.NumberStepper]/dropdown) on the right of the same
 * line. The default [modifier] spaces the row as a standalone setting; pass e.g. a start inset to
 * render it as a sub-setting attached to the row above.
 */
@Composable
internal fun SettingRow(
    label: String,
    modifier: Modifier = Modifier.padding(top = 16.dp),
    hasHint: Boolean = false,
    isDefault: Boolean = true,
    defaultText: String = "",
    onReset: () -> Unit = {},
    control: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().then(modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Txt(label, color = Skerry.colors.text, size = 13.sp, weight = FontWeight.Medium)
            if (hasHint) DefaultValueHint(isDefault, defaultText, onReset)
        }
        control()
    }
}

/**
 * Default-value hint: static gray text when the value is already default; a clickable cyan row
 * with a reset icon when changed (click restores [defaultText] via [onReset]).
 */
@Composable
private fun DefaultValueHint(isDefault: Boolean, defaultText: String, onReset: () -> Unit) {
    val text = stringResource(Res.string.appearance_default_value, defaultText)
    if (isDefault) {
        Txt(text, color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(top = 2.dp))
    } else {
        Row(
            Modifier.padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onReset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Sym("restart_alt", size = 13.sp, color = Skerry.colors.cyan)
            Txt(text, color = Skerry.colors.cyan, size = 11.sp)
        }
    }
}
