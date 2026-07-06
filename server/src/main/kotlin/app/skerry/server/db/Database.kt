package app.skerry.server.db

import app.skerry.server.config.ServerConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/** Database connection and schema creation. SQLite by default, PostgreSQL via URL. */
object Db {
    fun connect(config: ServerConfig): Database {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            if (config.databaseUser.isNotEmpty()) username = config.databaseUser
            if (config.databasePassword.isNotEmpty()) password = config.databasePassword
            // SQLite is single-writer: one connection avoids "database is locked".
            // PostgreSQL uses a normal pool.
            maximumPoolSize = if (config.isPostgres) 10 else 1
        }
        val database = Database.connect(HikariDataSource(hikari))
        createSchema(database)
        return database
    }

    fun createSchema(database: Database) {
        transaction(database) {
            // createMissingTablesAndColumns (not create): migrates by adding new nullable columns
            // (Devices.platform, Devices.lastSyncVersion) to an existing database without losing data.
            SchemaUtils.createMissingTablesAndColumns(
                Accounts, Devices, Records, Pairing, ActivityLog,
                AccountKeys, Teams, TeamMembers, TeamRecords,
            )
        }
    }
}
