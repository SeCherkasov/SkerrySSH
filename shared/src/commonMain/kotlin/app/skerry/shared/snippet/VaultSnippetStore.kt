package app.skerry.shared.snippet

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecordCodec

/**
 * [SnippetStore] поверх зашифрованного [Vault]: каждый сниппет — запись [RecordType.SNIPPET], чей
 * payload — JSON-сериализация [Snippet]. Команды могут содержать inline-креды, поэтому теперь они
 * под тем же шифрованием и E2E-синком, что и секреты (Phase A). По образцу
 * [app.skerry.shared.vault.CredentialStore].
 *
 * Порядка у сниппетов нет (интерфейс — set-семантика), поэтому отдельная запись-макет не нужна:
 * отдаём в порядке [Vault.records]. Чтение на залоченном vault — пустой список; битый payload
 * молча пропускается.
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
