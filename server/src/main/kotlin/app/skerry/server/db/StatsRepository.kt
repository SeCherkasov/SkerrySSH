package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** Aggregates for the admin console: counts and total ciphertext size only, no content. */
class StatsRepository(private val db: Database) {
    data class Counts(
        val accounts: Long,
        val devices: Long,
        val records: Long,
        val pairingSessions: Long,
        val storageBytes: Long,
    )

    suspend fun counts(): Counts = newSuspendedTransaction(Dispatchers.IO, db) {
        Counts(
            accounts = Accounts.selectAll().count(),
            devices = Devices.selectAll().count(),
            records = Records.selectAll().count(),
            pairingSessions = Pairing.selectAll().count(),
            // Total ciphertext size in bytes. `LENGTH(blob)` is computed DB-side (portable between
            // SQLite and PostgreSQL — bytea LENGTH also returns byte count); blobs aren't loaded
            // into memory.
            storageBytes = exec("SELECT COALESCE(SUM(LENGTH(blob)), 0) AS total FROM records") { rs ->
                if (rs.next()) rs.getLong("total") else 0L
            } ?: 0L,
        )
    }
}
