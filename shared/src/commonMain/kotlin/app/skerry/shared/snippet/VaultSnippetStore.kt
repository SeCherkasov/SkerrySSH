package app.skerry.shared.snippet

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.serialization.json.Json

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

    override fun all(): List<Snippet> {
        if (!vault.isUnlocked) return emptyList()
        return vault.records()
            .filter { it.type == RecordType.SNIPPET && !it.deleted }
            .mapNotNull { decode(vault.openPayload(it.id)) }
    }

    override fun put(snippet: Snippet) {
        vault.put(snippet.id, RecordType.SNIPPET, encode(snippet))
    }

    override fun remove(id: String) {
        vault.remove(id)
    }

    private fun encode(snippet: Snippet): ByteArray = json.encodeToString(snippet).encodeToByteArray()

    private fun decode(payload: ByteArray?): Snippet? =
        payload?.let { runCatching { json.decodeFromString<Snippet>(it.decodeToString()) }.getOrNull() }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
