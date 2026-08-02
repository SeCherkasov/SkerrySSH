package app.skerry.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/**
 * A setting expressed as a switch: label (with an optional explanatory line) on the leading edge,
 * [Toggle] on the trailing one. Shared because desktop panels, editor forms and mobile sheets all
 * need the same row and had started drawing it three slightly different ways.
 *
 * [labelSize] exists for the mobile type scale; everything else is fixed so the rows stay
 * recognisably the same control across platforms.
 */
@Composable
fun ToggleRow(
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = Skerry.colors.faint,
    labelSize: TextUnit = 12.5.sp,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Txt(label, color = Skerry.colors.text, size = labelSize)
            if (subtitle != null) {
                Txt(subtitle, color = subtitleColor, size = 11.sp, lineHeight = 15.sp)
            }
        }
        Toggle(on = on, onToggle = onToggle)
    }
}
