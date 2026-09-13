package app.skerry.server.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class SchemaMigrationTest {

    @Test
    fun `createSchema on an existing populated schema migrates cleanly`() = withTestDb { db ->
        // withTestDb already ran createSchema once on a fresh file; a server restart runs it
        // again over the existing tables. sqlite-jdbc >= 3.50 reports an empty PK_NAME, which
        // makes Exposed emit "ALTER TABLE ... ADD PRIMARY KEY" — a statement SQLite cannot
        // execute. Both passes must succeed without it.
        Db.createSchema(db)
        seedAccount(db)
        Db.createSchema(db)
    }

    @Test
    fun `createSchema adds the columns a pre-existing devices table lacks and keeps its rows`() {
        // The migration the whole of createSchema exists for, against a schema that actually
        // predates the columns. The test above starts from Tables.kt, which declares them, so it
        // only ever proves the no-op pass is quiet — the ALTER TABLE ... ADD path never runs, and
        // that is the path sqlite-jdbc's column metadata decides.
        val file = Files.createTempFile("skerry-legacy-", ".db")
        try {
            val db = Database.connect("jdbc:sqlite:${file.toAbsolutePath()}", driver = "org.sqlite.JDBC")
            transaction(db) {
                exec(
                    """
                    CREATE TABLE devices (
                        id VARCHAR(64) NOT NULL,
                        account_id VARCHAR(320) NOT NULL,
                        name TEXT NOT NULL,
                        created_at BIGINT NOT NULL,
                        last_seen_at BIGINT NOT NULL,
                        revoked BOOLEAN DEFAULT 0 NOT NULL,
                        CONSTRAINT pk_devices PRIMARY KEY (account_id, id)
                    )
                    """.trimIndent(),
                )
                exec(
                    "INSERT INTO devices (id, account_id, name, created_at, last_seen_at, revoked) " +
                        "VALUES ('dev-1', 'alice@example.com', 'Laptop', 1, 2, 0)",
                )
            }

            Db.createSchema(db)

            transaction(db) {
                val rows = Devices.selectAll().toList()
                assertEquals(1, rows.size, "the migration dropped the existing device")
                val row = rows.single()
                assertEquals("dev-1", row[Devices.id])
                assertEquals("Laptop", row[Devices.name], "the migration lost the row's data")
                assertEquals(2L, row[Devices.lastSeenAt])
                // Added by the migration, so null for a device that predates them.
                assertNull(row[Devices.platform])
                assertNull(row[Devices.lastSyncVersion])
            }

            // Idempotent from the migrated state too: the second pass must find nothing to add.
            Db.createSchema(db)
            transaction(db) {
                assertTrue(Devices.selectAll().toList().size == 1, "the second pass changed the data")
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
