package app.skerry.ui.sftp

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileItem
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_err_unexpected
import app.skerry.ui.mobile.MobileLivePane
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test

/**
 * What a screen reader is told when a pane operation the user asked for ends in a failure.
 *
 * The pane replaces its listing with a notice, and a sighted user sees it. A branch of a `when` is
 * an insertion, not a change to a node that was already there, so nothing is announced and nothing
 * takes focus: a user who confirms a delete and loses it hears silence (WCAG 4.1.3). The announcer
 * has to sit above the `when` and carry the reason itself — the same shape [StatusAnnouncer]'s own
 * doc prescribes and the transfer queue already uses.
 */
@OptIn(ExperimentalTestApi::class)
class PaneErrorAnnouncementTest {

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `a failed pane operation is announced on the desktop panel`() = failedPane { pane ->
        LivePane(
            pane = pane,
            icon = "dns",
            iconColor = Color.White,
            badge = "remote",
            badgeAccent = true,
            mono = FontFamily.Monospace,
            listState = rememberLazyListState(),
            active = true,
            onActivate = {},
            onEditingPath = {},
            onEditingFilter = {},
            filterTick = 0,
            onFilterClose = {},
            restoreFocus = {},
            modifier = Modifier,
        )
    }

    @Test
    fun `and on the phone, where the same operation is one tap away`() = failedPane { pane ->
        MobileLivePane(
            pane = pane,
            mono = FontFamily.Monospace,
            onTransfer = {},
            onDownloadHere = null,
            onOpenEditor = { _, _ -> },
            modifier = Modifier,
        )
    }

    /** Renders [content] over a pane whose listing failed on something the source never wraps. */
    private fun failedPane(content: @Composable (FilePaneController) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val pane = FilePaneController(UnwrappedBrowser, scope)
        pane.start()
        try {
            runForm({ content(pane) }, body())
        } finally {
            scope.cancel()
        }
    }

    private fun body(): ComposeUiTest.() -> Unit = {
        onNode(polite).assertContentDescriptionEquals(string(Res.string.ftail_err_unexpected))
    }
}

/** A source that fails the way [FileBrowserFailure.Unexpected] exists for: without wrapping. */
private object UnwrappedBrowser : FileBrowser {
    override val label: String = "prod-web-01"
    override suspend fun realpath(path: String): String = "/"
    override suspend fun list(path: String): List<FileItem> = throw StackOverflowError()
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit
}
