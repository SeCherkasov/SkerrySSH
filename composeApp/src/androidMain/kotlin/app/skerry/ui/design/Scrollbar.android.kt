package app.skerry.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun SkerryVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    // Touch scrolling is native on Android; desktop-style scrollbar is omitted.
}

@Composable
actual fun SkerryVerticalScrollbar(
    lazyListState: LazyListState,
    modifier: Modifier,
) {
    // Touch scrolling is native on Android; desktop-style scrollbar is omitted.
}
