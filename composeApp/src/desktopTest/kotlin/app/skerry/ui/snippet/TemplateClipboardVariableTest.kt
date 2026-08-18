package app.skerry.ui.snippet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetVariableKind
import app.skerry.ui.desktop.CROSS_THREAD_TIMEOUT_MS
import app.skerry.ui.design.FakeSystemClipboard
import app.skerry.ui.desktop.runForm
import app.skerry.ui.terminal.LocalSystemClipboard
import app.skerry.ui.terminal.SystemClipboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `${'$'}{{clipboard}}` in a snippet, resolved through the platform's own clipboard: on Wayland the text
 * the user actually copied lives in `wl-clipboard`, and reading Compose/AWT instead would splice
 * whatever XWayland happens to hold into the command that runs (#282).
 */
@OptIn(ExperimentalTestApi::class)
class TemplateClipboardVariableTest {

    private val clipboardVar =
        SnippetSegment.Variable(SnippetVariableKind.CLIPBOARD, "clipboard", null, "\${{clipboard}}")

    @Test
    fun `the clipboard value comes from the system clipboard`() {
        val values = resolve(FakeSystemClipboard(content = "copied text"))

        assertTrue(values.canRun, "the confirmation never left its pending state")
        assertEquals("copied text", values.value(clipboardVar, masked = false))
    }

    /**
     * A clipboard that throws resolves to empty rather than staying unanswered: `canRun` waits on
     * this value, so a swallowed failure would leave Run disabled with no way to find out why.
     */
    @Test
    fun `a clipboard that refuses to answer still opens the confirmation`() {
        val values = resolve(FakeSystemClipboard(refusesRead = true))

        assertTrue(values.canRun, "a refused clipboard read left the confirmation shut")
        assertEquals("", values.value(clipboardVar, masked = false))
        // Both splice nothing, but only an empty clipboard is something the user can fix by copying.
        assertTrue(values.clipboardUnavailable, "a clipboard that never answered was shown as an empty one")
    }

    @Test
    fun `an empty clipboard is not reported as one that failed`() {
        val values = resolve(FakeSystemClipboard(content = ""))

        assertTrue(values.canRun, "an empty clipboard left the confirmation shut")
        assertFalse(values.clipboardUnavailable, "an empty clipboard was shown as a failure")
    }

    /** Drives one confirmation's worth of resolution over [clipboard] and hands back the values. */
    private fun resolve(clipboard: SystemClipboard): TemplateVariableValues {
        lateinit var values: TemplateVariableValues
        runForm({
            CompositionLocalProvider(LocalSystemClipboard provides clipboard) {
                values = rememberTemplateVariableValues("request", listOf(clipboardVar))
            }
        }) {
            waitUntil("the clipboard never answered", timeoutMillis = CROSS_THREAD_TIMEOUT_MS) { values.canRun }
        }
        return values
    }
}
