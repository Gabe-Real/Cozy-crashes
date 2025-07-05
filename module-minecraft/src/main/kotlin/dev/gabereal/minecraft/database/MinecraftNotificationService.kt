/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.gabereal.minecraft.database

import dev.kord.common.entity.Snowflake
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone

public object MinecraftNotificationConfigs : LongIdTable("minecraft_notification_configs") {
    public val guildId: Column<Long> = long("guild_id").uniqueIndex()
    public val channelId: Column<Long> = long("channel_id")
    public val pingRoleId: Column<Long?> = long("ping_role_id").nullable()
    public val enabled: Column<Boolean> = bool("enabled").default(true)
    public val includeSnapshots: Column<Boolean> = bool("include_snapshots").default(false)
    public val createdAt: Column<kotlinx.datetime.LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    public val updatedAt: Column<kotlinx.datetime.LocalDateTime> = datetime("updated_at").defaultExpression(CurrentDateTime)
}

public object MinecraftKnownVersions : LongIdTable("minecraft_known_versions") {
    public val version: Column<String> = varchar("version", 50).uniqueIndex()
    public val versionType: Column<String> = varchar("version_type", 20)
    public val releaseTime: Column<kotlinx.datetime.LocalDateTime> = datetime("release_time")
    public val processed: Column<Boolean> = bool("processed").default(false)
    public val createdAt: Column<kotlinx.datetime.LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
}

public data class MinecraftNotificationConfig(
    val id: Long,
    val guildId: Long,
    val channelId: Long,
    val pingRoleId: Long?,
    val enabled: Boolean,
    val includeSnapshots: Boolean
)

public object MinecraftNotificationService {
    private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger { }
    
    private fun isDatabaseAvailable(): Boolean {
        return try {
            transaction {
                // Simple query to test database connectivity
                MinecraftNotificationConfigs.selectAll().limit(1).count() >= 0
            }
            true
        } catch (e: Exception) {
            logger.warn(e) { "Database is not available" }
            false
        }
    }
    
    public fun getConfig(guildId: Snowflake): MinecraftNotificationConfig? {
        if (!isDatabaseAvailable()) return null
        
        return try {
            transaction {
                MinecraftNotificationConfigs
                    .select { MinecraftNotificationConfigs.guildId eq guildId.value.toLong() }
                    .singleOrNull()
                    ?.let {
                        MinecraftNotificationConfig(
                            id = it[MinecraftNotificationConfigs.id].value,
                            guildId = it[MinecraftNotificationConfigs.guildId],
                            channelId = it[MinecraftNotificationConfigs.channelId],
                            pingRoleId = it[MinecraftNotificationConfigs.pingRoleId],
                            enabled = it[MinecraftNotificationConfigs.enabled],
                            includeSnapshots = it[MinecraftNotificationConfigs.includeSnapshots]
                        )
                    }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get config for guild $guildId" }
            null
        }
    }
    
    public fun setConfig(
        guildId: Snowflake,
        channelId: Snowflake,
        pingRoleId: Snowflake? = null,
        includeSnapshots: Boolean = false
    ): MinecraftNotificationConfig? {
        if (!isDatabaseAvailable()) return null
        
        return try {
            transaction {
                val existing = MinecraftNotificationConfigs
                    .select { MinecraftNotificationConfigs.guildId eq guildId.value.toLong() }
                    .singleOrNull()
                
                if (existing != null) {
                    // Update existing config
                    MinecraftNotificationConfigs.update({ MinecraftNotificationConfigs.guildId eq guildId.value.toLong() }) {
                        it[MinecraftNotificationConfigs.channelId] = channelId.value.toLong()
                        it[MinecraftNotificationConfigs.pingRoleId] = pingRoleId?.value?.toLong()
                        it[MinecraftNotificationConfigs.includeSnapshots] = includeSnapshots
                        it[MinecraftNotificationConfigs.updatedAt] = kotlinx.datetime.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.UTC)
                    }
                } else {
                    // Insert new config
                    MinecraftNotificationConfigs.insert {
                        it[MinecraftNotificationConfigs.guildId] = guildId.value.toLong()
                        it[MinecraftNotificationConfigs.channelId] = channelId.value.toLong()
                        it[MinecraftNotificationConfigs.pingRoleId] = pingRoleId?.value?.toLong()
                        it[MinecraftNotificationConfigs.includeSnapshots] = includeSnapshots
                    }
                }
                
                getConfig(guildId)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to set config for guild $guildId" }
            null
        }
    }
    
    public fun removeConfig(guildId: Snowflake): Boolean? {
        if (!isDatabaseAvailable()) return null
        
        return try {
            transaction {
                val deletedCount = MinecraftNotificationConfigs.deleteWhere { 
                    MinecraftNotificationConfigs.guildId.eq(guildId.value.toLong()) 
                }
                deletedCount > 0
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to remove config for guild $guildId" }
            null
        }
    }
    
    public fun setEnabled(guildId: Snowflake, enabled: Boolean): Boolean? {
        if (!isDatabaseAvailable()) return null
        
        return try {
            transaction {
                val updated = MinecraftNotificationConfigs.update({ MinecraftNotificationConfigs.guildId eq guildId.value.toLong() }) {
                    it[MinecraftNotificationConfigs.enabled] = enabled
                    it[MinecraftNotificationConfigs.updatedAt] = kotlinx.datetime.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.UTC)
                }
                updated > 0
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to set enabled status for guild $guildId" }
            null
        }
    }
    
    public fun setIncludeSnapshots(guildId: Snowflake, includeSnapshots: Boolean): Boolean? {
        if (!isDatabaseAvailable()) return null
        
        return try {
            transaction {
                val updated = MinecraftNotificationConfigs.update({ MinecraftNotificationConfigs.guildId eq guildId.value.toLong() }) {
                    it[MinecraftNotificationConfigs.includeSnapshots] = includeSnapshots
                    it[MinecraftNotificationConfigs.updatedAt] = kotlinx.datetime.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.UTC)
                }
                updated > 0
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to set snapshot inclusion for guild $guildId" }
            null
        }
    }
    
    public fun getAllEnabledConfigs(): List<MinecraftNotificationConfig> {
        if (!isDatabaseAvailable()) {
            logger.warn { "Database is not available, returning empty list of enabled configs" }
            return emptyList()
        }
        
        return try {
            transaction {
                MinecraftNotificationConfigs
                    .select { MinecraftNotificationConfigs.enabled eq true }
                    .map {
                        MinecraftNotificationConfig(
                            id = it[MinecraftNotificationConfigs.id].value,
                            guildId = it[MinecraftNotificationConfigs.guildId],
                            channelId = it[MinecraftNotificationConfigs.channelId],
                            pingRoleId = it[MinecraftNotificationConfigs.pingRoleId],
                            enabled = it[MinecraftNotificationConfigs.enabled],
                            includeSnapshots = it[MinecraftNotificationConfigs.includeSnapshots]
                        )
                    }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get enabled configs" }
            emptyList()
        }
    }
    
    // Version tracking methods
    
    public fun isVersionKnown(version: String): Boolean {
        if (!isDatabaseAvailable()) return false
        
        return try {
            transaction {
                MinecraftKnownVersions
                    .select { MinecraftKnownVersions.version eq version }
                    .count() > 0
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to check if version $version is known" }
            false
        }
    }
    
    public fun addKnownVersion(
        version: String, 
        versionType: String, 
        releaseTime: kotlinx.datetime.LocalDateTime,
        processed: Boolean = false
    ): Boolean {
        if (!isDatabaseAvailable()) return false
        
        return try {
            transaction {
                MinecraftKnownVersions.insertIgnore {
                    it[MinecraftKnownVersions.version] = version
                    it[MinecraftKnownVersions.versionType] = versionType
                    it[MinecraftKnownVersions.releaseTime] = releaseTime
                    it[MinecraftKnownVersions.processed] = processed
                }
                true
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to add known version $version" }
            false
        }
    }
    
    public fun markVersionProcessed(version: String): Boolean {
        if (!isDatabaseAvailable()) return false
        
        return try {
            transaction {
                MinecraftKnownVersions.update({ MinecraftKnownVersions.version eq version }) {
                    it[processed] = true
                }
                true
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark version $version as processed" }
            false
        }
    }
    
    public fun getUnprocessedVersions(): List<String> {
        if (!isDatabaseAvailable()) return emptyList()
        
        return try {
            transaction {
                MinecraftKnownVersions
                    .select { MinecraftKnownVersions.processed eq false }
                    .orderBy(MinecraftKnownVersions.releaseTime, SortOrder.DESC)
                    .map { it[MinecraftKnownVersions.version] }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get unprocessed versions" }
            emptyList()
        }
    }
}
