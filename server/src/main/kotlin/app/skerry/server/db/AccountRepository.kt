package app.skerry.server.db

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

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

    /**
     * Atomically rotates the account password (issue #32): swaps the SRP verifier (salt + verifier)
     * and the wrapped dataKey to the new password's, and revokes every device except [keepDeviceId] —
     * all in one transaction, so an interrupted rotation can't leave the verifier and the wrap out of
     * step. The dataKey itself is unchanged; only its wrap is.
     *
     * Revoking the other devices forces them to re-authenticate with the new password: their
     * stateless refresh tokens would otherwise survive the change (see [app.skerry.server.auth.TokenService]),
     * and a device re-logging in with the new password clears its own revocation.
     *
     * Returns the account's current `syncSeq` (for a live-pull nudge over the changes stream), or
     * `null` if the account doesn't exist.
     */
    suspend fun rotatePassword(
        accountId: String,
        newSrpSalt: String,
        newSrpVerifier: String,
        newWrappedDataKey: ByteArray,
        keepDeviceId: String,
    ): Long? = dbTransaction(db) {
        val updated = Accounts.update({ Accounts.id eq accountId }) {
            it[srpSalt] = newSrpSalt
            it[srpVerifier] = newSrpVerifier
            it[wrappedDataKey] = ExposedBlob(newWrappedDataKey)
        }
        if (updated == 0) return@dbTransaction null
        Devices.update({ (Devices.accountId eq accountId) and (Devices.id neq keepDeviceId) }) {
            it[revoked] = true
        }
        Accounts.selectAll().where { Accounts.id eq accountId }.single()[Accounts.syncSeq]
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
