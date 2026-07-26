package app.skerry.server.db

import app.skerry.server.metrics.InventorySnapshot
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.nio.file.Files
import java.nio.file.Path

/** Aggregates for the admin console: counts and total ciphertext size only, no content. */
class StatsRepository(private val db: Database) {
    data class Counts(
        val accounts: Long,
        val devices: Long,
        val records: Long,
        val pairingSessions: Long,
        val storageBytes: Long,
    )

    /** Cheapest possible liveness check for the database, used by the readiness probe. */
    suspend fun ping(): Unit = dbTransaction(db) {
        exec("SELECT 1") { }
    }

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

    /**
     * Everything the metrics inventory gauges need, in **one** transaction and one grouped query per
     * table rather than a count per number. Called only by the background collector — never during a
     * scrape: on the default SQLite deployment the pool is a single connection, so scanning `records`
     * on every scrape would compete with every push and pull.
     *
     * `CASE WHEN` over booleans and `LENGTH(blob)` are portable between SQLite (0/1, BLOB) and
     * PostgreSQL (boolean, bytea) — the same approach as [AdminRepository.accountSummaries].
     */
    suspend fun inventory(
        databaseUrl: String,
        now: Long = System.currentTimeMillis(),
    ): InventorySnapshot = dbTransaction(db) {
        fun row(sql: String, columns: List<String>): List<Long> =
            exec(sql) { rs -> if (rs.next()) columns.map { rs.getLong(it) } else columns.map { 0L } }
                ?: columns.map { 0L }

        val devices = row(
            """SELECT COUNT(*) AS total,
                      SUM(CASE WHEN revoked THEN 0 ELSE 1 END) AS active
               FROM devices""",
            listOf("total", "active"),
        )
        val records = row(
            """SELECT COUNT(*) AS total,
                      SUM(CASE WHEN deleted THEN 1 ELSE 0 END) AS tombstones,
                      COALESCE(SUM(LENGTH(blob)), 0) AS bytes
               FROM records""",
            listOf("total", "tombstones", "bytes"),
        )
        val teamRecords = row(
            """SELECT COUNT(*) AS total,
                      SUM(CASE WHEN deleted THEN 1 ELSE 0 END) AS tombstones,
                      COALESCE(SUM(LENGTH(blob)), 0) AS bytes
               FROM team_records""",
            listOf("total", "tombstones", "bytes"),
        )
        val pairing = row(
            """SELECT COUNT(*) AS total,
                      SUM(CASE WHEN expires_at < $now THEN 1 ELSE 0 END) AS expired
               FROM pairing""",
            listOf("total", "expired"),
        )
        val members = row(
            """SELECT COUNT(*) AS total,
                      SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END) AS active
               FROM team_members""",
            listOf("total", "active"),
        )

        InventorySnapshot(
            accounts = Accounts.selectAll().count(),
            activeDevices = devices[1],
            revokedDevices = devices[0] - devices[1],
            liveRecords = records[0] - records[1],
            tombstones = records[1],
            storageBytes = records[2],
            liveTeamRecords = teamRecords[0] - teamRecords[1],
            teamTombstones = teamRecords[1],
            teamStorageBytes = teamRecords[2],
            pendingPairings = pairing[0] - pairing[1],
            expiredPairings = pairing[1],
            teams = Teams.selectAll().count(),
            activeMembers = members[1],
            invitedMembers = members[0] - members[1],
            activityRows = ActivityLog.selectAll().count(),
            databaseBytes = databaseSizeBytes(databaseUrl),
        )
    }

    /**
     * Size on disk — more useful than the sum of blob lengths, because it includes indexes, the WAL
     * and free pages, i.e. what actually fills the volume. 0 when it can't be determined (an
     * in-memory database, or a PostgreSQL role without permission).
     */
    private fun JdbcTransaction.databaseSizeBytes(databaseUrl: String): Long = when {
        databaseUrl.startsWith("jdbc:postgresql") ->
            exec("SELECT pg_database_size(current_database()) AS bytes") { rs ->
                if (rs.next()) rs.getLong("bytes") else 0L
            } ?: 0L
        databaseUrl.startsWith("jdbc:sqlite:") -> {
            val file = databaseUrl.removePrefix("jdbc:sqlite:").substringBefore('?')
            runCatching {
                val path = Path.of(file)
                // A WAL-mode database keeps recent pages in the sidecar files; ignoring them would
                // under-report right after a write burst.
                listOf(path, Path.of("$file-wal"), Path.of("$file-shm"))
                    .filter { Files.exists(it) }
                    .sumOf { Files.size(it) }
            }.getOrDefault(0L)
        }
        else -> 0L
    }
}
