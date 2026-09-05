package app.skerry.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Vertical scrollbar for a scrollable the pointer drives — a palette, a run log, any list tall
 * enough that "there is more below" is not obvious from the rows alone.
 *
 * Desktop draws Compose Desktop's thin capsule in the theme's colours; Android draws nothing, since
 * a touch list scrolls under the finger and a bar only takes width from it. Overlay it on the
 * scrollable rather than putting it in the row: it is chrome, not a column.
 */
@Composable
expect fun SkerryVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
)

/** [SkerryVerticalScrollbar] for a `LazyColumn`. */
@Composable
expect fun SkerryVerticalScrollbar(
    lazyListState: LazyListState,
    modifier: Modifier = Modifier,
)
