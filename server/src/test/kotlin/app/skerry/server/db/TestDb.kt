package app.skerry.server.db

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Files

/**
 * Isolated SQLite database on a temp file for repository tests. A file, not in-memory: Exposed
 * opens its own connection per transaction, and `:memory:` is per-connection.
 *
 * Suspend block: repositories run inside `dbTransaction`, so the test body runs inside
 * [runBlocking] to keep the synchronous call style.
 */
fun withTestDb(block: suspend (Database) -> Unit) {
    val file = Files.createTempFile("skerry-test-", ".db")
    try {
        val db = Database.connect("jdbc:sqlite:${file.toAbsolutePath()}", driver = "org.sqlite.JDBC")
        Db.createSchema(db)
        runBlocking { block(db) }
    } finally {
        Files.deleteIfExists(file)
    }
}

suspend fun seedAccount(db: Database, accountId: String = "alice@example.com") {
    AccountRepository(db).create(
        accountId = accountId,
        srpSalt = "00",
        srpVerifier = "ab",
        wrappedDataKey = byteArrayOf(1, 2, 3),
    )
}
