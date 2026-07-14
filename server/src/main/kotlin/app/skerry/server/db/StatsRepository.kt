package app.skerry.server.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Aggregates for the admin console: counts and total ciphertext size only, no content. */
class StatsRepository(private val db: Database) {
    data class Counts(
        val accounts: Long,
        val devices: Long,
        val records: Long,
        val pairingSessions: Long,
        val storageBytes: Long,
    )

    suspend fun counts(): Counts = dbTransaction(db) {
        Counts(
            accounts = Accounts.selectAll().count(),
            // Active devices only: a revoked device is inert (no sync) and devices are never
            // deleted, so counting them would keep the tile climbing and never reflect a revoke.
            devices = Devices.selectAll().where { Devices.revoked eq false }.count(),
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
