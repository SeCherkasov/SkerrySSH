package app.skerry.ui.vault

import app.skerry.shared.vault.CredentialStore
import app.skerry.shared.vault.CredentialUsage
import app.skerry.shared.vault.CredentialUsageLog
import app.skerry.shared.vault.SecurityEvent
import app.skerry.shared.vault.SecurityEventType
import app.skerry.shared.vault.SecurityLog
import app.skerry.ui.identity.CredentialManagerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The audit half of issue #221: a saved key export must land in both places a user looks after
 * something suspicious — the secret's own usage trail and the security event log — and the security
 * event must carry the credential id, never the label (the vault treats labels as secret).
 * [keyExportAudit] is the one callback both screens hand to [exportPrivateKey], so this is the
 * wiring test for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyExportAuditTest {

    private val key = SecretExport.PrivateKey("id_ed25519.pem", "-----BEGIN OPENSSH PRIVATE KEY-----")

    private fun controller(usage: CredentialUsageLog) = CredentialManagerController(
        CredentialStore(FakeUnlockedVault("master")),
        usage = usage,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ) { "gen" }

    @Test
    fun `a saved export lands in the usage trail and the security log, id only`() = runTest {
        val usage = RecordingUsageLog()
        val securityLog = ExportRecordingSecurityLog()
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(
            auth, key, scope = this,
            write = { ExportOutcome.Saved },
            onSaved = keyExportAudit(controller(usage), securityLog, "cred-1"),
        ) {}
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(listOf("exported:cred-1"), usage.events)
        assertEquals(listOf<Pair<SecurityEventType, String?>>(SecurityEventType.KeyExported to "cred-1"), securityLog.records)
    }

    @Test
    fun `a cancelled save-as leaves both logs untouched`() = runTest {
        val usage = RecordingUsageLog()
        val securityLog = ExportRecordingSecurityLog()
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(
            auth, key, scope = this,
            write = { ExportOutcome.Cancelled },
            onSaved = keyExportAudit(controller(usage), securityLog, "cred-1"),
        ) {}
        auth.submitPassword("master")
        advanceUntilIdle()

        assertTrue(usage.events.isEmpty(), "no file was written, yet the trail says otherwise: ${usage.events}")
        assertTrue(securityLog.records.isEmpty())
    }

    @Test
    fun `a missing security log does not stop the usage trail`() = runTest {
        val usage = RecordingUsageLog()
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(
            auth, key, scope = this,
            write = { ExportOutcome.Saved },
            onSaved = keyExportAudit(controller(usage), securityLog = null, credentialId = "cred-1"),
        ) {}
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(listOf("exported:cred-1"), usage.events)
    }
}

/** Usage log that records the calls themselves; only the export event matters here. */
private class RecordingUsageLog : CredentialUsageLog {
    val events = mutableListOf<String>()
    private val entries = mutableMapOf<String, CredentialUsage>()

    override fun of(credentialId: String): CredentialUsage? = entries[credentialId]
    override fun all(): List<CredentialUsage> = entries.values.toList()
    override fun recordAdded(credentialId: String): CredentialUsage = store(credentialId) { it }
    override fun recordChanged(credentialId: String): CredentialUsage = store(credentialId) { it }
    override fun recordUsed(credentialId: String): CredentialUsage = store(credentialId) { it }
    override fun recordCopied(credentialId: String): CredentialUsage = store(credentialId) { it }

    override fun recordExported(credentialId: String): CredentialUsage {
        events += "exported:$credentialId"
        return store(credentialId) { it.copy(exportedAt = it.exportedAt + "t") }
    }

    override fun forget(credentialId: String) { entries -= credentialId }
    override fun clear() = entries.clear()

    private fun store(id: String, edit: (CredentialUsage) -> CredentialUsage): CredentialUsage =
        edit(entries[id] ?: CredentialUsage(id)).also { entries[id] = it }
}

/** Security log that keeps what was recorded — type and detail — so the test asserts on the pair. */
private class ExportRecordingSecurityLog : SecurityLog {
    val records = mutableListOf<Pair<SecurityEventType, String?>>()

    override fun record(type: SecurityEventType, detail: String?) { records += type to detail }
    override fun recent(limit: Int): List<SecurityEvent> = emptyList()
    override fun lastPasswordChangeAt(): String? = null
    override fun clear() = records.clear()
}
