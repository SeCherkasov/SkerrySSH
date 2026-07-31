package app.skerry.server.db

import app.skerry.server.auth.WebPasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * Sets or rotates the web password ([app.skerry.server.auth.WebPasswordHasher]). Returns false
     * if the account doesn't exist.
     *
     * Rotation deliberately revokes nothing: it is a change of credential, so a browser already
     * holding a valid token stays signed in until it expires. Closing an open session is what
     * [clearWebPassword] is for.
     */
    suspend fun setWebPassword(accountId: String, password: String): Boolean {
        // Hashing is ~19 MiB of CPU work; keep it off the request thread and, on SQLite, out of the
        // single pooled connection a transaction would hold for its duration.
        val encoded = withContext(Dispatchers.Default) { WebPasswordHasher.hash(password) }
        return dbTransaction(db) {
            Accounts.update({ Accounts.id eq accountId }) { it[webPasswordHash] = encoded } > 0
        }
    }

    /**
     * Clears the web password and revokes the account's live web session in **one** transaction,
     * returning the device ids revoked (empty if none were open), or `null` if the account doesn't
     * exist.
     *
     * Both halves or neither: removing the password has to close the door that is already open, and
     * a clear that committed without the revoke would leave a browser signed in with no credential
     * left to take away. Revocation is the same state `DELETE /devices/{id}` sets, so the access
     * token is rejected by the JWT validator and the refresh token by `/auth/refresh`.
     */
    suspend fun clearWebPassword(accountId: String): List<String>? = dbTransaction(db) {
        val exists = Accounts.selectAll().where { Accounts.id eq accountId }.any()
        if (!exists) return@dbTransaction null
        Accounts.update({ Accounts.id eq accountId }) { it[webPasswordHash] = null }
        val open = Devices.selectAll()
            .where {
                (Devices.accountId eq accountId) and (Devices.platform eq WebSession.PLATFORM) and
                    (Devices.revoked eq false)
            }
            .map { it[Devices.id] }
        if (open.isNotEmpty()) {
            Devices.update({ (Devices.accountId eq accountId) and (Devices.platform eq WebSession.PLATFORM) }) {
                it[revoked] = true
            }
        }
        open
    }

    /** The stored PHC hash, or null when the account has no web access (or doesn't exist). */
    suspend fun webPasswordHash(accountId: String): String? = dbTransaction(db) {
        Accounts.selectAll().where { Accounts.id eq accountId }.singleOrNull()?.get(Accounts.webPasswordHash)
    }

    /**
     * Whether [password] opens the web zone for [accountId]. An unknown account and one with no web
     * password take the same path and the same time as a wrong password — see
     * [WebPasswordHasher.verifyMiss]; anything cheaper is an enumeration oracle.
     */
    suspend fun verifyWebPassword(accountId: String, password: String): Boolean {
        val stored = webPasswordHash(accountId)
        return withContext(Dispatchers.Default) {
            if (stored == null) WebPasswordHasher.verifyMiss(password) else WebPasswordHasher.verify(password, stored)
        }
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
