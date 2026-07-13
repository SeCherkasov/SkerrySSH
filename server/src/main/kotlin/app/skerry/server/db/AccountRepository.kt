package app.skerry.server.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Accounts: registration (SRP verifier + wrapped dataKey) and lookup. */
class AccountRepository(private val db: Database) {

    /** Throws [IllegalStateException] if the account already exists. */
    suspend fun create(
        accountId: String,
        srpSalt: String,
        srpVerifier: String,
        wrappedDataKey: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): AccountRow = dbTransaction(db) {
        val exists = Accounts.selectAll().where { Accounts.id eq accountId }.any()
        check(!exists) { "account already exists" }
        try {
            Accounts.insert {
                it[id] = accountId
                it[Accounts.srpSalt] = srpSalt
                it[Accounts.srpVerifier] = srpVerifier
                it[Accounts.wrappedDataKey] = ExposedBlob(wrappedDataKey)
                it[syncSeq] = 0
                it[createdAt] = now
            }
        } catch (e: ExposedSQLException) {
            // Race between the exists check and insert (PostgreSQL, pool>1): treat a PK violation
            // as "account already exists", same contract as the check above.
            throw IllegalStateException("account already exists", e)
        }
        AccountRow(accountId, srpSalt, srpVerifier, wrappedDataKey, 0)
    }

    /** Total number of registered accounts (for the optional per-instance registration cap). */
    suspend fun count(): Long = dbTransaction(db) {
        Accounts.selectAll().count()
    }

    suspend fun find(accountId: String): AccountRow? = dbTransaction(db) {
        Accounts.selectAll().where { Accounts.id eq accountId }.singleOrNull()?.let {
            AccountRow(
                id = it[Accounts.id],
                srpSalt = it[Accounts.srpSalt],
                srpVerifier = it[Accounts.srpVerifier],
                wrappedDataKey = it[Accounts.wrappedDataKey].bytes,
                syncSeq = it[Accounts.syncSeq],
            )
        }
    }
}
