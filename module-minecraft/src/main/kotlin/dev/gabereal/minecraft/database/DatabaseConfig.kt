/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.gabereal.minecraft.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.kordex.core.utils.env
import dev.kordex.core.utils.envOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

public object DatabaseConfig {
    private val logger = KotlinLogging.logger { }
    
    private val dbUrl: String by lazy {
        // Get DB_URL from environment and convert to JDBC format if needed
        val envUrl = envOrNull("DB_URL") ?: throw IllegalStateException("DB_URL environment variable not set")
        if (envUrl.startsWith("mariadb://")) {
            "jdbc:$envUrl"
        } else {
            envUrl
        }
    }
    
    private lateinit var dataSource: HikariDataSource
    
    public fun init() {
        logger.info { "Initializing database connection using DB_URL" }
        
        // Parse the URL to extract components
        val url = java.net.URI(dbUrl.removePrefix("jdbc:"))
        val userInfo = url.userInfo?.split(":") ?: throw IllegalStateException("Database credentials not found in URL")
        val dbUser = userInfo[0]
        val dbPassword = userInfo.getOrNull(1) ?: ""
        
        // Reconstruct the JDBC URL without credentials
        val jdbcUrl = "jdbc:mariadb://${url.host}:${url.port}${url.path}?useSSL=false&allowPublicKeyRetrieval=true"
        
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = dbUser
            password = dbPassword
            driverClassName = "org.mariadb.jdbc.Driver"
            
            // Connection pool settings
            maximumPoolSize = 10
            minimumIdle = 5
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000
            
            // Validation
            isAutoCommit = true
            connectionTestQuery = "SELECT 1"
        }
        
        dataSource = HikariDataSource(config)
        
        Database.connect(dataSource)
        
        // Create tables if they don't exist
        transaction {
            SchemaUtils.create(MinecraftNotificationConfigs, MinecraftKnownVersions)
        }
        
        logger.info { "Database initialization completed" }
    }
    
    public fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
            logger.info { "Database connection closed" }
        }
    }
}
