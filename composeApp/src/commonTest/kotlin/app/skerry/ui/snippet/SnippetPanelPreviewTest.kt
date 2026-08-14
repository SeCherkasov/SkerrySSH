package app.skerry.ui.snippet

import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetTemplate
import app.skerry.shared.snippet.SnippetVariableKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetPanelPreviewTest {

    private val environment = SnippetRunEnvironment(
        moment = SnippetMoment(year = 2026, month = 8, day = 2, hour = 9, minute = 12, second = 0, epochSeconds = 1_785_000_000L),
        newUuid = { "fixed-uuid" },
        randomChars = { n, _ -> "r".repeat(n) },
    )

    private fun preview(command: String, params: Map<String, String> = emptyMap()): String {
        val segments = SnippetTemplate.parse(command)
        return snippetPanelPreview(segments, SnippetTemplate.machineValues(segments, environment), params, clipboardLabel = "<clipboard>")
    }

    @Test
    fun a_filled_parameter_is_spliced_in() {
        assertEquals(
            "journalctl -u nginx -n 200 -f",
            preview("journalctl -u \${{service}} -n \${{lines}} -f", mapOf("service" to "nginx", "lines" to "200")),
        )
    }

    @Test
    fun an_empty_parameter_keeps_its_placeholder_instead_of_a_hole() {
        // Splicing "" would show `journalctl -u  -n` and read like a runnable command that isn't.
        assertEquals("journalctl -u \${{service}} -f", preview("journalctl -u \${{service}} -f"))
    }

    @Test
    fun a_vault_reference_is_masked_and_never_read_here() {
        assertEquals("mysql -p$SECRET_MASK", preview("mysql -p\${{vault:db-root}}"))
    }

    @Test
    fun the_clipboard_shows_as_a_label_rather_than_its_contents() {
        assertEquals("echo <clipboard>", preview("echo \${{clipboard}}"))
    }

    @Test
    fun machine_values_are_resolved_like_a_real_run() {
        assertEquals("backup-2026-08-02.tar", preview("backup-\${{date}}.tar"))
    }

    @Test
    fun panel_variables_list_parameters_first_then_the_resolved_kinds() {
        val vars = snippetPanelVariables(
            SnippetTemplate.parse("psql -U \${{user}} -h \${{host}} -p\${{vault:db}} \${{clipboard}} \${{date}}"),
        )

        assertEquals(listOf("user", "host", "db", "clipboard"), vars.map { it.name })
        assertEquals(listOf(true, true, false, false), vars.map { it.editable })
        assertEquals(SnippetVariableKind.VAULT, vars[2].kind)
    }

    @Test
    fun a_parameter_used_twice_is_prompted_once() {
        val vars = snippetPanelVariables(SnippetTemplate.parse("systemctl restart \${{svc}} && systemctl status \${{svc}}"))

        assertEquals(listOf("svc"), vars.map { it.name })
    }
}
