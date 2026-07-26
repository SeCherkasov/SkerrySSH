package app.skerry.server.db

import app.skerry.server.config.ServerConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata

/** Database connection and schema creation. SQLite by default, PostgreSQL via URL. */
object Db {
    /**
     * [meterRegistry] (when given) makes HikariCP publish `hikaricp_connections*` — on the default
     * SQLite deployment that is the most informative metric there is, since the pool is a single
     * connection and `hikaricp_connections_pending` is therefore direct evidence of contention.
     */
    fun connect(config: ServerConfig, meterRegistry: MeterRegistry? = null): Database {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            if (config.databaseUser.isNotEmpty()) username = config.databaseUser
            if (config.databasePassword.isNotEmpty()) password = config.databasePassword
            // SQLite is single-writer: one connection avoids "database is locked".
            // PostgreSQL uses a normal pool.
            maximumPoolSize = if (config.isPostgres) 10 else 1
            if (meterRegistry != null) metricsTrackerFactory = MicrometerMetricsTrackerFactory(meterRegistry)
        }
        val database = Database.connect(HikariDataSource(hikari))
        createSchema(database)
        return database
    }

    // Inlined createMissingTablesAndColumns (create + add-missing-columns + mapping consistence):
    // migrates by adding new nullable columns (Devices.platform, Devices.lastSyncVersion) to an
    // existing database without losing data. Inlined because sqlite-jdbc >= 3.50 reports
    // supportsAlterTableWithAddColumn = true, which routes Exposed (through 0.61) into schema-sync
    // paths whose metadata reads misfire on SQLite: an empty PK_NAME becomes "ADD PRIMARY KEY",
    // autoincrement misdetection becomes "MODIFY COLUMN" — statements SQLite cannot execute and
    // this schema does not need. On SQLite keep only what it supports and this migration relies
    // on: creating tables/indexes and adding new columns. Other dialects run everything.
    @Suppress("DEPRECATION")
    fun createSchema(database: Database) {
        val tables = arrayOf(
            Accounts, Devices, Records, Pairing, ActivityLog,
            AccountKeys, Teams, TeamMembers, TeamRecords, TeamScopes, TeamScopeGrants,
        )
        transaction(database) {
            val isSqlite = currentDialect is SQLiteDialect
            fun supported(sql: String) = !isSqlite || sql.startsWith("CREATE ") ||
                (sql.startsWith("ALTER TABLE") && " ADD " in sql && "CONSTRAINT" !in sql && "PRIMARY KEY" !in sql)
            // Like the original: without a reset, index metadata cached before the create phase
            // makes checkMappingConsistence re-emit CREATE INDEX for indexes that already exist.
            currentDialectMetadata.resetCaches()
            val created = SchemaUtils.createStatements(tables = tables)
            created.forEach { exec(it) }
            commit()
            val altered = SchemaUtils.addMissingColumnsStatements(tables = tables).filter(::supported)
            altered.forEach { exec(it) }
            commit()
            val executed = created + altered
            SchemaUtils.checkMappingConsistence(tables = tables)
                .filter { it !in executed && supported(it) }
                .forEach { exec(it) }
            currentDialectMetadata.resetCaches()
        }
    }
}
