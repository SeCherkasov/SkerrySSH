package app.skerry.shared.snippet

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec

/**
 * [SnippetStore] over an encrypted [Vault]: each snippet is a [RecordType.SNIPPET] record whose
 * payload is the JSON serialization of [Snippet]. Commands may contain inline credentials, so they
 * get the same encryption and E2E sync as other secrets.
 *
 * Snippets have no defined order (the interface has set semantics), so no separate order record
 * is needed; entries come back in [Vault.records] order. Reading a locked vault returns an empty
 * list; a corrupt payload is silently skipped.
 */
class VaultSnippetStore(private val vault: Vault) : SnippetStore {

    private val codec = VaultRecordCodec(vault, RecordType.SNIPPET, Snippet.serializer())

    override fun all(): List<Snippet> {
        if (!vault.isUnlocked) return emptyList()
        return codec.list()
    }

    override fun put(snippet: Snippet) {
        codec.put(snippet.id, snippet)
    }

    override fun remove(id: String) {
        codec.remove(id)
    }
}
