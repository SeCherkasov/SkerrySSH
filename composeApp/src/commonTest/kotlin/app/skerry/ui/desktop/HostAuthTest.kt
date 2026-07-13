package app.skerry.ui.desktop

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import app.skerry.ui.identity.CredentialDraft
import app.skerry.ui.identity.CredentialKind
import app.skerry.ui.identity.CredentialManagerController
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Resolves host auth before connecting ([resolveHostAuth]); shared path for new tabs, split
 * panes, and the desktop "Run snippet on host" shell.
 */
class HostAuthTest {

    private fun host(
        credentialId: String? = null,
        type: ConnectionType = ConnectionType.SSH,
    ) = Host(
        id = "h1", label = "Prod", address = "10.0.0.1", username = "root",
        credentialId = credentialId, connectionType = type,
    )

    private fun credentials(id: String, password: String): CredentialManagerController {
        val controller = CredentialManagerController(CredentialStore(FakeAuthVault())) { id }
        controller.save(CredentialDraft(label = "cred", kind = CredentialKind.PASSWORD, password = password))
        return controller
    }

    @Test
    fun non_ssh_hosts_resolve_to_placeholder_auth_without_credentials() {
        assertEquals(
            HostAuthResolution.Resolved(SshAuth.Password("")),
            resolveHostAuth(host(type = ConnectionType.TELNET), credentials = null),
        )
        assertEquals(
            HostAuthResolution.Resolved(SshAuth.Password("")),
            resolveHostAuth(host(type = ConnectionType.SERIAL, credentialId = "c1"), credentials("c1", "unused")),
        )
    }

    @Test
    fun ssh_host_with_bound_credential_resolves_to_its_auth() {
        assertEquals(
            HostAuthResolution.Resolved(SshAuth.Password("s3cr3t")),
            resolveHostAuth(host(credentialId = "c1"), credentials("c1", "s3cr3t")),
        )
    }

    @Test
    fun ssh_host_without_binding_needs_password() {
        assertEquals(HostAuthResolution.NeedsPassword, resolveHostAuth(host(), credentials("c1", "pw")))
        assertEquals(HostAuthResolution.NeedsPassword, resolveHostAuth(host(credentialId = "missing"), credentials("c1", "pw")))
        assertEquals(HostAuthResolution.NeedsPassword, resolveHostAuth(host(credentialId = "c1"), credentials = null))
    }
}

/** Minimal in-memory [Vault] for [CredentialStore] (mirrors FakeCredVault in identity tests). */
private class FakeAuthVault : Vault {
    private val payloads = mutableMapOf<String, ByteArray>()
    private val records = mutableMapOf<String, VaultRecord>()

    override fun exists(): Boolean = true
    override val isUnlocked: Boolean = true
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun lock() = Unit
    override fun reset() { payloads.clear(); records.clear() }

    override fun records(): List<VaultRecord> = records.values.toList()
    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
    override fun openPayload(id: String): ByteArray? =
        records[id]?.takeIf { !it.deleted }?.let { payloads[id] }

    override fun put(id: String, type: RecordType, payload: ByteArray) {
        val version = (records[id]?.version ?: 0L) + 1
        records[id] = VaultRecord(id, type, version, "2026-06-12T00:00:00Z", "dev", deleted = false, blob = ByteArray(0))
        payloads[id] = payload
    }

    override fun remove(id: String) {
        records[id] = (records[id] ?: return).copy(version = records[id]!!.version + 1, deleted = true)
    }

    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = true
    override fun verifyPassword(password: CharArray): Boolean = true

    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Corrupted
    override fun exportDataKey(): DataKey? = null
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
}
