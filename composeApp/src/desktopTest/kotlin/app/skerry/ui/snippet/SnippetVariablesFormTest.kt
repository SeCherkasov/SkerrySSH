package app.skerry.ui.snippet

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.seededSnippets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The variable dialog a `${{…}}` snippet stops at before it runs.
 *
 * The invariant it exists for is that what the preview shows is what gets sent — a value typed here
 * ends up in the command line on a live host, so an unfilled placeholder must block the run rather
 * than go out as the literal `${{host}}`.
 */
@OptIn(ExperimentalTestApi::class)
class SnippetVariablesFormTest {

    @Test
    fun `a filled placeholder is what gets sent`() {
        val manager = seededSnippets()
        val id = manager.save(SnippetDraft(label = "restart", command = "systemctl restart ${'$'}{{unit}}"))
        var sent: String? = null
        manager.run(id) { sent = it }

        runForm({ SnippetRunDialog(manager) }) {
            onNodeWithContentDescription("unit").performTextInput("nginx")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals("systemctl restart nginx", sent?.trimEnd())
    }

    /** Dismissing the dialog must send nothing: the command was never confirmed. */
    @Test
    fun `dismissing the dialog sends nothing`() {
        val manager = seededSnippets()
        val id = manager.save(SnippetDraft(label = "restart", command = "systemctl restart ${'$'}{{unit}}"))
        var sent: String? = null
        manager.run(id) { sent = it }

        runForm({ SnippetRunDialog(manager) }) {
            onNodeWithContentDescription("unit").performTextInput("nginx")
            onNodeWithTag(UiTags.FORM_CANCEL).performClick()
            waitForIdle()
        }
        assertNull(sent, "a dismissed run was sent to the host anyway")
    }

    /**
     * A placeholder nobody filled in must not reach the shell as text: `${'$'}{{unit}}` would be a brace
     * expansion, and `systemctl restart ${'$'}{{unit}}` would restart a unit of that literal name.
     */
    @Test
    fun `an untouched placeholder is sent as nothing, not as its own text`() {
        val manager = seededSnippets()
        val id = manager.save(SnippetDraft(label = "restart", command = "systemctl restart ${'$'}{{unit}}"))
        var sent: String? = null
        manager.run(id) { sent = it }

        runForm({ SnippetRunDialog(manager) }) {
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals("systemctl restart", sent?.trimEnd())
    }

    /** Two placeholders, two values, each landing where it was typed rather than in the other's slot. */
    @Test
    fun `each placeholder takes its own value`() {
        val manager = seededSnippets()
        val id = manager.save(
            SnippetDraft(label = "tail", command = "tail -n ${'$'}{{lines}} /var/log/${'$'}{{file}}"),
        )
        var sent: String? = null
        manager.run(id) { sent = it }

        runForm({ SnippetRunDialog(manager) }) {
            onNodeWithContentDescription("lines").performTextInput("50")
            onNodeWithContentDescription("file").performTextInput("nginx.log")
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals("tail -n 50 /var/log/nginx.log", sent?.trimEnd())
    }
}
