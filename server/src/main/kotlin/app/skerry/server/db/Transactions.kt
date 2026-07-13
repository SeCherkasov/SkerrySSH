package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Runs [block] in a suspending Exposed transaction on [db], offloaded to [Dispatchers.IO] so the
 * blocking JDBC work never runs on a caller's (e.g. Ktor request) dispatcher threads.
 *
 * Exposed 1.x replaced `newSuspendedTransaction(Dispatchers.IO, db)` with `suspendTransaction`,
 * which no longer accepts a dispatcher; the IO offload is expressed with `withContext` instead.
 */
internal suspend fun <T> dbTransaction(
    db: Database,
    block: suspend JdbcTransaction.() -> T,
): T = withContext(Dispatchers.IO) { suspendTransaction(db = db, statement = block) }
