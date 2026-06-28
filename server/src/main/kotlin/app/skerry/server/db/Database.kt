package app.skerry.server.db

import app.skerry.server.config.ServerConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/** Подключение к БД и создание схемы. SQLite по умолчанию, PostgreSQL — по URL. */
object Db {
    fun connect(config: ServerConfig): Database {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            if (config.databaseUser.isNotEmpty()) username = config.databaseUser
            if (config.databasePassword.isNotEmpty()) password = config.databasePassword
            // SQLite — единственный писатель: один коннект исключает «database is locked».
            // PostgreSQL держит нормальный пул.
            maximumPoolSize = if (config.isPostgres) 10 else 1
        }
        val database = Database.connect(HikariDataSource(hikari))
        createSchema(database)
        return database
    }

    fun createSchema(database: Database) {
        transaction(database) {
            SchemaUtils.create(Accounts, Devices, Records, Pairing, ActivityLog)
        }
    }
}
