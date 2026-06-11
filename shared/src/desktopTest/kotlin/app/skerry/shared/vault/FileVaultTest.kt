package app.skerry.shared.vault

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты файлового vault используют настоящий [LibsodiumVaultCrypto] (Argon2id), а не фейк —
 * это честная интеграция стора и крипто. Деривация дорогая, поэтому пароли короткие, а число
 * unlock/create в каждом тесте минимально.
 */
class FileVaultTest {

    private val crypto: VaultCrypto = LibsodiumVaultCrypto()
    private val tempDir: Path = Files.createTempDirectory("skerry-vault")
    private val file: Path get() = tempDir.resolve("vault.json")
    private val json = Json { ignoreUnknownKeys = true }

    private fun vault() = FileVault(file, crypto, deviceId = "device-1")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun `create writes a file and leaves the vault unlocked`() {
        val v = vault()
        assertFalse(v.exists())

        v.create("master".toCharArray())

        assertTrue(v.exists())
        assertTrue(v.isUnlocked)
    }

    @Test
    fun `unlock with the correct password succeeds on a fresh instance`() {
        vault().create("master".toCharArray())

        assertEquals(UnlockResult.Success, vault().unlock("master".toCharArray()))
    }

    @Test
    fun `unlock with a wrong password is rejected`() {
        vault().create("master".toCharArray())

        assertEquals(UnlockResult.WrongPassword, vault().unlock("nope".toCharArray()))
    }

    @Test
    fun `unlock of a corrupted file reports Corrupted`() {
        file.writeText("{ this is not valid vault json")

        assertEquals(UnlockResult.Corrupted, vault().unlock("master".toCharArray()))
    }

    @Test
    fun `crud throws while the vault is locked`() {
        val v = vault()
        v.create("master".toCharArray())
        v.lock()

        assertFalse(v.isUnlocked)
        assertFailsWith<IllegalStateException> { v.records() }
        assertFailsWith<IllegalStateException> { v.put("a", RecordType.HOST, ByteArray(1)) }
        assertFailsWith<IllegalStateException> { v.openPayload("a") }
    }

    @Test
    fun `put then openPayload round-trips and persists across lock`() {
        val payload = "192.168.1.45 root".encodeToByteArray()
        vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, payload)
            lock()
        }

        val reopened = vault()
        assertEquals(UnlockResult.Success, reopened.unlock("master".toCharArray()))
        assertContentEquals(payload, reopened.openPayload("host-1"))
    }

    @Test
    fun `put with an existing id upserts and bumps version`() {
        val v = vault().apply { create("master".toCharArray()) }

        v.put("host-1", RecordType.HOST, "v1".encodeToByteArray())
        v.put("host-1", RecordType.HOST, "v2".encodeToByteArray())

        assertEquals(1, v.records().count { it.id == "host-1" })
        assertEquals(2L, v.records().first { it.id == "host-1" }.version)
        assertContentEquals("v2".encodeToByteArray(), v.openPayload("host-1"))
    }

    @Test
    fun `openPayload of an unknown id is null`() {
        val v = vault().apply { create("master".toCharArray()) }

        assertNull(v.openPayload("ghost"))
    }

    @Test
    fun `remove leaves a tombstone with a bumped version`() {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "data".encodeToByteArray())
        }

        v.remove("host-1")

        val record = v.records().first { it.id == "host-1" }
        assertTrue(record.deleted)
        assertEquals(2L, record.version)
    }

    @Test
    fun `remove of an already-deleted id does not bump version again`() {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "data".encodeToByteArray())
        }
        v.remove("host-1")
        val version = v.records().first { it.id == "host-1" }.version

        v.remove("host-1")

        assertEquals(version, v.records().first { it.id == "host-1" }.version)
    }

    @Test
    fun `put after remove resurrects the record`() {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "v1".encodeToByteArray())
        }
        v.remove("host-1")

        v.put("host-1", RecordType.HOST, "v2".encodeToByteArray())

        val record = v.records().first { it.id == "host-1" }
        assertFalse(record.deleted)
        assertEquals(3L, record.version)
        assertContentEquals("v2".encodeToByteArray(), v.openPayload("host-1"))
    }

    @Test
    fun `changePassword throws while the vault is locked`() {
        val v = vault().apply { create("master".toCharArray()); lock() }

        assertFailsWith<IllegalStateException> {
            v.changePassword("master".toCharArray(), "new".toCharArray())
        }
    }

    @Test
    fun `remove of an unknown id is a no-op`() {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "data".encodeToByteArray())
        }

        v.remove("ghost")

        assertEquals(1, v.records().size)
    }

    @Test
    fun `swapping record blobs on disk is rejected by AAD binding`() {
        vault().apply {
            create("master".toCharArray())
            put("a", RecordType.HOST, "payload-A".encodeToByteArray())
            put("b", RecordType.HOST, "payload-B".encodeToByteArray())
            lock()
        }

        val body = json.decodeFromString<VaultFileBody>(Files.readString(file))
        val a = body.records.first { it.id == "a" }
        val b = body.records.first { it.id == "b" }
        val tampered = body.copy(records = listOf(a.copy(blob = b.blob), b.copy(blob = a.blob)))
        Files.writeString(file, json.encodeToString(tampered))

        val v = vault()
        assertEquals(UnlockResult.Success, v.unlock("master".toCharArray()))
        // blob записи b под AAD слота a (и наоборот) — тег не проходит, payload не выдаётся
        assertNull(v.openPayload("a"))
        assertNull(v.openPayload("b"))
    }

    @Test
    fun `changePassword re-wraps the data key and keeps records`() {
        val v = vault().apply {
            create("old-pass".toCharArray())
            put("host-1", RecordType.HOST, "data".encodeToByteArray())
        }

        assertTrue(v.changePassword("old-pass".toCharArray(), "new-pass".toCharArray()))
        v.lock()

        assertEquals(UnlockResult.WrongPassword, vault().unlock("old-pass".toCharArray()))
        val reopened = vault()
        assertEquals(UnlockResult.Success, reopened.unlock("new-pass".toCharArray()))
        assertContentEquals("data".encodeToByteArray(), reopened.openPayload("host-1"))
    }

    @Test
    fun `changePassword with a wrong old password fails`() {
        val v = vault().apply { create("old-pass".toCharArray()) }

        assertFalse(v.changePassword("wrong".toCharArray(), "new-pass".toCharArray()))
    }
}
