package app.skerry.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.skerry.ui.theme.Skerry

@Composable
actual fun SkerryVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    if (scrollState.maxValue > 0) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = modifier,
            style = defaultScrollbarStyle().copy(
                thickness = 4.dp,
                shape = RoundedCornerShape(2.dp),
                unhoverColor = Skerry.colors.lineStrong.copy(alpha = 0.6f),
                hoverColor = Skerry.colors.cyanBright.copy(alpha = 0.9f),
            ),
        )
    }
}

@Composable
actual fun SkerryVerticalScrollbar(
    lazyListState: LazyListState,
    modifier: Modifier,
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(lazyListState),
        modifier = modifier,
        style = defaultScrollbarStyle().copy(
            thickness = 4.dp,
            shape = RoundedCornerShape(2.dp),
            unhoverColor = Skerry.colors.lineStrong.copy(alpha = 0.6f),
            hoverColor = Skerry.colors.cyanBright.copy(alpha = 0.9f),
        ),
    )
}
