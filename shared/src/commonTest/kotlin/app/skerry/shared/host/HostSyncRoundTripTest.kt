package app.skerry.shared.host

import app.skerry.shared.vault.FileVault
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A profile edited on one device must arrive intact on another: sync ships the record's sealed blob
 * verbatim (zero-knowledge — the server never sees fields), so anything the JSON payload doesn't
 * carry is lost fleet-wide. Exercised end to end over the real vault: two devices sharing a data
 * key, one seals the profile, the other merges the record and reads it back through its own store.
 */
class HostSyncRoundTripTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val fs = FakeFileSystem()

    private fun vault(name: String, deviceId: String) =
        FileVault("/$name.json".toPath(), crypto, deviceId = deviceId, fileSystem = fs, now = { TS })

    /** libsodium needs async init before first use; init is idempotent. */
    private fun vaultTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    @Test
    fun `notes reach another device through a record merge`() = vaultTest {
        val a = vault("vault-a", "device-1").apply { create("master".toCharArray()) }
        val b = vault("vault-b", "device-2").apply { createWithDataKey(a.exportDataKey()!!) }

        val note = "reboot window: Sun 03:00 MSK\nask ops before touching it"
        VaultHostStore(a).put(
            Host(id = "h1", label = "prod-web-01", address = "10.0.0.5", username = "root", notes = note),
        )

        val merged = b.mergeRemote(a.records())

        assertTrue(merged.rejected.isEmpty())
        val synced = VaultHostStore(b).all().single { it.id == "h1" }
        assertEquals(note, synced.notes)
        assertEquals("prod-web-01", synced.label) // the rest of the profile came along unchanged
    }

    @Test
    fun `clearing a note propagates instead of leaving the old one on the other device`() = vaultTest {
        val a = vault("vault-c", "device-1").apply { create("master".toCharArray()) }
        val b = vault("vault-d", "device-2").apply { createWithDataKey(a.exportDataKey()!!) }
        val storeA = VaultHostStore(a)
        val host = Host(id = "h1", label = "web", address = "10.0.0.5", username = "root", notes = "stale note")
        storeA.put(host)
        b.mergeRemote(a.records())

        // Emptying the field on device A must overwrite (not merely omit) the note on device B.
        storeA.put(host.copy(notes = null))
        val merged = b.mergeRemote(a.records())

        assertTrue(merged.rejected.isEmpty())
        assertNull(VaultHostStore(b).all().single { it.id == "h1" }.notes)
    }

    private companion object {
        const val TS = "2026-06-12T00:00:00Z"
    }
}
