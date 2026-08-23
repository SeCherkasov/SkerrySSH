package app.skerry.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileContentBrowser
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.host.Host
import app.skerry.ui.ai.AssistantPanel
import app.skerry.ui.ai.ModelPickerMenu
import app.skerry.ui.ai.sessionAssistant
import app.skerry.ui.ai.terminalAi
import app.skerry.ui.ai.SilentSession
import app.skerry.ui.ai.terminalState
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.seededRunbooks
import app.skerry.ui.desktop.seededSnippets
import app.skerry.ui.desktop.string
import app.skerry.ui.files.FileEditController
import app.skerry.ui.files.FileEditorScreen
import app.skerry.ui.files.PathJumpField
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.assistant_ask_placeholder
import app.skerry.ui.generated.resources.ftail_fkey_search
import app.skerry.ui.generated.resources.rd_keyboard_input
import app.skerry.ui.generated.resources.runbook_palette_placeholder
import app.skerry.ui.generated.resources.shell_group_name_placeholder
import app.skerry.ui.generated.resources.shell_password_host_placeholder
import app.skerry.ui.generated.resources.sftp_edit_buffer
import app.skerry.ui.generated.resources.sftp_edit_find
import app.skerry.ui.generated.resources.sftp_path_field
import app.skerry.ui.generated.resources.term_ai_ask_short
import app.skerry.ui.generated.resources.term_keyboard_input
import app.skerry.ui.generated.resources.term_palette_placeholder
import app.skerry.ui.generated.resources.term_run_snippet_placeholder
import app.skerry.ui.mobile.MobileAiBarInput
import app.skerry.ui.mobile.MobileCommandPaletteSheet
import app.skerry.ui.mobile.MobileGroupCreateDialog
import app.skerry.ui.mobile.MobileGroupRenameDialog
import app.skerry.ui.mobile.MobilePasswordSheet
import app.skerry.ui.mobile.VncImeField
import app.skerry.ui.remote.FakeRemoteDesktop
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.runbook.RunbookPalette
import app.skerry.ui.terminal.CommandPalette
import app.skerry.ui.terminal.SnippetPalette
import app.skerry.ui.terminal.TerminalScreen
import app.skerry.ui.terminal.TerminalScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test

/**
 * Every input the app draws without a caption above it, as a screen reader meets it (issue #228).
 *
 * A search box, an ask bar and the soft keyboard's own funnel share a shape: nothing on screen
 * labels them but a placeholder, which is a sibling text node with no relation to the field. Left
 * alone the reader announces "edit box" and stops — the case
 * [Modifier.fieldName]`(fallback = …)` exists for, and the one every palette in the app was
 * written before it.
 *
 * The assertion is deliberately the *lookup*: found by the name, and the node found takes text.
 * A name that lands on the placeholder label instead of the field would satisfy the first half and
 * fail the second.
 */
@OptIn(ExperimentalTestApi::class)
class SearchFieldNamingTest {

    @Test
    fun `the command palette search is named by its placeholder`() =
        runForm({ CommandPalette(history = null, currentKey = null, onPick = {}, onDismiss = {}) }) {
            assertNamedInput(Res.string.term_palette_placeholder)
        }

    @Test
    fun `the snippet palette search is named by its placeholder`() =
        runForm({ SnippetPalette(seededSnippets()) {} }) {
            assertNamedInput(Res.string.term_run_snippet_placeholder)
        }

    @Test
    fun `the runbook palette search is named by its placeholder`() =
        runForm({ RunbookPalette(seededRunbooks()) {} }) {
            assertNamedInput(Res.string.runbook_palette_placeholder)
        }

    @Test
    fun `the model picker search is named by the placeholder it was given`() =
        runForm({ modelPicker() }) {
            onNodeWithContentDescription(MODEL_SEARCH).assert(hasSetTextAction())
        }

    /**
     * The desktop settings screen composes this menu inside `FormField("Model")`, and a popup is a
     * subcomposition — so the combo's caption reaches the menu and would name the search box after
     * the field it belongs to. Two platforms, one composable, two different names: on the phone the
     * menu is drawn in the page flow with no caption over it.
     */
    @Test
    fun `the model picker search keeps its own name inside a captioned form field`() =
        runForm({ FormField(FORM_CAPTION) { modelPicker() } }) {
            onNodeWithContentDescription(MODEL_SEARCH).assert(hasSetTextAction())
            onNodeWithContentDescription(FORM_CAPTION).assertDoesNotExist()
        }

    /**
     * Parity: the phone draws the same palettes and sheets through `MobileFormInput`, which had a
     * placeholder in hand and passed no fallback — so every one of them the phone shows without a
     * caption above it was still an unnamed edit box.
     */
    @Test
    fun `the mobile palette search is named by its placeholder`() =
        runForm({ MobileCommandPaletteSheet(history = null, currentKey = null, onPick = {}, onDismiss = {}) }) {
            assertNamedInput(Res.string.term_palette_placeholder)
        }

    @Test
    fun `the assistant ask box is named by its placeholder`() =
        runForm({ AssistantPanel(sessionAssistant(), terminal = null, modelLabel = "opus") }) {
            assertNamedInput(Res.string.assistant_ask_placeholder)
        }

    @Test
    fun `the mobile ask bar is named by its placeholder`() =
        runForm({ MobileAiBarInput(terminalAi(reply = "uptime"), terminalState()) }) {
            assertNamedInput(Res.string.term_ai_ask_short)
        }

    /** No placeholder at all here: the field is prefilled with the path, so it names itself. */
    @Test
    fun `the path jump field names itself`() =
        runForm({
            PathJumpField(
                path = "/var/log",
                mono = FontFamily.Monospace,
                textSize = 12.sp,
                onCommit = {},
                onCancel = {},
            ) { inner -> inner() }
        }) {
            assertNamedInput(Res.string.sftp_path_field)
        }

    @Test
    fun `the file editor buffer names itself`() = withEditor { controller ->
        runForm({ FileEditorScreen(controller, onClose = {}, modifier = Modifier.fillMaxSize()) }) {
            assertNamedInput(Res.string.sftp_edit_buffer)
        }
    }

    /** The find bar's caption is a sibling `Txt`, so the field has to adopt it explicitly. */
    @Test
    fun `the file editor find bar is named by the caption beside it`() = withEditor { controller ->
        runForm({ FileEditorScreen(controller, onClose = {}, modifier = Modifier.fillMaxSize()) }) {
            onNodeWithText(string(Res.string.ftail_fkey_search), substring = true).performClick()
            waitForIdle()
            assertNamedInput(Res.string.sftp_edit_find)
        }
    }

    /**
     * The terminal's soft-keyboard funnel is a 1dp field with nothing drawn in it — the one input
     * on the phone that a reader can reach and cannot possibly identify from the screen.
     */
    @Test
    fun `the terminal soft-keyboard funnel is named`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val state = TerminalScreenState(SilentSession(), scope)
        // A failing assertion is exactly what this test exists to produce, and the terminal's
        // outbound writer waits on a channel that is never closed: cancelled in a finally, or a
        // regression here leaks a coroutine into every test that runs after it.
        try {
            runForm({
                Box(Modifier.fillMaxSize()) { TerminalScreen(state, Modifier.fillMaxSize(), imeInput = true) }
            }) {
                assertNamedInput(Res.string.term_keyboard_input)
            }
        } finally {
            scope.cancel()
        }
    }

    /** Same funnel on the remote-desktop screen, feeding RFB key events instead of a PTY. */
    @Test
    fun `the remote desktop soft-keyboard funnel is named`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val screen = RemoteDesktopScreenState(FakeRemoteDesktop(framebuffer = RemoteFramebuffer(4, 4)), scope)
        try {
            runForm({ VncImeField(screen) {} }) { assertNamedInput(Res.string.rd_keyboard_input) }
        } finally {
            scope.cancel()
        }
    }

    /**
     * A placeholder is only a name when it names the field. `MobilePasswordSheet` draws the masking
     * dots as its placeholder and its caption as a plain sibling `Txt`, so the fallback alone made
     * a reader announce the glyphs — on the one field of the app that holds an SSH password.
     */
    @Test
    fun `the connect password field is named by its caption, not by the masking dots`() =
        runForm({ MobilePasswordSheet(HOST, onDismiss = {}, onConnect = {}) }) {
            onNodeWithContentDescription(string(Res.string.shell_password_host_placeholder)).assert(hasSetTextAction())
            onNodeWithContentDescription(MASK_DOTS).assertDoesNotExist()
        }

    /** Same shape with an example value for a placeholder: "Production" is not what the field is. */
    @Test
    fun `the group name field is named by what it holds, not by the example in it`() =
        runForm({ MobileGroupCreateDialog(onDismiss = {}, onCreate = {}) }) {
            assertNamedInput(Res.string.shell_group_name_placeholder)
        }

    /** The rename dialog prefills the old name, so its placeholder never draws — and still named it. */
    @Test
    fun `the group rename field is named too`() =
        runForm({ MobileGroupRenameDialog(initialName = "Production", onDismiss = {}, onSave = {}, onDelete = {}) }) {
            assertNamedInput(Res.string.shell_group_name_placeholder)
        }

    @Composable
    private fun modelPicker() = ModelPickerMenu(
        models = listOf("claude-opus-5"),
        selected = "claude-opus-5",
        favorites = emptySet(),
        onToggleFavorite = {},
        onSelect = {},
        emptyText = "none",
        searchPlaceholder = MODEL_SEARCH,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.assertNamedInput(name: StringResource) {
        onNodeWithContentDescription(string(name)).assert(hasSetTextAction())
    }
}

/** A buffer already loaded, so the editor draws the field rather than its loading notice. */
private fun withEditor(body: (FileEditController) -> Unit) {
    val item = FileItem("nginx.conf", PATH, FileItemType.File, CONTENT.length.toLong(), 100)
    val scope = CoroutineScope(Dispatchers.Unconfined)
    try {
        body(
            FileEditController(
                source = InMemoryFile(item),
                item = item,
                readOnly = false,
                scope = scope,
            ).also { it.open() },
        )
    } finally {
        scope.cancel()
    }
}

/** The one file the editor test opens; navigation is never exercised. */
private class InMemoryFile(private val item: FileItem) : FileContentBrowser, FileBrowser {
    override val label = "prod-web-01"
    override suspend fun realpath(path: String) = path
    override suspend fun list(path: String): List<FileItem> = emptyList()
    override suspend fun mkdir(path: String) = Unit
    override suspend fun delete(item: FileItem) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override suspend fun stat(path: String): FileItem = item
    override suspend fun readFile(path: String, maxBytes: Long): ByteArray = CONTENT.encodeToByteArray()
    override suspend fun writeFile(path: String, data: ByteArray) = Unit
}

private const val PATH = "/etc/nginx/nginx.conf"
private const val CONTENT = "server {\n}\n"
private const val MODEL_SEARCH = "Search models…"

/** What `MobilePasswordSheet` draws in the empty field — glyphs to be seen, never spoken. */
private const val MASK_DOTS = "••••••••"

private val HOST = Host("h1", "prod-web-01", "192.168.1.45", 22, "root")

/** Any caption will do — what matters is that one is in scope at all. */
private const val FORM_CAPTION = "Model"
