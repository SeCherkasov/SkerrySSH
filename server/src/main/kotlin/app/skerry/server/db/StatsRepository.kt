package app.skerry.server.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Агрегаты для админ-консоли. Только счётчики и суммарный размер шифроблобов — содержимого нет. */
class StatsRepository(private val db: Database) {
    data class Counts(
        val accounts: Long,
        val devices: Long,
        val records: Long,
        val pairingSessions: Long,
        val storageBytes: Long,
    )

    fun counts(): Counts = transaction(db) {
        Counts(
            accounts = Accounts.selectAll().count(),
            devices = Devices.selectAll().count(),
            records = Records.selectAll().count(),
            pairingSessions = Pairing.selectAll().count(),
            // Суммарный размер шифроблобов в байтах. `LENGTH(blob)` считается на стороне БД
            // (переносимо между SQLite и PostgreSQL — для bytea LENGTH тоже отдаёт число байт),
            // блобы в память не грузим. Лишь размер-агрегат метаданных, к содержимому доступа нет.
            storageBytes = exec("SELECT COALESCE(SUM(LENGTH(blob)), 0) AS total FROM records") { rs ->
                if (rs.next()) rs.getLong("total") else 0L
            } ?: 0L,
        )
    }
}
