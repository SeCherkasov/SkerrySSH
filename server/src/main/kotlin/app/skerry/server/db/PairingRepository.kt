package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

/**
 * One-shot pairing sessions (variant B, design §3). The server stores the dataKey encrypted under
 * a one-time transferKey and cannot read it; the session lives until its TTL and burns on claim.
 */
class PairingRepository(private val db: Database) {

    suspend fun create(code: String, accountId: String, encryptedDataKey: ByteArray, expiresAt: Long): Unit =
        newSuspendedTransaction(Dispatchers.IO, db) {
            Pairing.insert {
                it[Pairing.code] = code
                it[Pairing.accountId] = accountId
                it[Pairing.encryptedDataKey] = ExposedBlob(encryptedDataKey)
                it[Pairing.expiresAt] = expiresAt
                it[consumed] = false
            }
        }

    /**
     * Atomically claims and burns the session. Returns `null` if the code doesn't exist, was
     * already claimed, or has expired (as of [now]).
     *
     * TOCTOU-safe: the claim is a single conditional UPDATE (`consumed=false AND expiresAt>now`),
     * not read-then-update. Only the transaction whose UPDATE actually changed a row (count==1)
     * wins and builds a [PairingRow]; a concurrent second claim of the same code updates 0 rows and
     * gets `null`. This guarantees a code can never be claimed twice, even under a race.
     */
    suspend fun consume(code: String, now: Long = System.currentTimeMillis()): PairingRow? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val claimed = Pairing.update({
                (Pairing.code eq code) and (Pairing.consumed eq false) and (Pairing.expiresAt greater now)
            }) {
                it[consumed] = true
            }
            if (claimed != 1) return@newSuspendedTransaction null
            // This transaction won the race; the session's immutable fields are now safe to read.
            val row = Pairing.selectAll().where { Pairing.code eq code }.single()
            PairingRow(
                code = row[Pairing.code],
                accountId = row[Pairing.accountId],
                encryptedDataKey = row[Pairing.encryptedDataKey].bytes,
                expiresAt = row[Pairing.expiresAt],
                consumed = true,
            )
        }

    suspend fun cleanupExpired(now: Long = System.currentTimeMillis()): Int =
        newSuspendedTransaction(Dispatchers.IO, db) {
            Pairing.deleteWhere { expiresAt lessEq now }
        }
}
