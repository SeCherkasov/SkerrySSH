package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.skerry.ui.nav.PlatformBackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.skerry.ui.theme.Skerry

/**
 * Shared mobile bottom-sheet chrome: full-screen scrim (`Skerry.colors.scrim`, tap outside to dismiss),
 * bottom panel with 26dp corner rounding, grab handle, and drag-to-dismiss
 * ([rememberSheetDrag]/[SheetHandle]). The container owns ONLY the chrome — height, scrolling, and
 * padding are set by each sheet via [panelModifier], so forms/details/menus reuse one skeleton
 * without losing their own layout details. The sheet doesn't lift itself above the keyboard: the
 * root `safeDrawing` (see MobileDesignApp) shrinks the area above the IME, and the sheet (pinned
 * to the bottom) rides above the keyboard along with all its content.
 *
 * Taps on the panel itself are absorbed (the sheet doesn't dismiss); [content] goes right below
 * the handle inside the panel's [ColumnScope] — `Modifier.weight`/`verticalScroll` etc. are
 * available there.
 *
 * @param maxHeightFraction if set, caps the panel height to a screen fraction (detail sheet, so
 *   long content scrolls instead of overflowing the top); null means the height comes from the
 *   content/panelModifier.
 */
@Composable
fun MobileBottomSheet(
    onDismiss: () -> Unit,
    panelModifier: Modifier = Modifier,
    maxHeightFraction: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // System back/gesture dismisses the sheet (like a tap outside/swipe down). Composed deeper than
    // the chrome's navigation interceptor, so by the dispatcher's LIFO order it fires first,
    // keeping back from navigating away from the screen underneath the sheet.
    PlatformBackHandler(onBack = onDismiss)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Skerry.colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val drag = rememberSheetDrag(onDismiss)
        val cap = maxHeightFraction?.let { Modifier.heightIn(max = maxHeight * it) } ?: Modifier
        Column(
            Modifier
                .fillMaxWidth()
                .then(cap)
                .then(drag.sheet)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Skerry.colors.surface2)
                // Absorb clicks on the panel so tapping the sheet doesn't dismiss it (dismiss is scrim/swipe only).
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .then(panelModifier),
        ) {
            SheetHandle(drag)
            content()
        }
    }
}
