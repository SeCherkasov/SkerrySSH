package app.skerry.shared.vault

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты файлового vault используют настоящий [IonspinVaultCrypto] (Argon2id), а не фейк —
 * это честная интеграция стора и крипто. I/O идёт через in-memory [FakeFileSystem], поэтому
 * тесты общие для всех таргетов (commonTest) и не трогают реальную ФС. Деривация дорогая,
 * поэтому пароли короткие, а число unlock/create в каждом тесте минимально. libsodium требует
 * асинхронной инициализации до первого вызова — каждый тест обёрнут в [vaultTest].
 */
class FileVaultTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val fs = FakeFileSystem()
    private val file: Path = "/vault.json".toPath()
    private val json = Json { ignoreUnknownKeys = true }

    private fun vault() = FileVault(file, crypto, deviceId = "device-1", fileSystem = fs, now = { TS })

    /** Гарантирует инициализацию libsodium перед телом теста; init идемпотентен. */
    private fun vaultTest(block: suspend () -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    @Test
    fun `create writes a file and leaves the vault unlocked`() = vaultTest {
        val v = vault()
        assertFalse(v.exists())

        v.create("master".toCharArray())

        assertTrue(v.exists())
        assertTrue(v.isUnlocked)
    }

    @Test
    fun `unlock with the correct password succeeds on a fresh instance`() = vaultTest {
        vault().create("master".toCharArray())

        assertEquals(UnlockResult.Success, vault().unlock("master".toCharArray()))
    }

    @Test
    fun `unlock with a wrong password is rejected`() = vaultTest {
        vault().create("master".toCharArray())

        assertEquals(UnlockResult.WrongPassword, vault().unlock("nope".toCharArray()))
    }

    @Test
    fun `unlock of a corrupted file reports Corrupted`() = vaultTest {
        fs.write(file) { writeUtf8("{ this is not valid vault json") }

        assertEquals(UnlockResult.Corrupted, vault().unlock("master".toCharArray()))
    }

    @Test
    fun `crud throws while the vault is locked`() = vaultTest {
        val v = vault()
        v.create("master".toCharArray())
        v.lock()

        assertFalse(v.isUnlocked)
        assertFailsWith<IllegalStateException> { v.records() }
        assertFailsWith<IllegalStateException> { v.put("a", RecordType.HOST, ByteArray(1)) }
        assertFailsWith<IllegalStateException> { v.openPayload("a") }
    }

    @Test
    fun `put then openPayload round-trips and persists across lock`() = vaultTest {
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
    fun `put with an existing id upserts and bumps version`() = vaultTest {
        val v = vault().apply { create("master".toCharArray()) }

        v.put("host-1", RecordType.HOST, "v1".encodeToByteArray())
        v.put("host-1", RecordType.HOST, "v2".encodeToByteArray())

        assertEquals(1, v.records().count { it.id == "host-1" })
        assertEquals(2L, v.records().first { it.id == "host-1" }.version)
        assertContentEquals("v2".encodeToByteArray(), v.openPayload("host-1"))
    }

    @Test
    fun `openPayload of an unknown id is null`() = vaultTest {
        val v = vault().apply { create("master".toCharArray()) }

        assertNull(v.openPayload("ghost"))
    }

    @Test
    fun `remove leaves a tombstone with a bumped version`() = vaultTest {
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
    fun `openPayload of a removed id is null`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "data".encodeToByteArray())
        }

        v.remove("host-1")

        // tombstone сохраняет зашифрованный blob для sync, но открытым его не выдаёт.
        assertNull(v.openPayload("host-1"))
    }

    @Test
    fun `remove of an already-deleted id does not bump version again`() = vaultTest {
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
    fun `put after remove resurrects the record`() = vaultTest {
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
    fun `changePassword throws while the vault is locked`() = vaultTest {
        val v = vault().apply { create("master".toCharArray()); lock() }

        assertFailsWith<IllegalStateException> {
            v.changePassword("master".toCharArray(), "new".toCharArray())
        }
    }

    @Test
    fun `remove of an unknown id is a no-op`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "data".encodeToByteArray())
        }

        v.remove("ghost")

        assertEquals(1, v.records().size)
    }

    @Test
    fun `swapping record blobs on disk is rejected by AAD binding`() = vaultTest {
        vault().apply {
            create("master".toCharArray())
            put("a", RecordType.HOST, "payload-A".encodeToByteArray())
            put("b", RecordType.HOST, "payload-B".encodeToByteArray())
            lock()
        }

        val body = json.decodeFromString<VaultFileBody>(fs.read(file) { readUtf8() })
        val a = body.records.first { it.id == "a" }
        val b = body.records.first { it.id == "b" }
        val tampered = body.copy(records = listOf(a.copy(blob = b.blob), b.copy(blob = a.blob)))
        fs.write(file) { writeUtf8(json.encodeToString(tampered)) }

        val v = vault()
        assertEquals(UnlockResult.Success, v.unlock("master".toCharArray()))
        // blob записи b под AAD слота a (и наоборот) — тег не проходит, payload не выдаётся
        assertNull(v.openPayload("a"))
        assertNull(v.openPayload("b"))
    }

    @Test
    fun `aad binds id and type with a separator so adjacent slots cannot collide`() = vaultTest {
        // Без разделителя слоты ("aKNOWN_", HOST) и ("a", KNOWN_HOST) дают одинаковый AAD
        // ("aKNOWN_HOST"), и blob одного слота прошёл бы AEAD в другом.
        vault().apply {
            create("master".toCharArray())
            put("aKNOWN_", RecordType.HOST, "host-payload".encodeToByteArray())
            lock()
        }

        val body = json.decodeFromString<VaultFileBody>(fs.read(file) { readUtf8() })
        val host = body.records.first { it.id == "aKNOWN_" }
        val forged = host.copy(id = "a", type = RecordType.KNOWN_HOST)
        fs.write(file) { writeUtf8(json.encodeToString(body.copy(records = body.records + forged))) }

        val v = vault()
        assertEquals(UnlockResult.Success, v.unlock("master".toCharArray()))
        // Подлинный слот по-прежнему расшифровывается...
        assertContentEquals("host-payload".encodeToByteArray(), v.openPayload("aKNOWN_"))
        // ...а blob того же слота под AAD соседнего ("a", KNOWN_HOST) — тег не проходит.
        assertNull(v.openPayload("a"))
    }

    @Test
    fun `changePassword re-wraps the data key and keeps records`() = vaultTest {
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
    fun `changePassword with a wrong old password fails`() = vaultTest {
        val v = vault().apply { create("old-pass".toCharArray()) }

        assertFalse(v.changePassword("wrong".toCharArray(), "new-pass".toCharArray()))
    }

    private companion object {
        const val TS = "2026-06-12T00:00:00Z"
    }
}
