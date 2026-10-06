package app.skerry.shared.team

import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.ForwardingFileSystem
import okio.Path
import okio.IOException
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TeamRecordingOutboxTest {
    private val fs = FakeFileSystem()
    private val crypto = IonspinVaultCrypto()

    @AfterTest fun close() = fs.checkNoOpenFiles()

    @Test
    fun `outbox persists ciphertext for retry without plaintext cast`() = runTest {
        initializeVaultCrypto()
        val ref = TeamScopeRef("team-1")
        val identity = RecordingIdentity(ref, "rec-1", "host-1", "alice@example.com", 0)
        val outbox = TeamRecordingOutbox("/private/recordings".toPath(), fs, {}, crypto)
        val capture = outbox.arm(identity, crypto.newDataKey(), 80, 24, "secret title", 100, { 0 })
        capture.record("secret terminal output".encodeToByteArray())
        val upload = capture.finish(2)

        assertEquals(upload.identity, outbox.pendingUploads().single().identity)
        val ciphertext = outbox.chunk(identity, 0)
        assertFalse(ciphertext.decodeToString().contains("secret terminal output"))
        assertContentEquals(ciphertext, outbox.chunk(identity, 0))
        outbox.markUploaded(identity)
        assertEquals(emptyList(), outbox.pendingUploads())
    }

    @Test
    fun `restart recovers each persisted feed including split utf8 without exposing title`() = runTest {
        initializeVaultCrypto()
        val key = crypto.newDataKey()
        val identity = RecordingIdentity(TeamScopeRef("team-1"), "rec-1", "host-1", "alice", 0)
        val directory = "/private/recordings".toPath()
        val first = TeamRecordingOutbox(directory, fs, {}, crypto)
        var elapsed = 0L
        val capture = first.arm(identity, key, 80, 24, "private title", 100, { elapsed }, linkKey = "server-A")
        val bytes = "secret €".encodeToByteArray()
        capture.record(bytes.copyOfRange(0, bytes.size - 1))
        elapsed = 1000
        capture.record(bytes.copyOfRange(bytes.size - 1, bytes.size))
        capture.abandon()
        fs.list(directory).flatMap { fs.list(it) }.forEach { path ->
            val text = fs.read(path) { readUtf8() }
            assertFalse(text.contains("private title"))
            assertFalse(text.contains("secret"))
        }
        val reopened = TeamRecordingOutbox(directory, fs, {}, crypto)
        reopened.recover("server-A") { _, epoch -> app.skerry.shared.vault.DataKey(key.bytes.copyOf()).takeIf { epoch == 0L } }
        val upload = reopened.pendingUploads("server-A").single()
        val codec = TeamRecordingCrypto(crypto)
        val dek = codec.openKey(key, upload.wrappedKey, identity)!!
        try {
            val cast = upload.chunks.joinToString("") { chunk ->
                val clear = codec.openChunk(dek, identity, chunk.index,
                    reopened.chunk(identity, chunk.index, "server-A"))!!
                try { clear.decodeToString() } finally { clear.fill(0) }
            }
            assertTrue(cast.contains("private title"))
            assertTrue(cast.contains("secret"))
            assertTrue(cast.contains("€"))
        } finally { dek.zeroize() }
        assertEquals(emptyList(), reopened.pendingUploads("server-B"))
    }

    @Test
    fun `same recording ids on two server links stay independent during rotation and upload`() = runTest {
        initializeVaultCrypto()
        val identity = RecordingIdentity(TeamScopeRef("team-1"), "rec-1", "host-1", "alice", 0)
        val key = crypto.newDataKey()
        val next = crypto.newDataKey()
        val outbox = TeamRecordingOutbox("/private/recordings".toPath(), fs, {}, crypto)
        val first = outbox.arm(identity, key, 80, 24, null, 100, { 0 }, "server-A")
        val second = outbox.arm(identity, key, 80, 24, null, 100, { 0 }, "server-B")
        first.record("A".encodeToByteArray())
        second.record("B".encodeToByteArray())
        first.abandon()
        second.finish(1)
        outbox.rewrapSpace(identity.ref, key, next, 3, "server-A")
        outbox.activateSpaceWrap(identity.ref, 3, "server-A")
        outbox.recover("server-A") { _, epoch -> app.skerry.shared.vault.DataKey(next.bytes.copyOf()).takeIf { epoch == 3L } }
        assertEquals(3L, outbox.pendingUploads("server-A").single().wrapEpoch)
        assertEquals(0L, outbox.pendingUploads("server-B").single().wrapEpoch)
        outbox.markUploaded(identity, "server-A")
        outbox.markUploaded(identity, "server-A")
        assertEquals(emptyList(), outbox.pendingUploads("server-A"))
        assertEquals(1, outbox.pendingUploads("server-B").size)
    }

    @Test
    fun `journal committed before entry write failure is recovered instead of discarded`() = runTest {
        initializeVaultCrypto()
        val identity = RecordingIdentity(TeamScopeRef("team-1"), "rec-1", "host-1", "alice", 0)
        val key = crypto.newDataKey()
        var failEntry = false
        val flaky = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                if (failEntry && target.name == "entry.json") throw IOException("entry update refused")
                super.atomicMove(source, target)
            }
        }
        val outbox = TeamRecordingOutbox("/private/recordings".toPath(), flaky, {}, crypto)
        val capture = outbox.arm(identity, key, 80, 24, null, 100, { 0 }, "server-A")
        failEntry = true
        assertFailsWith<IOException> { capture.record("committed output".encodeToByteArray()) }
        capture.abandon()
        failEntry = false
        outbox.recover("server-A") { _, _ -> app.skerry.shared.vault.DataKey(key.bytes.copyOf()) }
        val upload = outbox.pendingUploads("server-A").single()
        val codec = TeamRecordingCrypto(crypto)
        val dek = codec.openKey(key, upload.wrappedKey, identity)!!
        val clear = codec.openChunk(dek, identity, 0, outbox.chunk(identity, 0, "server-A"))!!
        try { assertTrue(clear.decodeToString().contains("committed output")) }
        finally { clear.fill(0); dek.zeroize() }
    }

    @Test
    fun `many small durable feeds compact without exhausting the final chunk count`() = runTest {
        initializeVaultCrypto()
        val identity = RecordingIdentity(TeamScopeRef("team-1"), "rec-1", "host-1", "alice", 0)
        val outbox = TeamRecordingOutbox("/private/recordings".toPath(), fs, {}, crypto)
        val capture = outbox.arm(identity, crypto.newDataKey(), 80, 24, null, 100, { 0 })
        repeat(TeamRecordingCrypto.MAX_CHUNKS + 10) { capture.record("hello\n".encodeToByteArray()) }
        assertEquals(1, capture.finish(1).chunks.size)
    }

    @Test
    fun `uploaded tombstone makes partial cleanup restartable`() = runTest {
        initializeVaultCrypto()
        val identity = RecordingIdentity(TeamScopeRef("team-1"), "rec-1", "host-1", "alice", 0)
        var failDelete = false
        val flaky = object : ForwardingFileSystem(fs) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (failDelete && path.name == "journal-0") throw IOException("delete refused")
                super.delete(path, mustExist)
            }
        }
        val outbox = TeamRecordingOutbox("/private/recordings".toPath(), flaky, {}, crypto)
        val capture = outbox.arm(identity, crypto.newDataKey(), 80, 24, null, 100, { 0 }, "server-A")
        capture.record("output".encodeToByteArray())
        capture.finish(1)
        failDelete = true
        assertFailsWith<IOException> { outbox.markUploaded(identity, "server-A") }
        assertEquals(emptyList(), outbox.pendingUploads("server-A"))
        failDelete = false
        val reopened = TeamRecordingOutbox("/private/recordings".toPath(), flaky, {}, crypto)
        reopened.recover("server-A") { _, _ -> error("uploaded entries must not resolve keys") }
        assertEquals(emptyList(), fs.list("/private/recordings".toPath()))
    }

    @Test
    fun `recovery skips live capture and continues past a corrupt unrelated entry`() = runTest {
        initializeVaultCrypto()
        val directory = "/private/recordings".toPath()
        val identity = RecordingIdentity(TeamScopeRef("team-1"), "rec-1", "host-1", "alice", 0)
        val key = crypto.newDataKey()
        val outbox = TeamRecordingOutbox(directory, fs, {}, crypto)
        val capture = outbox.arm(identity, key, 80, 24, null, 100, { 0 }, "server-A")
        capture.record("output".encodeToByteArray())
        outbox.recover("server-A") { _, _ -> error("live capture must not be recovered") }
        assertEquals(emptyList(), outbox.pendingUploads("server-A"))
        capture.abandon()
        val broken = directory / "broken"
        fs.createDirectories(broken)
        fs.write(broken / "entry.json") { writeUtf8("broken") }
        assertFailsWith<IllegalStateException> {
            outbox.recover("server-A") { _, _ -> app.skerry.shared.vault.DataKey(key.bytes.copyOf()) }
        }
        assertEquals(1, outbox.pendingUploads("server-A").size)
        assertTrue(fs.exists(broken / "entry.json"))
    }

    @Test
    fun `tiny feeds are charged for filesystem allocation and rejected prefix stays recoverable`() = runTest {
        assertJournalLimitPreservesEvidence(allocationLimit = 8_192, maxEntries = 32_768)
    }

    @Test
    fun `journal file count is bounded independently of ciphertext size`() = runTest {
        assertJournalLimitPreservesEvidence(allocationLimit = 1_000_000, maxEntries = 1)
    }

    private suspend fun assertJournalLimitPreservesEvidence(allocationLimit: Long, maxEntries: Int) {
        initializeVaultCrypto()
        val identity = RecordingIdentity(TeamScopeRef("team-1"), "rec-1", "host-1", "alice", 0)
        val key = crypto.newDataKey()
        val outbox = TeamRecordingOutbox("/private/recordings".toPath(), fs, {}, crypto,
            journalAllocationLimit = allocationLimit, maxJournalEntries = maxEntries)
        val capture = outbox.arm(identity, key, startedAtSeconds = 100, now = { 0 })
        capture.record("committed".encodeToByteArray())
        assertFailsWith<IllegalStateException> { capture.record("rejected".encodeToByteArray()) }
        capture.abandon()
        outbox.recover("") { _, _ -> app.skerry.shared.vault.DataKey(key.bytes.copyOf()) }
        val upload = outbox.pendingUploads().single()
        val codec = TeamRecordingCrypto(crypto)
        val dek = codec.openKey(key, upload.wrappedKey, identity)!!
        val clear = codec.openChunk(dek, identity, 0, outbox.chunk(identity, 0))!!
        try {
            assertTrue(clear.decodeToString().contains("committed"))
            assertFalse(clear.decodeToString().contains("rejected"))
        } finally { clear.fill(0); dek.zeroize(); key.zeroize() }
    }
}
