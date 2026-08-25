package app.skerry.ui.sftp

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileItem
import app.skerry.ui.desktop.runForm
import app.skerry.ui.files.FilePaneController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

/**
 * Draws [entries] with the live listing the session panel uses, and runs [body] over the result.
 *
 * The pane controller is real but never lists anything: what these tests are about is what
 * [LivePaneList] does with entries it is given, so the entries are handed in directly and the
 * browser behind the pane only exists because the controller needs one.
 */
@OptIn(ExperimentalTestApi::class)
internal fun renderPaneList(entries: List<FileItem>, body: ComposeUiTest.() -> Unit) {
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val pane = FilePaneController(EmptyBrowser, scope)
    try {
        runForm({
            LivePaneList(
                pane = pane,
                entries = entries,
                mono = FontFamily.Monospace,
                listState = rememberLazyListState(),
                active = true,
                onActivate = {},
            )
        }, body)
    } finally {
        scope.cancel()
    }
}

private object EmptyBrowser : FileBrowser {
    override val label: String = "stub"
    override suspend fun realpath(path: String): String = "/"
    override suspend fun list(path: String): List<FileItem> = emptyList()
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit
}
