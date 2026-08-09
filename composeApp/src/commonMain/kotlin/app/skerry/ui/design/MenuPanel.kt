package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/**
 * The app's pop-up menu: a rounded panel of [MenuItem] rows. Every menu the app opens on a right
 * click or a "⋮" button wears this — including the one Compose itself opens over selected text,
 * which is why it lives in the design layer rather than next to any one call site.
 */
@Composable
fun MenuPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(7.dp))
            .padding(4.dp),
    ) {
        content()
    }
}

/** One row of a [MenuPanel]. [color] carries the destructive variant (`Skerry.colors.sunset`). */
@Composable
fun MenuItem(label: String, color: Color = Skerry.colors.text, onClick: () -> Unit) {
    Box(
        Modifier
            .widthIn(min = MENU_MIN_WIDTH)
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Txt(label, color = color, size = 12.sp)
    }
}

/**
 * The width floor keeps a menu from collapsing onto its longest label — short verbs in some locales
 * leave a sliver of a click target, and the same menu would change width from row to row depending
 * on which actions that row offers.
 */
private val MENU_MIN_WIDTH = 140.dp
