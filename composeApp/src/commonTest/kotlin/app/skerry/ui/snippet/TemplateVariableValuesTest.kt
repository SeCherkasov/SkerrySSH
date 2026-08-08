package app.skerry.ui.snippet

import androidx.compose.runtime.mutableStateMapOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateVariableValuesTest {

    private fun values(params: Map<String, String>) = TemplateVariableValues(
        paramNames = params.keys.toList(),
        vaultRefs = emptyList(),
        needsClipboard = false,
        vaultResolutions = emptyMap(),
        params = mutableStateMapOf<String, String>().apply { putAll(params) },
    )

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
}
