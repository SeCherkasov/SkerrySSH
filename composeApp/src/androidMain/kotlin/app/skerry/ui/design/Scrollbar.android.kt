package app.skerry.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Nothing to draw: a touch list scrolls under the finger, and a bar there would only take width
// from the rows. See the expect declaration.

@Composable
actual fun SkerryVerticalScrollbar(scrollState: ScrollState, modifier: Modifier) = Unit

@Composable
actual fun SkerryVerticalScrollbar(lazyListState: LazyListState, modifier: Modifier) = Unit
