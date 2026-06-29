package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** Аккаунты: регистрация (SRP-верификатор + обёртка dataKey) и чтение. */
class AccountRepository(private val db: Database) {

    /** Бросает [IllegalStateException], если аккаунт уже существует. */
    suspend fun create(
        accountId: String,
        srpSalt: String,
        srpVerifier: String,
        wrappedDataKey: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): AccountRow = newSuspendedTransaction(Dispatchers.IO, db) {
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
            // Гонка между exists-проверкой и insert (PostgreSQL, pool>1): PK-нарушение трактуем
            // как «аккаунт уже есть» — тот же контракт, что у быстрой проверки выше.
            throw IllegalStateException("account already exists", e)
        }
        AccountRow(accountId, srpSalt, srpVerifier, wrappedDataKey, 0)
    }

    suspend fun find(accountId: String): AccountRow? = newSuspendedTransaction(Dispatchers.IO, db) {
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

    suspend fun exists(accountId: String): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        Accounts.selectAll().where { Accounts.id eq accountId }.any()
    }
}
