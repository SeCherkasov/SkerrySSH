package app.skerry.ui.vault

import app.skerry.shared.snippet.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnippetVaultUsageTest {

    private fun snippet(id: String, command: String) = Snippet(id = id, label = "s-$id", command = command)

    @Test
    fun `snippetsUsing finds exact vault references by name`() {
        val snippets = listOf(
            snippet("1", "mysqldump -p${'$'}{{vault:prod-db}} db"),
            snippet("2", "echo ${'$'}{{vault:other}}"),
            snippet("3", "plain command"),
            snippet("4", "a ${'$'}{{vault:prod-db}} b ${'$'}{{date}}"),
        )

        assertEquals(listOf("1", "4"), VaultPresentation.snippetsUsing("prod-db", snippets).map { it.id })
    }

    @Test
    fun `snippetsUsing ignores non-vault variables and partial matches`() {
        val snippets = listOf(
            snippet("1", "echo ${'$'}{{clipboard}} ${'$'}{{prod-db}}"), // param named like the entry
            snippet("2", "echo ${'$'}{{vault:prod-db-replica}}"),      // different entry
        )

        assertTrue(VaultPresentation.snippetsUsing("prod-db", snippets).isEmpty())
    }
}
