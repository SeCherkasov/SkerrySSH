package app.skerry.ui.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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

        exportPrivateKey(auth, key, scope = this, write = { written += it.fileName; ExportOutcome.Saved }) {}
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

        exportPrivateKey(auth, key, scope = this, write = { written += it.fileName; ExportOutcome.Saved }) {}
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

        exportPrivateKey(auth, key, scope = this, write = { error("no document provider") }) { reported = it }
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

        exportPrivateKey(auth, key, scope = this, write = { throw CancellationException("screen left") }) {
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

        exportPrivateKey(auth, key, scope = this, write = { ExportOutcome.Failed }) { reported = it }
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(ExportOutcome.Failed, reported)
        assertTrue(reported!!.worthReporting)
    }

    @Test
    fun `a cancelled save-as is reported but not worth a dialog`() = runTest {
        var reported: ExportOutcome? = null
        val auth = SecretCopyAuthorizer(
            FakeUnlockedVault("master"), biometrics = null, scope = this,
            kdfDispatcher = StandardTestDispatcher(testScheduler),
        )

        exportPrivateKey(auth, key, scope = this, write = { ExportOutcome.Cancelled }) { reported = it }
        auth.submitPassword("master")
        advanceUntilIdle()

        assertEquals(ExportOutcome.Cancelled, reported)
        assertTrue(!reported!!.worthReporting)
    }

}
