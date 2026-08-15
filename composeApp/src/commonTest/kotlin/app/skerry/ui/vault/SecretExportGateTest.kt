package app.skerry.ui.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The join issue #218 was missing: a private key must not reach the disk until the user has
 * re-authenticated. `privateKeyExport` being correct and `SecretCopyAuthorizer` being correct prove
 * nothing on their own — the bug was in the wiring between them, so the wiring is what this pins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecretExportGateTest {

    private val key = SecretExport.PrivateKey("id_ed25519.pem", "-----BEGIN OPENSSH PRIVATE KEY-----")
    private val cert = SecretExport.Public("id_ed25519-cert.pub", "ssh-ed25519-cert-v01@openssh.com AAAA")

    @Test
    fun `nothing is written until the master password is confirmed`() = runTest {
        val written = mutableListOf<String>()
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { written += it.fileName; ExportOutcome.Saved }, onSaved = {}) {}
        advanceUntilIdle()

        assertTrue(written.isEmpty(), "the key reached disk without authorization: $written")

        auth.submitPassword("master")
        advanceUntilIdle()
        assertEquals(listOf("id_ed25519.pem"), written)
    }

    @Test
    fun `a refused authorization writes nothing at all`() = runTest {
        val written = mutableListOf<String>()
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { written += it.fileName; ExportOutcome.Saved }, onSaved = {}) {}
        auth.dismiss()
        auth.submitPassword("master")
        advanceUntilIdle()

        assertTrue(written.isEmpty(), "a dismissed prompt still exported: $written")
    }

    @Test
    fun `the certificate is written without a password check`() = runTest {
        // Public material, like the Copy button beside it. If this ever starts demanding the master
        // password, the two actions have been wired to the wrong helper.
        val written = mutableListOf<String>()

        var reported: ExportOutcome? = null
        exportPublic(cert, scope = this, write = { written += it.fileName; ExportOutcome.Saved }) { reported = it }
        advanceUntilIdle()

        assertEquals(listOf("id_ed25519-cert.pub"), written)
        assertEquals(ExportOutcome.Saved, reported)
    }

    @Test
    fun `a write that throws is reported as failed and does not kill the screen's scope`() = runTest {
        // The picker itself can throw before any byte is written — ActivityNotFoundException when no
        // document provider handles the intent, and worse on some OEM ROMs. Escaping into the shared
        // scope would cancel it, and every later action on that screen (copy password, generate key)
        // would then be dropped in silence.
        var reported: ExportOutcome? = null
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { error("no document provider") }, onSaved = {}) { reported = it }
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(ExportOutcome.Failed, reported)

        var laterWorkRan = false
        launch { laterWorkRan = true }
        advanceUntilIdle()
        assertTrue(laterWorkRan, "the scope was cancelled — later actions on this screen are dead")
    }

    @Test
    fun `a cancelled export is not reported as a failure`() = runTest {
        // The composition going away is not something to interrupt the user about. Pinned at the
        // action, not at guardedExport: an inline runCatching here would collapse cancellation into
        // "Export failed" and pop that dialog every time the sheet closed mid-write.
        var reported: ExportOutcome? = null
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { throw CancellationException("screen left") }, onSaved = {}) {
            reported = it
        }
        auth.submitPassword("master")
        runCatching { advanceUntilIdle() }

        assertNull(reported, "a cancelled export must not be reported as an outcome")
    }

    @Test
    fun `a failed certificate write is reported too`() = runTest {
        var reported: ExportOutcome? = null

        exportPublic(cert, scope = this, write = { ExportOutcome.Failed }) { reported = it }
        advanceUntilIdle()

        assertEquals(ExportOutcome.Failed, reported)
    }

    @Test
    fun `a failed write is reported to the caller`() = runTest {
        var reported: ExportOutcome? = null
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { ExportOutcome.Failed }, onSaved = {}) { reported = it }
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(ExportOutcome.Failed, reported)
        assertTrue(reported!!.worthReporting)
    }

    @Test
    fun `a saved export is recorded inside the authorized action`() = runTest {
        // The audit trail must say what actually happened: a record made at the button press would
        // log an export that a dismissed prompt then never performs.
        var recorded = 0
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { ExportOutcome.Saved }, onSaved = { recorded++ }) {}
        advanceUntilIdle()
        assertEquals(0, recorded, "recorded before the user re-authenticated")

        auth.submitPassword("master")
        advanceUntilIdle()
        assertEquals(1, recorded)
    }

    @Test
    fun `a cancelled save-as records no export`() = runTest {
        var recorded = 0
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { ExportOutcome.Cancelled }, onSaved = { recorded++ }) {}
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(0, recorded, "a closed Save-As dialog wrote nothing, yet an export was recorded")
    }

    @Test
    fun `a failed write records no export`() = runTest {
        var recorded = 0
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { ExportOutcome.Failed }, onSaved = { recorded++ }) {}
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(0, recorded, "a failed write left no file, yet an export was recorded")
    }

    @Test
    fun `an audit hook that throws still reports the outcome and leaves the scope alive`() = runTest {
        // The audit write is best-effort: a full disk under the security log must not suppress the
        // export's own outcome (the file IS on disk) and must not cancel the screen's shared scope —
        // that would silently kill every later action on the panel.
        var reported: ExportOutcome? = null
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(
            auth, key, scope = this,
            write = { ExportOutcome.Saved },
            onSaved = { error("audit log: disk full") },
        ) { reported = it }
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(ExportOutcome.Saved, reported)

        var laterWorkRan = false
        launch { laterWorkRan = true }
        advanceUntilIdle()
        assertTrue(laterWorkRan, "the scope was cancelled — later actions on this screen are dead")
    }

    @Test
    fun `a write that lands while the screen is torn down still records and reports`() = runTest {
        // The platform writers finish the write on another dispatcher; withContext's prompt
        // cancellation would discard their Saved at the resume into a cancelled screen scope —
        // leaving the key on disk with no audit record. The write-plus-audit stretch runs under
        // NonCancellable in the caller, so a completed write always delivers its outcome.
        var recorded = 0
        var reported: ExportOutcome? = null
        val screenScope = CoroutineScope(coroutineContext + Job())
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(
            auth, key, scope = screenScope,
            write = {
                // Mimic the platform shape: the write completes on its own dispatcher under
                // NonCancellable while the screen's scope dies mid-flight.
                withContext(StandardTestDispatcher(testScheduler) + NonCancellable) {
                    screenScope.cancel()
                    ExportOutcome.Saved
                }
            },
            onSaved = { recorded++ },
        ) { reported = it }
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(1, recorded, "the key is on disk — its audit record must not be lost to the teardown")
        assertEquals(ExportOutcome.Saved, reported)
    }

    @Test
    fun `a dismissed prompt records no export`() = runTest {
        var recorded = 0
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { ExportOutcome.Saved }, onSaved = { recorded++ }) {}
        auth.dismiss()
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(0, recorded)
    }

    @Test
    fun `a cancelled save-as is reported but not worth a dialog`() = runTest {
        var reported: ExportOutcome? = null
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { ExportOutcome.Cancelled }, onSaved = {}) { reported = it }
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(ExportOutcome.Cancelled, reported)
        assertTrue(!reported!!.worthReporting)
    }

}
