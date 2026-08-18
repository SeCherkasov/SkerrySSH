package app.skerry.ui.snippet

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.snippet.Snippet
import app.skerry.ui.design.MAX_UNTRUSTED_LABEL_CHARS
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.desktop.drawnText
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_ambiguous
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_unnamed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `${{vault:name}}` entry name comes from the template, and a template can arrive from a team
 * member. It names the credential about to be spliced into a remote command, so it is drawn through
 * the same filter every other untrusted label uses: a right-to-left override in it would make the
 * row read as one entry while another is looked up.
 */
@OptIn(ExperimentalTestApi::class)
class TemplateVariableVaultLabelTest {

    // Escapes, not the raw glyphs: an invisible character in source is unreviewable.
    private val reordered = "prod\u202Edb"
    private val invisible = "stag\u200Bing"
    private val unusable = "web\u202D1"

    /** Two entries answer to this one — the row must say so rather than name a secret. */
    private val ambiguous = "twins"

    /** Nothing of this name survives the filter — the row still has to name something. */
    private val unprintable = "\u3164\u200B"

    @Test
    fun `the run confirmation filters a vault entry name before drawing it`() {
        val values = TemplateVariableValues(
            paramNames = emptyList(),
            paramChoices = emptyMap(),
            vaultRefs = listOf(reordered, invisible, unusable, ambiguous, unprintable),
            needsClipboard = false,
            params = mutableStateMapOf(),
        ).apply {
            // All three draw sites: the resolved row and the two error sentences the name is
            // spliced into.
            vaultResolutions = mapOf(
                reordered to VaultRef.Ok("s3cret", secret = true),
                invisible to VaultRef.Missing,
                unusable to VaultRef.Unusable,
                ambiguous to VaultRef.Ambiguous,
                unprintable to VaultRef.Ok("s3cret", secret = true),
            )
        }
        runForm({ TemplateVariableFields(values, autoFocus = false) }) {
            val drawn = drawnText()
            assertTrue(drawn.any { it.contains("proddb") }, "the resolved entry is still named, was $drawn")
            // The mask is the resolved row's own mark: the look-up still keys on the raw name, and
            // only what is drawn was filtered.
            assertEquals(2, drawn.count { it == SECRET_MASK }, "both resolved rows still draw, was $drawn")
            assertTrue(drawn.none { it.hasFormatChars() }, "no formatting character reaches the screen, was $drawn")
            assertTrue(
                drawn.any { it == string(Res.string.lib_snippet_vars_vault_ambiguous, ambiguous) },
                "a name two entries answer to says so, was $drawn",
            )
            assertTrue(
                drawn.any { it.contains(string(Res.string.lib_snippet_vars_vault_unnamed)) },
                "a name that filters away to nothing still names its row, was $drawn",
            )
        }
    }

    /**
     * A parameter name is grammar-bound but not length-bound, and its caption is also the field's
     * accessible name: unbounded, one shared template pushes the preview and the Run button out of
     * the dialog.
     */
    @Test
    fun `the run confirmation caps a parameter caption`() {
        val long = "a".repeat(300)
        val values = TemplateVariableValues(
            paramNames = listOf(long),
            paramChoices = emptyMap(),
            vaultRefs = emptyList(),
            needsClipboard = false,
            params = mutableStateMapOf(),
        )
        runForm({ TemplateVariableFields(values, autoFocus = false) }) {
            val drawn = drawnText()
            assertTrue(drawn.any { it == untrustedLabel(long) }, "the caption is the filtered name")
            assertTrue(drawn.none { it.length > MAX_UNTRUSTED_LABEL_CHARS }, "nothing longer is drawn, was $drawn")
        }
    }

    /**
     * The panel's parameter field sits outside a [app.skerry.ui.design.FormField], so it takes its
     * name from the caption beside it explicitly — without that it is an unnamed input in a form
     * whose values are spliced into a remote command.
     */
    @Test
    fun `the run panel names its parameter field after the caption`() {
        val snippet = Snippet(id = "s2", label = "Dump", command = "mysqldump \${{database}}")
        runForm({
            SnippetRunPanel(
                entry = SnippetEntry(snippet),
                targets = emptyList(),
                activeTargetId = null,
                mono = FontFamily.Monospace,
                onRun = { _, _ -> true },
                onCopy = {}, onEdit = {}, onDelete = {},
            )
        }) {
            onNodeWithContentDescription("database").assertExists()
        }
    }

    @Test
    fun `the run panel filters a vault entry name before drawing it`() {
        val snippet = Snippet(id = "s1", label = "Dump", command = "mysqldump -p\${{vault:$reordered}}")
        runForm({
            SnippetRunPanel(
                entry = SnippetEntry(snippet),
                targets = emptyList(),
                activeTargetId = null,
                mono = FontFamily.Monospace,
                onRun = { _, _ -> true },
                onCopy = {}, onEdit = {}, onDelete = {},
            )
        }) {
            val drawn = drawnText()
            // The command block quotes the template verbatim, which is a separate finding; the
            // variables list is what names the credential, and that name is filtered.
            assertTrue(drawn.any { it.contains("proddb") }, "the panel names the entry, was $drawn")
        }
    }

    private fun String.hasFormatChars(): Boolean = any { it.category == CharCategory.FORMAT }

}
