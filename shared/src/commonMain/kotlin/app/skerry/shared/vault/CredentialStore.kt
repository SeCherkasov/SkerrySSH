package app.skerry.shared.vault

/**
 * Store for [Credential] keychain secrets over a [Vault]: each secret is a [RecordType.CREDENTIAL]
 * record whose payload is a JSON serialization of [Credential] (label and secret inside the
 * encrypted blob). Pure common logic over the [Vault] contract — no platform part.
 *
 * Requires an unlocked vault: CRUD on a locked one throws from [Vault] itself. Records whose
 * payload fails to decrypt or parse (corruption/incompatible migration) are silently skipped —
 * one broken record must not break the whole list.
 */
class CredentialStore(private val vault: Vault) {

    private val codec = VaultRecordCodec(vault, RecordType.CREDENTIAL, Credential.serializer())

    /** All live secrets (tombstones and other record types excluded). */
    fun all(): List<Credential> = codec.list()

    /** Secret by [id], or `null` if missing, deleted, or unreadable. */
    fun get(id: String): Credential? = codec.get(id)

    /** Create/update a secret (upsert by [Credential.id]). */
    fun put(credential: Credential) {
        codec.put(credential.id, credential)
    }

    /** Soft-delete a secret (tombstone). Hosts referencing it are reconciled in the UI layer. */
    fun remove(id: String) {
        codec.remove(id)
    }
}
