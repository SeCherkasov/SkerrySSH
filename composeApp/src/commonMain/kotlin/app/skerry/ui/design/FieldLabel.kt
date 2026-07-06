package app.skerry.ui.design

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Uppercase form field label (small caps, dimmed color), with [top] spacing from the previous
 * block. Shared primitive for settings/dialog forms (same look as sync/tunnel/snippet form labels).
 */
@Composable
fun FieldLabel(text: String, top: Dp = 12.dp) {
    Txt(
        text,
        color = D.faint,
        size = 10.5.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = top, bottom = 5.dp),
    )
}
