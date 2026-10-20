package com.discordlite.server.db

import com.discordlite.server.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * Thin wrapper over JDBC. Every query is plain, parameterised SQL — no string concatenation of
 * user input — run inside a transaction on the IO dispatcher.
 */
class Db(private val dataSource: DataSource) {

    suspend fun <T> tx(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.block().also { connection.commit() }
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }
    }

    companion object {
        fun dataSource(config: AppConfig): HikariDataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.dbUser
                password = config.dbPassword
                maximumPoolSize = 10
            },
        )

        fun migrate(dataSource: DataSource) {
            Flyway.configure().dataSource(dataSource).load().migrate()
        }
    }
}

fun Connection.exec(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { statement ->
        params.forEachIndexed { i, p -> statement.setObject(i + 1, p) }
        statement.executeUpdate()
    }

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        params.forEachIndexed { i, p -> statement.setObject(i + 1, p) }
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

/** Postgres unique-constraint violation. */
val SQLException.isUniqueViolation: Boolean get() = sqlState == "23505"
