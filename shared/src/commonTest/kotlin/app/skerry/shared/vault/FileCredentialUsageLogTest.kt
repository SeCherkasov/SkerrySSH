package app.skerry.shared.vault

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FileCredentialUsageLog] over [FakeFileSystem] (like [FileSecurityLogTest]): what the vault can
 * honestly say about a secret's life — when it was added, when it last authenticated, how often it
 * was copied. Covers the first-write-wins rule for "added", the copy cap, forgetting a deleted
 * secret, and survival across restart.
 */
class FileCredentialUsageLogTest {
    private val fs = FakeFileSystem()
    private val path = "/cfg/credential_usage.json".toPath()

    // Controlled clock: the test sets timestamps itself so ordering stays deterministic.
    private var clock = 0L
    private fun log(maxCopies: Int = 64) =
        FileCredentialUsageLog(path, fs, maxCopies = maxCopies) { "2026-01-01T00:00:${clock.toString().padStart(2, '0')}Z" }

    @AfterTest
    fun tearDown() = fs.checkNoOpenFiles()

    @Test
    fun unknownCredentialHasNoUsage() {
        assertNull(log().of("cred-1"))
    }

    @Test
    fun addedIsStampedOnceAndNeverMoves() {
        val l = log()
        clock = 1
        l.recordAdded("cred-1")
        // A second "added" (import re-running, a save over the same id) must not rewrite history.
        clock = 9
        l.recordAdded("cred-1")
        assertTrue(l.of("cred-1")!!.addedAt!!.endsWith(":01Z"))
    }

    @Test
    fun lastUsedTracksTheNewestConnection() {
        val l = log()
        clock = 1
        l.recordUsed("cred-1")
        clock = 4
        l.recordUsed("cred-1")
        assertTrue(l.of("cred-1")!!.lastUsedAt!!.endsWith(":04Z"))
        assertNull(l.of("cred-1")!!.addedAt)
    }

    @Test
    fun changedTracksTheNewestRotation() {
        val l = log()
        clock = 3
        l.recordChanged("cred-1")
        clock = 8
        l.recordChanged("cred-1")
        assertTrue(l.of("cred-1")!!.changedAt!!.endsWith(":08Z"))
        // Rotating the material is not the moment the secret was added.
        assertNull(l.of("cred-1")!!.addedAt)
    }

    @Test
    fun copiesAccumulateNewestLast() {
        val l = log()
        clock = 2
        l.recordCopied("cred-1")
        clock = 5
        l.recordCopied("cred-1")
        val copies = l.of("cred-1")!!.copiedAt
        assertEquals(2, copies.size)
        assertTrue(copies.last().endsWith(":05Z"))
    }

    @Test
    fun copyCapDropsOldest() {
        val l = log(maxCopies = 3)
        repeat(5) { clock = it.toLong(); l.recordCopied("cred-1") }
        val copies = l.of("cred-1")!!.copiedAt
        assertEquals(3, copies.size)
        assertTrue(copies.first().endsWith(":02Z"))
        assertTrue(copies.last().endsWith(":04Z"))
    }

    @Test
    fun exportsAccumulateNewestLast() {
        val l = log()
        clock = 2
        l.recordExported("cred-1")
        clock = 6
        l.recordExported("cred-1")
        val exports = l.of("cred-1")!!.exportedAt
        assertEquals(2, exports.size)
        assertTrue(exports.last().endsWith(":06Z"))
    }

    @Test
    fun exportCapDropsOldest() {
        val l = log(maxCopies = 3)
        repeat(5) { clock = it.toLong(); l.recordExported("cred-1") }
        val exports = l.of("cred-1")!!.exportedAt
        assertEquals(3, exports.size)
        assertTrue(exports.first().endsWith(":02Z"))
        assertTrue(exports.last().endsWith(":04Z"))
    }

    @Test
    fun exportsPersistAcrossInstances() {
        // An export is the audit event a user comes back to days later — it must survive restart.
        clock = 7
        log().recordExported("cred-1")
        assertTrue(log().of("cred-1")!!.exportedAt.single().endsWith(":07Z"))
    }

    @Test
    fun usageIsPerCredential() {
        val l = log()
        clock = 1
        l.recordAdded("cred-1")
        clock = 2
        l.recordAdded("cred-2")
        l.recordCopied("cred-2")
        assertTrue(l.of("cred-1")!!.copiedAt.isEmpty())
        assertEquals(1, l.of("cred-2")!!.copiedAt.size)
    }

    @Test
    fun forgetDropsOnlyThatCredential() {
        val l = log()
        clock = 1
        l.recordAdded("cred-1")
        l.recordAdded("cred-2")
        l.forget("cred-1")
        assertNull(l.of("cred-1"))
        assertTrue(l.of("cred-2") != null)
    }

    @Test
    fun persistsAcrossInstances() {
        clock = 7
        log().recordUsed("cred-1")
        assertTrue(log().of("cred-1")!!.lastUsedAt!!.endsWith(":07Z"))
    }

    @Test
    fun clearEmptiesTheLog() {
        val l = log()
        clock = 1
        l.recordAdded("cred-1")
        l.clear()
        assertNull(l.of("cred-1"))
    }

    @Test
    fun hardensFileBeforeMove() {
        // Which secret was used and when is audit metadata: the file gets private permissions on the
        // temp copy, before atomicMove, so the target never exists with umask-default permissions.
        val hardened = mutableListOf<String>()
        val l = FileCredentialUsageLog(path, fs, harden = { hardened += it.name }) { "2026-01-01T00:00:00Z" }
        l.recordAdded("cred-1")
        assertEquals(listOf("${path.name}.tmp"), hardened)
    }

    @Test
    fun corruptFileReadsAsEmpty() {
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("{ not json") }
        val l = log()
        assertNull(l.of("cred-1"))
        clock = 1
        l.recordAdded("cred-1")
        assertTrue(l.of("cred-1") != null)
    }
}
