package app.skerry.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skerry.ui.theme.Skerry

@Composable
actual fun SkerryVerticalScrollbar(scrollState: ScrollState, modifier: Modifier) {
    // maxValue is 0 only once the content has been measured and fits: no bar over a list that has
    // nowhere to scroll.
    if (scrollState.maxValue > 0) {
        VerticalScrollbar(rememberScrollbarAdapter(scrollState), modifier, style = skerryScrollbarStyle())
    }
}

@Composable
actual fun SkerryVerticalScrollbar(lazyListState: LazyListState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(lazyListState), modifier, style = skerryScrollbarStyle())
}

/** Thin capsule in the theme's colours, brightening under the pointer. */
@Composable
private fun skerryScrollbarStyle(): ScrollbarStyle {
    val idle = Skerry.colors.lineStrong.copy(alpha = IDLE_ALPHA)
    val hover = Skerry.colors.cyanBright.copy(alpha = HOVER_ALPHA)
    return remember(idle, hover) {
        defaultScrollbarStyle().copy(
            thickness = 4.dp,
            shape = RoundedCornerShape(2.dp),
            unhoverColor = idle,
            hoverColor = hover,
        )
    }
}

private const val IDLE_ALPHA = 0.6f
private const val HOVER_ALPHA = 0.9f
