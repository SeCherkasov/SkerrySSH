package app.skerry.shared.vault

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent() в тестах localChanges
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
    fun `compact physically forgets tombstones but keeps live records`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("dead", RecordType.HOST, "x".encodeToByteArray())
            put("alive", RecordType.HOST, "y".encodeToByteArray())
        }
        v.remove("dead") // надгробие

        // Компактим оба id: тромбстоун исчезает НАСОВСЕМ (не остаётся в records — больше не пушится),
        // живая запись с тем же запросом не трогается (защита от потери непротолкнутой версии).
        v.compact(listOf("dead", "alive"))

        assertNull(v.records().firstOrNull { it.id == "dead" })
        assertTrue(v.records().any { it.id == "alive" })
        assertContentEquals("y".encodeToByteArray(), v.openPayload("alive"))
    }

    @Test
    fun `compact survives a reload and is idempotent on unknown ids`() = vaultTest {
        val file2 = "/vault2.json".toPath()
        FileVault(file2, crypto, deviceId = "device-1", fileSystem = fs, now = { TS }).apply {
            create("master".toCharArray())
            put("dead", RecordType.HOST, "x".encodeToByteArray())
            remove("dead")
            compact(listOf("dead", "never-existed")) // неизвестный id — no-op, не падаем
        }
        // Перечитываем файл новым стором: компакция дошла до диска, а не только в кеш.
        val reloaded = FileVault(file2, crypto, deviceId = "device-1", fileSystem = fs, now = { TS })
        assertEquals(UnlockResult.Success, reloaded.unlock("master".toCharArray()))
        assertTrue(reloaded.records().none { it.id == "dead" })
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
    fun `unlock tolerates unknown record types and preserves them across rewrites`() = vaultTest {
        vault().apply {
            create("master".toCharArray())
            put("known", RecordType.HOST, "payload".encodeToByteArray())
            lock()
        }
        // Вставляем запись с типом, которого эта версия не знает (будущий RecordType), прямо в JSON —
        // имитируем запись, синканутую с более новой версии на другом устройстве.
        val root = json.parseToJsonElement(fs.read(file) { readUtf8() }).jsonObject
        val future = buildJsonObject {
            put("id", "future"); put("type", "FUTURE_TYPE"); put("version", 1L)
            put("updatedAt", TS); put("deviceId", "device-1"); put("deleted", false)
            put("blob", buildJsonArray { })
        }
        val patched = buildJsonObject {
            put("meta", root.getValue("meta"))
            put("records", buildJsonArray { root.getValue("records").jsonArray.forEach { add(it) }; add(future) })
        }
        fs.write(file) { writeUtf8(json.encodeToString(JsonObject.serializer(), patched)) }

        val v = vault()
        // Одна нераспознанная запись НЕ делает весь vault Corrupted (иначе единственный выход — reset).
        assertEquals(UnlockResult.Success, v.unlock("master".toCharArray()))
        assertContentEquals("payload".encodeToByteArray(), v.openPayload("known"))
        // Перезапись файла (put) сохраняет неизвестную запись verbatim — downgrade не теряет данные.
        v.put("known2", RecordType.HOST, "p2".encodeToByteArray())
        val after = json.parseToJsonElement(fs.read(file) { readUtf8() }).jsonObject.getValue("records").jsonArray
        assertTrue(after.any { it.jsonObject["id"].toString().contains("future") })
    }

    @Test
    fun `a record with a too-short blob reads as null instead of throwing`() = vaultTest {
        val v = vault()
        v.create("master".toCharArray())
        // Недоверенный sync-сервер мог бы прислать запись с blob короче nonce+tag — mergeRemote кладёт
        // её как есть; чтение не должно бросать (иначе DoS: падение при каждой разблокировке).
        v.mergeRemote(
            listOf(
                VaultRecord("evil", RecordType.HOST, version = 99, updatedAt = TS, deviceId = "z", deleted = false, blob = ByteArray(4)),
            ),
        )
        assertNull(v.openPayload("evil"))
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
    fun `reset deletes the file and locks the vault`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "data".encodeToByteArray())
        }

        v.reset()

        assertFalse(v.exists())
        assertFalse(v.isUnlocked)
        // После сброса CRUD недоступен (vault заблокирован), а файла на диске больше нет.
        assertFailsWith<IllegalStateException> { v.records() }
        assertFalse(fs.exists(file))
    }

    @Test
    fun `reset removes a corrupted file and lets a fresh vault be created`() = vaultTest {
        fs.write(file) { writeUtf8("{ this is not valid vault json") }
        val v = vault()
        // Битый файл нельзя разблокировать — единственный выход через сброс.
        assertEquals(UnlockResult.Corrupted, v.unlock("master".toCharArray()))

        v.reset()
        assertFalse(v.exists())

        // Сразу после сброса можно создать новый vault с нуля — тупик расшит.
        v.create("fresh-pass".toCharArray())
        assertTrue(v.exists())
        assertTrue(v.isUnlocked)
    }

    @Test
    fun `reset with no existing file is a no-op`() = vaultTest {
        val v = vault()
        assertFalse(v.exists())

        v.reset() // не должно бросать на отсутствующем файле

        assertFalse(v.exists())
        assertFalse(v.isUnlocked)
    }

    @Test
    fun `changePassword with a wrong old password fails`() = vaultTest {
        val v = vault().apply { create("old-pass".toCharArray()) }

        assertFalse(v.changePassword("wrong".toCharArray(), "new-pass".toCharArray()))
    }

    @Test
    fun `verifyPassword accepts the correct master password`() = vaultTest {
        val v = vault().apply { create("master".toCharArray()) }

        assertTrue(v.verifyPassword("master".toCharArray()))
    }

    @Test
    fun `verifyPassword rejects a wrong master password`() = vaultTest {
        val v = vault().apply { create("master".toCharArray()) }

        assertFalse(v.verifyPassword("nope".toCharArray()))
    }

    @Test
    fun `verifyPassword does not disturb the open session`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "data".encodeToByteArray())
        }

        assertTrue(v.verifyPassword("master".toCharArray()))

        // Проверка личности не перевыдаёт ключ и не перечитывает записи: vault остаётся открыт.
        assertTrue(v.isUnlocked)
        assertContentEquals("data".encodeToByteArray(), v.openPayload("host-1"))
    }

    @Test
    fun `verifyPassword on a locked vault returns false`() = vaultTest {
        val v = vault().apply { create("master".toCharArray()); lock() }

        assertFalse(v.verifyPassword("master".toCharArray()))
    }

    @Test
    fun `adoptDataKey persists a different account key under the given password`() = vaultTest {
        val v = vault()
        v.create("local".toCharArray())

        // Имитируем ключ аккаунта, пришедший с другого устройства (отличается от локального).
        val accountKey = crypto.newDataKey()
        assertTrue(v.adoptDataKey(accountKey, "account".toCharArray()))
        // Запись, запечатанная уже под принятым ключом, должна читаться после перезапуска.
        v.put("r1", RecordType.HOST, "secret".encodeToByteArray())
        v.lock()

        // Старый локальный пароль больше не подходит — vault переобёрнут под паролем аккаунта.
        assertEquals(UnlockResult.WrongPassword, vault().unlock("local".toCharArray()))
        val reopened = vault()
        assertEquals(UnlockResult.Success, reopened.unlock("account".toCharArray()))
        assertContentEquals("secret".encodeToByteArray(), reopened.openPayload("r1"))
    }

    @Test
    fun `adoptDataKey is a no-op when the key is unchanged and keeps the password`() = vaultTest {
        val v = vault()
        v.create("local".toCharArray())

        // Тот же ключ (основное устройство переподключается своим же) → ничего не переписываем.
        val sameKey = v.exportDataKey()!!
        assertFalse(v.adoptDataKey(sameKey, "account".toCharArray()))
        v.lock()

        // Пароль vault не сменился: открывается исходным, а не переданным в adoptDataKey.
        assertEquals(UnlockResult.Success, vault().unlock("local".toCharArray()))
        assertEquals(UnlockResult.WrongPassword, vault().unlock("account".toCharArray()))
    }

    @Test
    fun `put emits a local change for live-sync`() = runTest {
        initializeVaultCrypto()
        val v = vault()
        v.create("m".toCharArray())
        val seen = mutableListOf<Unit>()
        val job = backgroundScope.launch { v.localChanges.collect { seen += Unit } }
        runCurrent() // дать подписчику зарегистрироваться до мутации (SharedFlow replay=0)

        v.put("h", RecordType.HOST, "x".encodeToByteArray())
        runCurrent()

        assertEquals(1, seen.size)
        job.cancel()
    }

    @Test
    fun `remove emits a local change for live-sync`() = runTest {
        initializeVaultCrypto()
        val v = vault()
        v.create("m".toCharArray())
        v.put("h", RecordType.HOST, "x".encodeToByteArray())
        val seen = mutableListOf<Unit>()
        val job = backgroundScope.launch { v.localChanges.collect { seen += Unit } }
        runCurrent()

        v.remove("h")
        runCurrent()

        assertEquals(1, seen.size)
        job.cancel()
    }

    @Test
    fun `removing an unknown id emits nothing`() = runTest {
        initializeVaultCrypto()
        val v = vault()
        v.create("m".toCharArray())
        val seen = mutableListOf<Unit>()
        val job = backgroundScope.launch { v.localChanges.collect { seen += Unit } }
        runCurrent()

        v.remove("nope")
        runCurrent()

        assertTrue(seen.isEmpty())
        job.cancel()
    }

    @Test
    fun `removing an already-tombstoned id emits nothing`() = runTest {
        initializeVaultCrypto()
        val v = vault()
        v.create("m".toCharArray())
        v.put("h", RecordType.HOST, "x".encodeToByteArray())
        v.remove("h") // первое удаление → надгробие (эмит уже был, до подписки)
        val seen = mutableListOf<Unit>()
        val job = backgroundScope.launch { v.localChanges.collect { seen += Unit } }
        runCurrent()

        v.remove("h") // повторное удаление надгробия — no-op, не должно будить push
        runCurrent()

        assertTrue(seen.isEmpty())
        job.cancel()
    }

    @Test
    fun `mergeRemote does not emit a local change`() = runTest {
        initializeVaultCrypto()
        val v = vault()
        v.create("m".toCharArray())
        val seen = mutableListOf<Unit>()
        val job = backgroundScope.launch { v.localChanges.collect { seen += Unit } }
        runCurrent()

        // Входящая запись с sync: merge кладёт её verbatim, но push обратно не нужен (LWW отверг бы),
        // поэтому localChanges не эмитится — иначе pull→merge зациклил бы push.
        val incoming = VaultRecord("r", RecordType.HOST, version = 5, updatedAt = TS, deviceId = "other", deleted = false, blob = ByteArray(8))
        v.mergeRemote(listOf(incoming))
        runCurrent()

        assertTrue(seen.isEmpty())
        job.cancel()
    }

    @Test
    fun `compact does not emit a local change`() = runTest {
        initializeVaultCrypto()
        val v = vault()
        v.create("m".toCharArray())
        v.put("h", RecordType.HOST, "x".encodeToByteArray())
        v.remove("h") // надгробие, которое компакция физически удалит
        val seen = mutableListOf<Unit>()
        val job = backgroundScope.launch { v.localChanges.collect { seen += Unit } }
        runCurrent()

        v.compact(listOf("h"))
        runCurrent()

        assertTrue(seen.isEmpty())
        job.cancel()
    }

    private companion object {
        const val TS = "2026-06-12T00:00:00Z"
    }
}
