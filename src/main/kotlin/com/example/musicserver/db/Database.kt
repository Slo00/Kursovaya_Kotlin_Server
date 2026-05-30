package com.example.musicserver.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

object Db {

    fun init() {
        val jdbcUrl = System.getenv("DATABASE_URL")
            ?: "jdbc:postgresql://localhost:5432/musicapp"
        val username = System.getenv("DATABASE_USER") ?: "music"
        val password = System.getenv("DATABASE_PASSWORD") ?: "music"

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }

        Database.connect(HikariDataSource(config))

        transaction {
            SchemaUtils.create(
                Users,
                Albums,
                Tracks,
                Playlists,
                PlaylistTracks,
                Favorites
            )
            // Idempotent migration for pre-existing databases that lack the role column.
            TransactionManager.current().exec(
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER'"
            )
        }

        println("Database connected: $jdbcUrl")
    }
}
