package app.skerry.ui.snippet

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import app.skerry.ui.desktop.drawnText
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_ambiguous
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_missing
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_more
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_ready
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_reading
import app.skerry.ui.generated.resources.lib_snippet_vars_vault_unusable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A key reference resolves after the confirmation is already open and focus has moved on, so the
 * outcome has to be spoken. What decides whether it is spoken is not the modifier but the shape: the
 * announcing node carries the text itself and outlives the change, and it stays silent until every
 * reference has answered — a half-resolved block would announce twice for one dialog.
 */
@OptIn(ExperimentalTestApi::class)
class VaultRefAnnouncementTest {

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    private fun values(vararg refs: String) = values(refs.toList())

    private fun values(refs: List<String>) = TemplateVariableValues(
        paramNames = emptyList(),
        paramChoices = emptyMap(),
        vaultRefs = refs,
        needsClipboard = false,
        params = mutableStateMapOf(),
    )

    @Test
    fun `the block says nothing until every reference has answered`() {
        val v = values("prod-db", "temp_pubkey")
        v.vaultResolutions = mapOf("prod-db" to VaultRef.Ok("s3cret", secret = true))

        runForm({ TemplateVariableFields(v, autoFocus = false) }) {
            onNode(polite).assertContentDescriptionEquals("")
            // And the row itself says the look-up is still running rather than claiming the entry is
            // missing — the state the `null` arm exists for.
            assertTrue(
                drawnText().any { it == string(Res.string.lib_snippet_vars_vault_reading, "temp_pubkey") },
                "the unresolved row names its state, was ${drawnText()}",
            )

            v.vaultResolutions = v.vaultResolutions + ("temp_pubkey" to VaultRef.Ok("ssh-ed25519 AAAA", secret = false))
            waitForIdle()

            // The same node, a new description: a status change, not a node appearing. One sentence
            // per reference, and neither the password nor the key is read out.
            onNode(polite).assertContentDescriptionEquals(
                string(Res.string.lib_snippet_vars_vault_ready, "prod-db") + ". " +
                    string(Res.string.lib_snippet_vars_vault_ready, "temp_pubkey"),
            )
        }
    }

    @Test
    fun `a template with more references than it is worth speaking counts the tail`() {
        val names = (1..6).map { "entry-$it" }
        val v = values(names)
        v.vaultResolutions = names.associateWith { VaultRef.Ok("s3cret", secret = true) }

        runForm({ TemplateVariableFields(v, autoFocus = false) }) {
            // A shared template's reference count has no bound, and a live region cannot be scrolled
            // past: five are named, the rest are counted in words rather than dropped in silence.
            onNode(polite).assertContentDescriptionEquals(
                names.take(5).joinToString(". ") { string(Res.string.lib_snippet_vars_vault_ready, it) } +
                    ". " + string(Res.plurals.lib_snippet_vars_vault_more, 1, 1),
            )
        }
    }

    @Test
    fun `an entry name is filtered before it is spoken`() {
        // Escapes, not the raw glyphs: an invisible character in source is unreviewable. The name
        // comes from a template that may have been shared, and this string is the whole of what a
        // screen-reader user gets — the rows they can re-read are filtered, so this must be too.
        val v = values(listOf("prod\u202Edb"))
        v.vaultResolutions = mapOf("prod\u202Edb" to VaultRef.Ok("s3cret", secret = true))

        runForm({ TemplateVariableFields(v, autoFocus = false) }) {
            onNode(polite).assertContentDescriptionEquals(string(Res.string.lib_snippet_vars_vault_ready, "proddb"))
        }
    }

    @Test
    fun `every outcome a reference can have is spoken as itself`() {
        val v = values(listOf("ghost", "twins", "on-disk"))
        v.vaultResolutions = mapOf(
            "ghost" to VaultRef.Missing,
            "twins" to VaultRef.Ambiguous,
            "on-disk" to VaultRef.Unusable,
        )

        runForm({ TemplateVariableFields(v, autoFocus = false) }) {
            // Three different reasons a run cannot proceed: "no such entry", "the name matches more
            // than one" and "that entry has nothing to insert" are not the same sentence.
            onNode(polite).assertContentDescriptionEquals(
                string(Res.string.lib_snippet_vars_vault_missing, "ghost") + ". " +
                    string(Res.string.lib_snippet_vars_vault_ambiguous, "twins") + ". " +
                    string(Res.string.lib_snippet_vars_vault_unusable, "on-disk"),
            )
        }
    }
}
