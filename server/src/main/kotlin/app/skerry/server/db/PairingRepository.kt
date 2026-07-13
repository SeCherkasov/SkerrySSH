package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update

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
