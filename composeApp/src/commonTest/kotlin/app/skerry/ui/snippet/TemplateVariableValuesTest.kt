package app.skerry.ui.snippet

import androidx.compose.runtime.mutableStateMapOf
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetVariableKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PUBLIC_LINE = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI temp"

class TemplateVariableValuesTest {

    private fun values(params: Map<String, String>) = TemplateVariableValues(
        paramNames = params.keys.toList(),
        paramChoices = emptyMap(),
        vaultRefs = emptyList(),
        needsClipboard = false,
        params = mutableStateMapOf<String, String>().apply { putAll(params) },
    )

    private fun vaultValues(vararg refs: String) = TemplateVariableValues(
        paramNames = emptyList(),
        paramChoices = emptyMap(),
        vaultRefs = refs.toList(),
        needsClipboard = false,
        params = mutableStateMapOf(),
    )

    private fun vaultVariable(name: String) =
        SnippetSegment.Variable(SnippetVariableKind.VAULT, "vault", name, "\${{vault:$name}}")

    @Test
    fun a_parameter_is_seeded_until_it_is_edited() {
        val v = values(mapOf("host" to "prod-01"))
        assertTrue(v.isSeeded("host")) // the template's default — the field may select it on focus
        v.params["host"] = "prod-02"
        assertFalse(v.isSeeded("host"))
    }

    @Test
    fun typing_the_seeded_value_back_re_arms_the_field() {
        val v = values(mapOf("host" to "prod-01"))
        v.params["host"] = "prod-02"
        v.params["host"] = "prod-01"
        // Deliberate: the rule is "the value the form put there", by content — not an edited flag.
        // A parameter typed back to the default is indistinguishable from an untouched one.
        assertTrue(v.isSeeded("host"))
    }

    @Test
    fun a_parameter_with_no_default_is_seeded_while_still_empty() {
        val v = values(mapOf("port" to ""))
        assertTrue(v.isSeeded("port"))
        v.params["port"] = "8080"
        assertFalse(v.isSeeded("port"))
    }

    @Test
    fun a_vault_reference_blocks_the_run_until_it_is_resolved() {
        val v = vaultValues("temp_pubkey")
        // Deriving a public half parses a PEM off the composition thread: until it lands, the dialog
        // has no value to send, and "not answered yet" must not read as "resolved".
        assertFalse(v.canRun)

        v.vaultResolutions = mapOf("temp_pubkey" to VaultRef.Ok(PUBLIC_LINE, secret = false))

        assertTrue(v.canRun)
    }

    @Test
    fun a_missing_or_unusable_entry_keeps_the_run_blocked() {
        val v = vaultValues("gone", "on-disk")
        v.vaultResolutions = mapOf("gone" to VaultRef.Missing, "on-disk" to VaultRef.Unusable)

        assertFalse(v.canRun)
    }

    @Test
    fun a_password_is_masked_in_the_preview_and_sent_in_clear() {
        val v = vaultValues("prod-db")
        v.vaultResolutions = mapOf("prod-db" to VaultRef.Ok("s3cret", secret = true))
        val variable = vaultVariable("prod-db")

        assertEquals(SECRET_MASK, v.value(variable, masked = true))
        assertEquals("s3cret", v.value(variable, masked = false))
        // The guard's later confirmation masks the same span this preview did.
        assertEquals(listOf("s3cret"), v.vaultSecrets())
    }

    @Test
    fun the_masked_span_is_the_one_that_reaches_the_line() {
        val v = vaultValues("prod-db")
        // A password may hold anything the vault accepted. The line is assembled through
        // sanitizeSnippetValue, so a tab arrives as a space — and the guard's exact replace would
        // walk straight past the raw string and print the password in its quote.
        v.vaultResolutions = mapOf("prod-db" to VaultRef.Ok("pass\tword", secret = true))

        assertEquals(listOf("pass word"), v.vaultSecrets())
    }

    @Test
    fun a_public_key_reads_the_same_in_the_preview_and_is_not_a_secret_to_mask() {
        val v = vaultValues("temp_pubkey")
        v.vaultResolutions = mapOf("temp_pubkey" to VaultRef.Ok(PUBLIC_LINE, secret = false))
        val variable = vaultVariable("temp_pubkey")

        assertEquals(PUBLIC_LINE, v.value(variable, masked = true))
        assertEquals(PUBLIC_LINE, v.value(variable, masked = false))
        // Public material must not travel to the production guard as a span to hide: masking it would
        // blank out the one part of the quoted line that says which key is being authorized.
        assertEquals(emptyList(), v.vaultSecrets())
    }
}
