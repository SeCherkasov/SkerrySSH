package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/**
 * Compact chip button (6dp corner radius, border, 12dp horizontal padding). Two variants:
 * outline (default — transparent background + [Skerry.colors.cyan14] border) and filled ([filled] —
 * tinted background/border from [color]; disabled state is dimmed).
 */
@Composable
fun ChipButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    size: TextUnit = 11.5.sp,
    weight: FontWeight = FontWeight.Normal,
    verticalPadding: Dp = 6.dp,
) {
    val background = if (filled) (if (enabled) color.copy(alpha = 0.16f) else Skerry.colors.overlaySoft) else Color.Transparent
    val border = if (filled) (if (enabled) color.copy(alpha = 0.5f) else Skerry.colors.line) else Skerry.colors.cyan14
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = if (enabled) color else Skerry.colors.faint, size = size, weight = weight)
    }
}
