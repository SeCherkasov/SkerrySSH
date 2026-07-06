package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.skerry.ui.design.D
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

/** One bottom-sheet menu action: label plus an optional icon; [danger] colors it coral (destructive). */
data class MobileSheetAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: String? = null,
    val danger: Boolean = false,
)

/** Places the sheet at the window's top-left corner; the content stretches to fill the screen itself. */
private val FullWindowPosition = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * Shared bottom-sheet menu for contextual actions (Forget key / Edit-Remove / Disconnect, etc.) in
 * the same idiom as the New connection / Vault sheets: full-screen scrim, bottom panel with a
 * grab handle, drag-to-dismiss ([MobileBottomSheet]). Actions render as full-width buttons
 * ([MobileSheetButton]), like the Vault sheet's Copy/Delete buttons rather than flat rows: the
 * first non-destructive action is filled cyan (primary), the rest are outlined, destructive ones
 * with a coral outline.
 *
 * Wrapped in a full-screen [Popup], so it can be inserted as `target?.let { MobileActionSheet(...) }`
 * anywhere in the tree — the scrim always covers the whole screen, not just the source row. Tapping
 * a button closes the sheet then runs [MobileSheetAction.onClick]; tapping outside/swipe down/Back
 * runs [onDismiss].
 */
@Composable
fun MobileActionSheet(
    title: String,
    actions: List<MobileSheetAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    // First non-destructive action is primary (filled button); danger actions are never filled.
    val primaryIndex = actions.indexOfFirst { !it.danger }
    Popup(
        popupPositionProvider = FullWindowPosition,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        MobileBottomSheet(onDismiss = onDismiss, panelModifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Txt(title, color = D.text, size = 15.sp, weight = FontWeight.SemiBold)
            if (subtitle != null) {
                Txt(subtitle, color = D.dim, size = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { index, action ->
                    MobileSheetButton(
                        label = action.label,
                        onClick = { onDismiss(); action.onClick() },
                        modifier = Modifier.fillMaxWidth(),
                        icon = action.icon,
                        filled = index == primaryIndex,
                        danger = action.danger,
                    )
                }
            }
        }
    }
}

/**
 * Bottom-sheet action button at mobile scale (parity with [MobileNewConnectionSheet]): 12-dp
 * corner radius, 13-dp vertical padding, 15-sp text — a large touch target rather than the
 * desktop [PrimaryButton] (8-dp/12-sp), which looked small on a phone. [filled] gives a solid
 * cyan button; otherwise outlined. [danger] colors the outline/text [D.sunset] (deletion). Shared
 * by the Vault sheets and [MobileActionSheet].
 */
@Composable
internal fun MobileSheetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    filled: Boolean = true,
    danger: Boolean = false,
) {
    val fg = when {
        filled -> D.ink
        danger -> D.sunset
        else -> D.text
    }
    val base = Modifier.clip(RoundedCornerShape(12.dp))
        .then(if (filled) Modifier.background(D.cyan) else Modifier.border(1.dp, if (danger) D.sunset.copy(alpha = 0.3f) else D.cyan14, RoundedCornerShape(12.dp)))
    Row(
        modifier.then(base)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Sym(icon, size = 17.sp, color = fg)
        Txt(label, color = fg, size = 15.sp, weight = if (filled) FontWeight.Bold else FontWeight.Medium)
    }
}
