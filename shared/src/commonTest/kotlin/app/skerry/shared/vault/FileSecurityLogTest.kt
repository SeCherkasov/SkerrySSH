package app.skerry.shared.vault

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FileSecurityLog] — локальный (не синкаемый) журнал событий безопасности поверх [FakeFileSystem]
 * (как [FileVaultTest]). Проверяем порядок (новые первыми), кап, дериватив «последняя смена пароля»
 * и переживание перезапуска (перечитывание файла новым инстансом).
 */
class FileSecurityLogTest {
    private val fs = FakeFileSystem()
    private val path = "/cfg/security_events.json".toPath()

    // Управляемые часы: тест сам задаёт штампы, чтобы порядок/дериватив были детерминированы.
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
        // Новейшее (clock=4) первым, самое старое из сохранённых — clock=2 (0 и 1 вытеснены капом).
        assertEquals("4", all.first().detail)
        assertEquals("2", all.last().detail)
    }

    @Test
    fun lastPasswordChangeTracksCreateAndChange() {
        val l = log()
        assertNull(l.lastPasswordChangeAt())
        clock = 1; l.record(SecurityEventType.VaultCreated)
        assertTrue(l.lastPasswordChangeAt()!!.endsWith(":01Z"))
        // Событие, не относящееся к паролю, метку не двигает.
        clock = 2; l.record(SecurityEventType.BiometricEnabled)
        assertTrue(l.lastPasswordChangeAt()!!.endsWith(":01Z"))
        clock = 5; l.record(SecurityEventType.MasterPasswordChanged)
        assertTrue(l.lastPasswordChangeAt()!!.endsWith(":05Z"))
    }

    @Test
    fun persistsAcrossInstances() {
        clock = 7; log().record(SecurityEventType.DevicePaired, detail = "iPhone 16 Pro")
        // Свежий инстанс читает тот же файл.
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
    fun corruptFileReadsAsEmpty() {
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("{ not json") }
        val l = log()
        assertTrue(l.recent().isEmpty())
        // Запись поверх битого файла восстанавливает валидное состояние.
        clock = 1; l.record(SecurityEventType.VaultCreated)
        assertEquals(1, l.recent().size)
    }
}
