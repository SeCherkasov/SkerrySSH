package app.skerry.shared.vault

import app.skerry.shared.host.HostStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One-time migration collapsing the Vault model from two levels (host → account → secret) to one
 * (host → secret). Before the collapse, an [RecordType.IDENTITY] record stored either a raw secret
 * (`{id, label, auth}`) or an account wrapper (`{id, label, username, credentialId}`), and
 * [app.skerry.shared.host.Host.identityId] pointed to one of them. After: secrets live in the
 * keychain ([RecordType.CREDENTIAL], [Credential]), and the host references them directly via
 * `credentialId`.
 *
 * The migration is self-detecting and idempotent (no separate flag). Safe to call on every unlock.
 * Requires an unlocked [Vault]; hosts live in a separate [HostStore], so the migration is
 * cross-store: secrets go IDENTITY→CREDENTIAL (keeping the id), account wrappers collapse into the
 * host referencing the secret directly, then the wrapper records are removed.
 */
class VaultMigration(
    private val vault: Vault,
    private val hostStore: HostStore,
) {

    /** Runs the migration; returns `true` if anything changed (for logs/tests). */
    fun migrate(): Boolean {
        var changed = false

        // a) Old raw IDENTITY secrets → CREDENTIAL (same id: hosts still reference it). Account
        //    wrappers (username/credentialId fields, no auth) don't decode as this and are skipped.
        for (record in vault.records()) {
            if (record.type != RecordType.IDENTITY || record.deleted) continue
            val legacy = decodeSecret(vault.openPayload(record.id)) ?: continue
            vault.put(record.id, RecordType.CREDENTIAL, encodeCredential(Credential(record.id, legacy.label, legacy.auth)))
            changed = true
        }

        // Live keychain secrets after step (a) — only these ids are valid rebind targets for a host.
        val liveCredentials = vault.records()
            .filter { it.type == RecordType.CREDENTIAL && !it.deleted }
            .mapTo(mutableSetOf()) { it.id }

        // b) Account wrappers → their credentialId, but only if the target secret is actually live.
        //    A wrapper whose secret is missing (corruption/partial run) is not collapsed: avoids
        //    creating a dangling reference and destroying the only witness of the link.
        val wrapper = mutableMapOf<String, String>()
        for (record in vault.records()) {
            if (record.type != RecordType.IDENTITY || record.deleted) continue
            val account = decodeAccount(vault.openPayload(record.id)) ?: continue
            if (account.credentialId in liveCredentials) wrapper[record.id] = account.credentialId
        }

        // c) Rebind hosts onto the LIVE secret: legacy → wrapper.credentialId, or legacy itself if
        //    it's already a live secret's id (interrupted run/direct reference). If it doesn't
        //    resolve to a live secret, unlink it (credentialId=null, will prompt for password) —
        //    fail-safe instead of a dangling reference.
        for (host in hostStore.all()) {
            val legacy = host.identityId ?: continue
            val credId = (wrapper[legacy] ?: legacy).takeIf { it in liveCredentials }
            hostStore.put(host.copy(credentialId = credId, identityId = null))
            changed = true
        }

        // d) Remove only the collapsed wrappers (their target is live, hosts are rebound).
        for (id in wrapper.keys) {
            vault.remove(id)
            changed = true
        }

        return changed
    }

    private fun encodeCredential(c: Credential): ByteArray =
        json.encodeToString(Credential.serializer(), c).encodeToByteArray()

    // Old secret format: {id,label,auth} → migrates to CREDENTIAL. A wrapper (no auth) won't decode
    // as this thanks to the required auth field. Extra discriminator: a record with credentialId is
    // an account, not a secret (even if it happened to have auth), and is NOT converted to CREDENTIAL.
    private fun decodeSecret(payload: ByteArray?): LegacySecret? =
        payload?.let { runCatching { json.decodeFromString(LegacySecret.serializer(), it.decodeToString()) }.getOrNull() }
            ?.takeIf { it.credentialId == null }

    // Account wrapper: {id,label,username,credentialId}. A raw secret (no credentialId) won't decode as this.
    private fun decodeAccount(payload: ByteArray?): LegacyAccount? =
        payload?.let { runCatching { json.decodeFromString(LegacyAccount.serializer(), it.decodeToString()) }.getOrNull() }

    /**
     * Former shape of an IDENTITY record (raw secret). [auth] shares wire names with
     * [CredentialSecret]. [credentialId] is a discriminator: a real secret doesn't have one;
     * its presence means an account wrapper (parsed by [LegacyAccount]) — [decodeSecret] rejects such a record.
     */
    @Serializable
    private data class LegacySecret(
        val id: String,
        val label: String,
        @SerialName("auth") val auth: CredentialSecret,
        val credentialId: String? = null,
    )

    /** Former shape of an IDENTITY record (account wrapper): username + reference to a keychain secret. */
    @Serializable
    private data class LegacyAccount(
        val id: String,
        val label: String,
        val username: String,
        val credentialId: String,
    )

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
