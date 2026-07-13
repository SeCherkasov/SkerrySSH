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
 * File vault tests use the real [IonspinVaultCrypto] (Argon2id), not a fake — an honest
 * integration of store and crypto. I/O goes through in-memory [FakeFileSystem], so the tests are
 * shared across all targets (commonTest) and never touch the real filesystem. Derivation is
 * expensive, so passwords are short and unlock/create calls per test are kept minimal. libsodium
 * needs async init before first use — each test is wrapped in [vaultTest].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent() in localChanges tests
class FileVaultTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val fs = FakeFileSystem()
    private val file: Path = "/vault.json".toPath()
    private val json = Json { ignoreUnknownKeys = true }

    private fun vault() = FileVault(file, crypto, deviceId = "device-1", fileSystem = fs, now = { TS })

    /** Ensures libsodium is initialized before the test body; init is idempotent. */
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

        // The tombstone keeps the encrypted blob for sync but never opens it.
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
        v.remove("dead") // tombstone

        // Compact both ids: the tombstone disappears for good (no longer in records — no longer
        // pushed), the live record in the same call is left untouched (protects an unpushed version).
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
            compact(listOf("dead", "never-existed")) // unknown id is a no-op, does not throw
        }
        // Re-read the file with a new store: compaction reached disk, not just the cache.
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
        // Insert a record with a type this version doesn't know (a future RecordType) directly
        // into JSON — simulates a record synced from a newer version on another device.
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
        // One unrecognized record does not make the whole vault Corrupted (else reset is the only way out).
        assertEquals(UnlockResult.Success, v.unlock("master".toCharArray()))
        assertContentEquals("payload".encodeToByteArray(), v.openPayload("known"))
        // Rewriting the file (put) preserves the unknown record verbatim — a downgrade loses no data.
        v.put("known2", RecordType.HOST, "p2".encodeToByteArray())
        val after = json.parseToJsonElement(fs.read(file) { readUtf8() }).jsonObject.getValue("records").jsonArray
        assertTrue(after.any { it.jsonObject["id"].toString().contains("future") })
    }

    @Test
    fun `mergeRemote rejects a record with a too-short blob instead of throwing`() = vaultTest {
        val v = vault()
        v.create("master".toCharArray())
        // An untrusted sync server could send a record with a blob shorter than nonce+tag —
        // trial-decrypt must treat it as an ordinary AEAD failure and reject the record, not throw
        // (else DoS: crash on every sync) and not store an unreadable blob.
        val result = v.mergeRemote(
            listOf(
                VaultRecord("evil", RecordType.HOST, version = 99, updatedAt = TS, deviceId = "z", deleted = false, blob = ByteArray(4)),
            ),
        )
        assertEquals(listOf("evil"), result.rejected.map { it.id })
        assertTrue(v.records().none { it.id == "evil" })
        assertNull(v.openPayload("evil"))
    }

    @Test
    fun `mergeRemote rejects a garbage blob with an inflated version`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "genuine".encodeToByteArray())
        }

        // A malicious server pushes an undecryptable blob claiming version=MAX to overwrite the record.
        val garbage = VaultRecord("host-1", RecordType.HOST, version = Long.MAX_VALUE, updatedAt = TS, deviceId = "evil", deleted = false, blob = ByteArray(64))
        val result = v.mergeRemote(listOf(garbage))

        assertTrue(result.applied.isEmpty())
        assertEquals(listOf("host-1"), result.rejected.map { it.id })
        // The locally readable record survives instead of being silently replaced.
        assertEquals(1L, v.records().first { it.id == "host-1" }.version)
        assertContentEquals("genuine".encodeToByteArray(), v.openPayload("host-1"))
    }

    @Test
    fun `mergeRemote rejects a replayed genuine blob with a bumped version`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "old-password".encodeToByteArray())
        }
        val genuine = v.records().first { it.id == "host-1" }
        v.put("host-1", RecordType.HOST, "new-password".encodeToByteArray())

        // Rollback attack: the server replays the authentic version-1 ciphertext claiming version 3.
        // The AAD binds the version the blob was sealed with, so the tag check fails.
        val replay = genuine.copy(version = 3L)
        val result = v.mergeRemote(listOf(replay))

        assertTrue(result.applied.isEmpty())
        assertEquals(listOf("host-1"), result.rejected.map { it.id })
        assertContentEquals("new-password".encodeToByteArray(), v.openPayload("host-1"))
    }

    @Test
    fun `mergeRemote rejects a forged tombstone built from a live blob`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("host-1", RecordType.HOST, "data".encodeToByteArray())
        }
        val live = v.records().first { it.id == "host-1" }

        // The server flips deleted=true on an authentic live blob to destroy the record fleet-wide.
        val forged = live.copy(deleted = true, version = live.version + 1)
        val result = v.mergeRemote(listOf(forged))

        assertTrue(result.applied.isEmpty())
        assertEquals(listOf("host-1"), result.rejected.map { it.id })
        assertFalse(v.records().first { it.id == "host-1" }.deleted)
        assertContentEquals("data".encodeToByteArray(), v.openPayload("host-1"))
    }

    @Test
    fun `mergeRemote accepts a record sealed by another device under the same key`() = vaultTest {
        val a = vault().apply { create("master".toCharArray()) }
        val b = FileVault("/vault-b.json".toPath(), crypto, deviceId = "device-2", fileSystem = fs, now = { TS })
        b.createWithDataKey(a.exportDataKey()!!)
        b.put("r1", RecordType.HOST, "from-b".encodeToByteArray())

        val result = a.mergeRemote(listOf(b.records().single()))

        assertEquals(listOf("r1"), result.applied.map { it.id })
        assertTrue(result.rejected.isEmpty())
        assertContentEquals("from-b".encodeToByteArray(), a.openPayload("r1"))
    }

    @Test
    fun `mergeRemote and openPayload accept legacy blobs sealed with the id-type AAD`() = vaultTest {
        val v = vault().apply { create("master".toCharArray()) }
        // A blob sealed before metadata binding (an old client, or a record still on the sync
        // server): AAD covers only id‖type. It must merge and read via the legacy fallback.
        val key = v.exportDataKey()!!
        val blob = crypto.seal(key, "legacy-payload".encodeToByteArray(), "old-1\u001F${RecordType.HOST.name}".encodeToByteArray())
        key.zeroize()
        val legacy = VaultRecord("old-1", RecordType.HOST, version = 2, updatedAt = TS, deviceId = "devX", deleted = false, blob = blob)

        val result = v.mergeRemote(listOf(legacy))

        assertEquals(listOf("old-1"), result.applied.map { it.id })
        assertTrue(result.rejected.isEmpty())
        assertContentEquals("legacy-payload".encodeToByteArray(), v.openPayload("old-1"))
    }

    @Test
    fun `tombstone from remove is authenticated on another device sharing the key`() = vaultTest {
        val a = vault().apply {
            create("master".toCharArray())
            put("h", RecordType.HOST, "x".encodeToByteArray())
        }
        val b = FileVault("/vault-b.json".toPath(), crypto, deviceId = "device-2", fileSystem = fs, now = { TS })
        b.createWithDataKey(a.exportDataKey()!!)

        a.remove("h")
        val tombstone = a.records().single { it.id == "h" }
        val result = b.mergeRemote(listOf(tombstone))

        // remove() re-seals the tombstone, so its bumped version and deleted=true pass trial-decrypt.
        assertEquals(listOf("h"), result.applied.map { it.id })
        assertTrue(b.records().single { it.id == "h" }.deleted)
    }

    @Test
    fun `tombstone does not keep the deleted payload in its blob`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("h", RecordType.HOST, "secret".encodeToByteArray())
        }

        v.remove("h")

        val tombstone = v.records().single { it.id == "h" }
        val key = v.exportDataKey()!!
        val opened = crypto.open(key, tombstone.blob, recordAad(tombstone))
        key.zeroize()
        // The re-sealed tombstone authenticates but carries no plaintext — the secret is gone.
        assertContentEquals(ByteArray(0), opened)
    }

    @Test
    fun `remove works even when the record blob is unreadable under the current key`() = vaultTest {
        val v = vault().apply {
            create("master".toCharArray())
            put("h", RecordType.HOST, "x".encodeToByteArray())
        }
        // Adopting another account's key makes the old local record unreadable (documented
        // adoptDataKey semantics); removing it must still produce a valid tombstone.
        v.adoptDataKey(crypto.newDataKey(), "account".toCharArray())

        v.remove("h")

        assertTrue(v.records().single { it.id == "h" }.deleted)
        assertNull(v.openPayload("h"))
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
        // Record b's blob under slot a's AAD (and vice versa) — tag check fails, no payload returned
        assertNull(v.openPayload("a"))
        assertNull(v.openPayload("b"))
    }

    @Test
    fun `aad binds id and type with a separator so adjacent slots cannot collide`() = vaultTest {
        // Without a separator, slots ("aKNOWN_", HOST) and ("a", KNOWN_HOST) yield the same AAD
        // ("aKNOWN_HOST"), so one slot's blob would pass AEAD under the other.
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
        // The genuine slot still decrypts...
        assertContentEquals("host-payload".encodeToByteArray(), v.openPayload("aKNOWN_"))
        // ...but the same blob under the neighboring slot's AAD ("a", KNOWN_HOST) fails the tag check.
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
        // After reset, CRUD is unavailable (vault locked) and the file no longer exists on disk.
        assertFailsWith<IllegalStateException> { v.records() }
        assertFalse(fs.exists(file))
    }

    @Test
    fun `reset removes a corrupted file and lets a fresh vault be created`() = vaultTest {
        fs.write(file) { writeUtf8("{ this is not valid vault json") }
        val v = vault()
        // A corrupt file cannot be unlocked — reset is the only way out.
        assertEquals(UnlockResult.Corrupted, v.unlock("master".toCharArray()))

        v.reset()
        assertFalse(v.exists())

        // Right after reset, a new vault can be created from scratch — the deadlock is resolved.
        v.create("fresh-pass".toCharArray())
        assertTrue(v.exists())
        assertTrue(v.isUnlocked)
    }

    @Test
    fun `reset with no existing file is a no-op`() = vaultTest {
        val v = vault()
        assertFalse(v.exists())

        v.reset() // must not throw when the file doesn't exist

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

        // Identity check does not re-derive the key or reread records: the vault stays unlocked.
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

        // Simulate an account key received from another device (differs from the local key).
        val accountKey = crypto.newDataKey()
        assertTrue(v.adoptDataKey(accountKey, "account".toCharArray()))
        // A record sealed under the adopted key must read back after restart.
        v.put("r1", RecordType.HOST, "secret".encodeToByteArray())
        v.lock()

        // The old local password no longer works — the vault is rewrapped under the account password.
        assertEquals(UnlockResult.WrongPassword, vault().unlock("local".toCharArray()))
        val reopened = vault()
        assertEquals(UnlockResult.Success, reopened.unlock("account".toCharArray()))
        assertContentEquals("secret".encodeToByteArray(), reopened.openPayload("r1"))
    }

    @Test
    fun `adoptDataKey is a no-op when the key is unchanged and keeps the password`() = vaultTest {
        val v = vault()
        v.create("local".toCharArray())

        // Same key (primary device reconnecting with its own) => nothing is rewritten.
        val sameKey = v.exportDataKey()!!
        assertFalse(v.adoptDataKey(sameKey, "account".toCharArray()))
        v.lock()

        // The vault password did not change: unlocks with the original, not the one passed to adoptDataKey.
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
        runCurrent() // let the subscriber register before the mutation (SharedFlow replay=0)

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
        v.remove("h") // first removal => tombstone (already emitted, before subscription)
        val seen = mutableListOf<Unit>()
        val job = backgroundScope.launch { v.localChanges.collect { seen += Unit } }
        runCurrent()

        v.remove("h") // removing an already-tombstoned record is a no-op, must not trigger a push
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

        // An incoming record from sync (here it even fails trial-decrypt and is rejected): merge is
        // an incoming operation, so localChanges is not emitted — otherwise pull->merge would loop into a push.
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
        v.remove("h") // tombstone that compaction will physically delete
        val seen = mutableListOf<Unit>()
        val job = backgroundScope.launch { v.localChanges.collect { seen += Unit } }
        runCurrent()

        v.compact(listOf("h"))
        runCurrent()

        assertTrue(seen.isEmpty())
        job.cancel()
    }

    @Test
    fun `failed atomic write cleans up the tmp file`() = vaultTest {
        // Write target is an existing directory: atomicMove into it fails, and atomicWriteUtf8 must
        // clean up the tmp file (not leave an orphaned copy of secrets on disk).
        fs.createDirectories(file)

        assertFailsWith<Exception> { vault().create("m".toCharArray()) }

        assertFalse(fs.exists("/vault.json.tmp".toPath()), "tmp file must be removed when the write fails")
    }

    private companion object {
        const val TS = "2026-06-12T00:00:00Z"
    }
}
