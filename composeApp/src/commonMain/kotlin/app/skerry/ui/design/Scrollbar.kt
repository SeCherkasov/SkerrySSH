package app.skerry.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders a platform-adaptive vertical scrollbar.
 *
 * On desktop, draws Compose Desktop's [VerticalScrollbar] with Skerry's theme colors,
 * hover highlight, and draggable capsule thumb.
 * On Android, touch dragging is native and mouse-driven scrollbars are omitted.
 */
@Composable
expect fun SkerryVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
)

@Composable
expect fun SkerryVerticalScrollbar(
    lazyListState: LazyListState,
    modifier: Modifier = Modifier,
)
