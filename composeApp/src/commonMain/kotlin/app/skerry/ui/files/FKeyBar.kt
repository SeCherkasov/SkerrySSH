package app.skerry.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Txt
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * One cell of the mc-style bottom key bar: function key [n] and what it does. [enabled] greys the
 * cell out when the action isn't available in the current context (nothing to save, no session yet);
 * [done] marks a label that is only a placeholder — such a cell carries a visible "*".
 */
internal data class FKeyDef(
    val n: Int,
    val label: StringResource,
    val done: Boolean = true,
    val enabled: Boolean = true,
)

/**
 * Bottom hotkey bar (mc/Total Commander): a row of "F3 View … F10 Quit" cells spanning the width.
 * Shared by the file panel and the built-in editor, which swap their own [keys] in — the bar is the
 * screen's key legend, so it has to follow whatever currently owns the keyboard. A cell click and
 * the matching key press both go through [onKey].
 */
@Composable
internal fun FKeyBar(keys: List<FKeyDef>, onKey: (Int) -> Unit, mono: FontFamily) {
    Row(
        Modifier.fillMaxWidth().background(Skerry.colors.surface2).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { def ->
            FKeyCell(def, mono, onClick = { onKey(def.n) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FKeyCell(def: FKeyDef, mono: FontFamily, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Skerry.colors.panel)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = def.enabled,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Txt("F${def.n}", color = if (def.enabled) Skerry.colors.cyanBright else Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, font = mono)
        Txt(
            stringResource(def.label),
            color = if (def.enabled) Skerry.colors.text else Skerry.colors.faint,
            size = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // "*" marks a non-working stub.
        if (!def.done) Txt("*", color = Skerry.colors.sunset, size = 11.sp, weight = FontWeight.SemiBold)
    }
}
