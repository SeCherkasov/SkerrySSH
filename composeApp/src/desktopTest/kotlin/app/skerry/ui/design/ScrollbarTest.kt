package app.skerry.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ScrollbarTest {

    @Test
    fun `vertical scrollbar composes with scrollState without error`() = runComposeUiTest {
        setContent {
            SkerryTheme {
                val state = rememberScrollState()
                Box(Modifier.size(100.dp)) {
                    Column(Modifier.verticalScroll(state)) {
                        Box(Modifier.height(300.dp))
                    }
                    SkerryVerticalScrollbar(
                        scrollState = state,
                        modifier = Modifier.align(Alignment.CenterEnd).matchParentSize(),
                    )
                }
            }
        }
    }

    @Test
    fun `vertical scrollbar composes with lazyListState without error`() = runComposeUiTest {
        setContent {
            SkerryTheme {
                val state = rememberLazyListState()
                Box(Modifier.size(100.dp)) {
                    LazyColumn(state = state) {
                        items(50) {
                            Box(Modifier.height(20.dp))
                        }
                    }
                    SkerryVerticalScrollbar(
                        lazyListState = state,
                        modifier = Modifier.align(Alignment.CenterEnd).matchParentSize(),
                    )
                }
            }
        }
    }
}
