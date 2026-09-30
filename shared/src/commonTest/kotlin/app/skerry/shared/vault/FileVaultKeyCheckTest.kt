package app.skerry.shared.vault

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FileVault.unlockWithDataKey] against a key that is not the vault's own. The path exists for keys
 * held outside the vault — a biometric or OS-keyring wrapper, a team key — and every one of those can
 * go stale: the account key replaced by sync, the vault reset and created anew, a team key rotated.
 * Accepting a stale key opens a vault whose records don't decrypt, and whatever is written next is
 * sealed under a key the password no longer unwraps.
 */
class FileVaultKeyCheckTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val fs = FakeFileSystem()
    private val file: Path = "/vault.json".toPath()
    private val json = Json { ignoreUnknownKeys = true }

    private fun vault() = FileVault(file, crypto, deviceId = "device-1", fileSystem = fs, now = { TS })

    private fun keyTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    private fun copyOf(key: DataKey) = DataKey(key.bytes.copyOf())

    @Test
    fun `a key that is not the vault's own is refused and the vault stays locked`() = keyTest {
        vault().apply { create("master".toCharArray()); lock() }

        val v = vault()
        assertEquals(UnlockResult.WrongPassword, v.unlockWithDataKey(crypto.newDataKey()))
        assertFalse(v.isUnlocked)
    }

    @Test
    fun `the vault's own key still unlocks it`() = keyTest {
        val key = vault().run { create("master".toCharArray()); exportDataKey()!!.also { lock() } }

        val v = vault()
        assertEquals(UnlockResult.Success, v.unlockWithDataKey(key))
        assertTrue(v.isUnlocked)
    }

    @Test
    fun `the key a sync adoption replaced no longer unlocks the vault`() = keyTest {
        val v = vault().apply { create("master".toCharArray()) }
        val old = v.exportDataKey()!!
        val adopted = crypto.newDataKey()
        assertTrue(v.adoptDataKey(copyOf(adopted), "master".toCharArray()))
        v.lock()

        assertEquals(UnlockResult.WrongPassword, vault().unlockWithDataKey(old))
        assertEquals(UnlockResult.Success, vault().unlockWithDataKey(adopted))
    }

    @Test
    fun `the key of a vault that was reset does not open the one created after it`() = keyTest {
        val first = vault().run { create("master".toCharArray()); exportDataKey()!!.also { reset() } }
        vault().apply { create("another".toCharArray()); lock() }

        assertEquals(UnlockResult.WrongPassword, vault().unlockWithDataKey(first))
    }

    @Test
    fun `a password change keeps the key accepted`() = keyTest {
        val v = vault().apply { create("master".toCharArray()) }
        val key = v.exportDataKey()!!
        assertTrue(v.changePassword("master".toCharArray(), "changed".toCharArray()))
        v.lock()

        assertEquals(UnlockResult.Success, vault().unlockWithDataKey(key))
    }

    @Test
    fun `re-keyed records leave the old key refused`() = keyTest {
        val oldKey = crypto.newDataKey()
        val oldCopy = copyOf(oldKey)
        vault().apply {
            createWithDataKey(oldKey)
            put("h1", RecordType.HOST, "payload".encodeToByteArray())
            assertTrue(rekeyRecords(crypto.newDataKey()))
            lock()
        }

        assertEquals(UnlockResult.WrongPassword, vault().unlockWithDataKey(oldCopy))
    }

    @Test
    fun `a vault written before the check existed still opens by key, and gains the check on password unlock`() = keyTest {
        val key = vault().run {
            create("master".toCharArray())
            put("h1", RecordType.HOST, "payload".encodeToByteArray())
            exportDataKey()!!.also { lock() }
        }
        stripKeyCheck()

        // No check on file: the key can't be told apart, so it is taken as before.
        assertEquals(UnlockResult.Success, vault().unlockWithDataKey(copyOf(key)))

        // A password unlock proves the key, so it writes the check down…
        vault().apply { assertEquals(UnlockResult.Success, unlock("master".toCharArray())); lock() }
        assertTrue(metaOnDisk().containsKey("keyCheck"))
        // …and from then on a foreign key is refused while the real one still opens the records.
        assertEquals(UnlockResult.WrongPassword, vault().unlockWithDataKey(crypto.newDataKey()))
        val reopened = vault()
        assertEquals(UnlockResult.Success, reopened.unlockWithDataKey(key))
        assertContentEquals("payload".encodeToByteArray(), reopened.openPayload("h1"))
    }

    private fun metaOnDisk(): JsonObject =
        json.parseToJsonElement(fs.read(file) { readUtf8() }).jsonObject.getValue("meta").jsonObject

    /** Rewrites the file the way a pre-check version of the app left it: same meta, no `keyCheck`. */
    private fun stripKeyCheck() {
        val root = json.parseToJsonElement(fs.read(file) { readUtf8() }).jsonObject
        val meta = JsonObject(metaOnDisk() - "keyCheck")
        val rewritten = buildJsonObject {
            root.forEach { (name, value) -> put(name, if (name == "meta") meta else value) }
        }
        fs.write(file) { writeUtf8(rewritten.toString()) }
    }

    private companion object {
        const val TS = "2026-09-30T00:00:00Z"
    }
}
