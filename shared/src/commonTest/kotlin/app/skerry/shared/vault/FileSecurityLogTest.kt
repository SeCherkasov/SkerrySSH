package app.skerry.shared.vault

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FileSecurityLog] is a local (non-syncing) security event log over [FakeFileSystem] (like
 * [FileVaultTest]). Covers ordering (newest first), the cap, the "last password change" derived
 * value, and survival across restart (re-reading the file with a new instance).
 */
class FileSecurityLogTest {
    private val fs = FakeFileSystem()
    private val path = "/cfg/security_events.json".toPath()

    // Controlled clock: the test sets timestamps itself so ordering/derived values are deterministic.
    private var clock = 0L
    private fun log(max: Int = 50) = FileSecurityLog(path, fs, max = max) { "2026-01-01T00:00:${clock.toString().padStart(2, '0')}Z" }

    @AfterTest
    fun tearDown() = fs.checkNoOpenFiles()

    @Test
    fun recentReturnsNewestFirst() {
        val l = log()
        clock = 1; l.record(SecurityEventType.VaultCreated)
        clock = 2; l.record(SecurityEventType.BiometricEnabled)
        clock = 3; l.record(SecurityEventType.UnlockedBiometric)

        val recent = l.recent()
        assertEquals(3, recent.size)
        assertEquals(SecurityEventType.UnlockedBiometric, recent[0].type)
        assertEquals(SecurityEventType.VaultCreated, recent[2].type)
    }

    @Test
    fun recentRespectsLimit() {
        val l = log()
        repeat(5) { clock = it.toLong(); l.record(SecurityEventType.UnlockedBiometric) }
        assertEquals(2, l.recent(limit = 2).size)
    }

    @Test
    fun capDropsOldestBeyondMax() {
        val l = log(max = 3)
        repeat(5) { clock = it.toLong(); l.record(SecurityEventType.UnlockedBiometric, detail = it.toString()) }
        val all = l.recent(limit = 100)
        assertEquals(3, all.size)
        // Newest (clock=4) first, oldest retained is clock=2 (0 and 1 evicted by the cap).
        assertEquals("4", all.first().detail)
        assertEquals("2", all.last().detail)
    }

    @Test
    fun lastPasswordChangeTracksCreateAndChange() {
        val l = log()
        assertNull(l.lastPasswordChangeAt())
        clock = 1; l.record(SecurityEventType.VaultCreated)
        assertTrue(l.lastPasswordChangeAt()!!.endsWith(":01Z"))
        // A non-password-related event does not move the timestamp.
        clock = 2; l.record(SecurityEventType.BiometricEnabled)
        assertTrue(l.lastPasswordChangeAt()!!.endsWith(":01Z"))
        clock = 5; l.record(SecurityEventType.MasterPasswordChanged)
        assertTrue(l.lastPasswordChangeAt()!!.endsWith(":05Z"))
    }

    @Test
    fun persistsAcrossInstances() {
        clock = 7; log().record(SecurityEventType.DevicePaired, detail = "iPhone 16 Pro")
        // A fresh instance reads the same file.
        val reopened = log().recent()
        assertEquals(1, reopened.size)
        assertEquals("iPhone 16 Pro", reopened[0].detail)
    }

    @Test
    fun clearEmptiesLog() {
        val l = log()
        clock = 1; l.record(SecurityEventType.VaultCreated)
        l.clear()
        assertTrue(l.recent().isEmpty())
        assertNull(l.lastPasswordChangeAt())
    }

    @Test
    fun hardensFileBeforeMove() {
        // The private-permissions hook is called on the temp file (before atomicMove), not the
        // target — the final file must not have a window with umask-default permissions.
        val hardened = mutableListOf<String>()
        val l = FileSecurityLog(path, fs, harden = { hardened += it.name }) { "2026-01-01T00:00:00Z" }
        l.record(SecurityEventType.VaultCreated)
        assertEquals(listOf("${path.name}.tmp"), hardened)
    }

    @Test
    fun corruptFileReadsAsEmpty() {
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("{ not json") }
        val l = log()
        assertTrue(l.recent().isEmpty())
        // Writing over a corrupt file restores a valid state.
        clock = 1; l.record(SecurityEventType.VaultCreated)
        assertEquals(1, l.recent().size)
    }
}
