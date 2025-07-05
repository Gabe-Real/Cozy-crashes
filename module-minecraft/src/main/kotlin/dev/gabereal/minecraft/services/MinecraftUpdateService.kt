/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.gabereal.minecraft.services

import dev.gabereal.minecraft.collections.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.hours

public class MinecraftUpdateService {
    private val logger = KotlinLogging.logger { }
    
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
        }
        expectSuccess = false
    }
    
    private companion object {
        const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val MINECRAFT_NEWS_URL = "https://www.minecraft.net/en-us/article"
        const val MINECRAFT_BASE_URL = "https://www.minecraft.net"
        const val MINECRAFT_DEFAULT_IMAGE = "https://www.minecraft.net/content/dam/games/minecraft/logos/mc-logo.png"
    }
    
    private var lastCheckedVersions: Set<String> = emptySet()
    private var lastCheckTime: Instant = Clock.System.now()
    
    /**
     * Get the latest Minecraft version information
     */
    public suspend fun getLatestVersions(): MinecraftVersionManifest? {
        return try {
            logger.debug { "Fetching version manifest from Mojang API" }
            val response = client.get(VERSION_MANIFEST_URL)
            if (response.status.value in 200..299) {
                response.body<MinecraftVersionManifest>()
            } else {
                logger.warn { "Failed to fetch version manifest: ${response.status}" }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Error fetching Minecraft version manifest" }
            null
        }
    }
    
    /**
     * Check for new Minecraft updates since last check
     */
    public suspend fun checkForUpdates(): List<ProcessedMinecraftUpdate> {
        val manifest = getLatestVersions() ?: return emptyList()
        val currentTime = Clock.System.now()
        
        // Get recent versions (within the last 30 days or versions we haven't seen)
        val recentVersions = manifest.versions.filter { version ->
            val versionTime = Instant.parse(version.releaseTime)
            versionTime > (currentTime - (30 * 24).hours) || version.id !in lastCheckedVersions
        }
        
        val newUpdates = mutableListOf<ProcessedMinecraftUpdate>()
        
        for (version in recentVersions.take(5)) { // Limit to 5 most recent to avoid spam
            try {
                val update = processVersionUpdate(version, manifest)
                if (update != null && version.id !in lastCheckedVersions) {
                    newUpdates.add(update)
                }
            } catch (e: Exception) {
                logger.error(e) { "Error processing version ${version.id}" }
            }
        }
        
        // Update our tracking
        lastCheckedVersions = manifest.versions.take(20).map { it.id }.toSet() // Keep track of last 20 versions
        lastCheckTime = currentTime
        
        return newUpdates.sortedByDescending { it.releaseTime }
    }
    
    private suspend fun processVersionUpdate(
        version: MinecraftVersion,
        manifest: MinecraftVersionManifest
    ): ProcessedMinecraftUpdate? {
        val releaseTime = Instant.parse(version.releaseTime)
        val isSnapshot = version.type == "snapshot"
        val isNewRelease = version.id == manifest.latest.release && version.type == "release"
        
        // Generate title and description based on version type
        val (title, description) = when {
            isNewRelease -> {
                "🎉 Minecraft ${version.id} Released!" to 
                "A new version of Minecraft has been released! Update your game to experience the latest features, improvements, and bug fixes."
            }
            isSnapshot -> {
                "📸 Minecraft Snapshot ${version.id}" to
                "A new snapshot is available for testing! This development version contains experimental features and changes that may be included in future releases."
            }
            version.type == "release" -> {
                "🎮 Minecraft ${version.id} Update" to
                "Minecraft has been updated to version ${version.id}. Check out the changelog for details on what's new!"
            }
            else -> {
                "🔧 Minecraft ${version.id} (${version.type})" to
                "A new ${version.type} version of Minecraft is available."
            }
        }
        
        // Try to find changelog URL by scraping or using known patterns
        val changelogUrl = findChangelogUrl(version.id, isSnapshot)
        
        return ProcessedMinecraftUpdate(
            version = version.id,
            type = version.type,
            releaseTime = releaseTime,
            title = title,
            description = description,
            imageUrl = getVersionImageUrl(version.id, isSnapshot),
            changelogUrl = changelogUrl,
            isSnapshot = isSnapshot,
            isNewRelease = isNewRelease
        )
    }
    
    private suspend fun findChangelogUrl(version: String, isSnapshot: Boolean): String {
        // Try different URL patterns for changelogs
        val possibleUrls = if (isSnapshot) {
            listOf(
                "https://www.minecraft.net/en-us/article/minecraft-snapshot-${version.replace("w", "").replace("pre", "-pre").replace("rc", "-rc")}",
                "https://www.minecraft.net/en-us/article/minecraft-snapshot-${version}",
                "https://feedback.minecraft.net/hc/en-us/sections/360001186971-Release-Changelogs"
            )
        } else {
            listOf(
                "https://www.minecraft.net/en-us/article/minecraft-java-edition-${version.replace(".", "-")}",
                "https://www.minecraft.net/en-us/article/minecraft-${version.replace(".", "-")}",
                "https://feedback.minecraft.net/hc/en-us/sections/360001186971-Release-Changelogs"
            )
        }
        
        for (url in possibleUrls) {
            try {
                val response = client.get(url)
                if (response.status.value in 200..299) {
                    return url
                }
            } catch (e: Exception) {
                // Continue to next URL
            }
        }
        
        // Fallback to general changelog section
        return if (isSnapshot) {
            "https://feedback.minecraft.net/hc/en-us/sections/360001186971-Release-Changelogs"
        } else {
            "https://www.minecraft.net/en-us/updates"
        }
    }
    
    private fun getVersionImageUrl(version: String, isSnapshot: Boolean): String {
        return if (isSnapshot) {
            "https://www.minecraft.net/content/dam/games/minecraft/key-art/Experimental-Gameplay_Thumbnail.jpg"
        } else {
            MINECRAFT_DEFAULT_IMAGE
        }
    }
    
    /**
     * Get detailed version information
     */
    public suspend fun getVersionDetails(version: MinecraftVersion): MinecraftVersionDetails? {
        return try {
            client.get(version.url).body<MinecraftVersionDetails>()
        } catch (e: Exception) {
            logger.error(e) { "Error fetching details for version ${version.id}" }
            null
        }
    }
    
    /**
     * Scrape Minecraft news for additional information
     */
    public suspend fun scrapeMinecraftNews(): List<MinecraftNewsEntry> {
        return try {
            logger.debug { "Scraping Minecraft news" }
            val response = client.get(MINECRAFT_NEWS_URL)
            if (response.status.value !in 200..299) {
                logger.warn { "Failed to fetch Minecraft news: ${response.status}" }
                return emptyList()
            }
            
            val html: String = response.body()
            val document: Document = Jsoup.parse(html)
            
            val newsEntries = mutableListOf<MinecraftNewsEntry>()
            
            // Look for article cards or news items
            val articles = document.select("article, .article-card, .news-item, .card")
            
            for (article in articles.take(10)) { // Limit to 10 recent articles
                try {
                    val titleElement = article.select("h1, h2, h3, .title, .article-title").first()
                    val title = titleElement?.text()?.trim() ?: continue
                    
                    val linkElement = article.select("a").first()
                    val relativeUrl = linkElement?.attr("href") ?: continue
                    val fullUrl = if (relativeUrl.startsWith("http")) relativeUrl else "$MINECRAFT_BASE_URL$relativeUrl"
                    
                    val descElement = article.select("p, .description, .excerpt").first()
                    val description = descElement?.text()?.trim() ?: ""
                    
                    val imageElement = article.select("img").first()
                    val imageUrl = imageElement?.attr("src")?.let { src ->
                        if (src.startsWith("http")) src else "$MINECRAFT_BASE_URL$src"
                    }
                    
                    // Try to extract date
                    val dateElement = article.select(".date, .published, time").first()
                    val publishedDate = dateElement?.text()?.trim() ?: ""
                    
                    newsEntries.add(
                        MinecraftNewsEntry(
                            title = title,
                            description = description,
                            publishedDate = publishedDate,
                            url = fullUrl,
                            imageUrl = imageUrl
                        )
                    )
                } catch (e: Exception) {
                    logger.debug(e) { "Error parsing news article" }
                }
            }
            
            newsEntries
        } catch (e: Exception) {
            logger.error(e) { "Error scraping Minecraft news" }
            emptyList()
        }
    }
    
    public fun close() {
        client.close()
    }
}
