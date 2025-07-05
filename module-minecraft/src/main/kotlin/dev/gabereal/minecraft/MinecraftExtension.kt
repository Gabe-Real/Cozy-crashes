/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.gabereal.minecraft

import dev.gabereal.minecraft.collections.*
import dev.gabereal.minecraft.database.DatabaseConfig
import dev.gabereal.minecraft.database.MinecraftNotificationService
import dev.gabereal.minecraft.services.MinecraftUpdateService
import dev.kord.common.annotation.KordPreview
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.Role
import dev.kord.core.entity.channel.Channel
import dev.kord.core.entity.channel.NewsChannel
import dev.kord.core.entity.channel.ResolvedChannel
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.embed
import dev.kordex.core.DISCORD_FUCHSIA
import dev.kordex.core.DISCORD_GREEN
import dev.kordex.core.DISCORD_YELLOW
import dev.kordex.core.checks.hasPermission
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.application.slash.ephemeralSubCommand
import dev.kordex.core.commands.converters.impl.channel as channelConverter
import dev.kordex.core.commands.converters.impl.optionalBoolean
import dev.kordex.core.commands.converters.impl.optionalBoolean
import dev.kordex.core.commands.converters.impl.optionalRole
import dev.kordex.core.commands.converters.impl.optionalString
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.i18n.toKey
import dev.kordex.core.pagination.pages.Page
import dev.kordex.core.utils.scheduling.Scheduler
import dev.kordex.core.utils.scheduling.Task
import dev.kordex.core.utils.toReaction
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone

private const val PAGINATOR_TIMEOUT = 60_000L  // One minute
private const val CHUNK_SIZE = 10
private const val CHECK_DELAY = 300L // 5 minutes

public class MinecraftExtension : Extension() {
	override val name: String = "minecraft"

	private val logger = KotlinLogging.logger { }
	private val scheduler = Scheduler()
	private var checkTask: Task? = null
	private val updateService = MinecraftUpdateService()

	@OptIn(KordPreview::class)
	override suspend fun setup() {
		// Initialize database (non-fatal if it fails)
		try {
			DatabaseConfig.init()
			logger.info { "Database initialized successfully" }
		} catch (e: Exception) {
			logger.error(e) { "Failed to initialize database - notification features will be disabled" }
		}

		// Start periodic update checking
		checkTask = scheduler.schedule(CHECK_DELAY, callback = ::checkForUpdates)
		logger.info { "Minecraft update checking started (every $CHECK_DELAY seconds)" }

		ephemeralSlashCommand {
			name = "minecraft".toKey()
			description = "Commands related to Minecraft updates and notifications".toKey()

			allowInDms = false

			ephemeralSubCommand {
				name = "latest".toKey()
				description = "Get the latest Minecraft release with changelog".toKey()

				action {
					val manifest = updateService.getLatestVersions()
					if (manifest == null) {
						respond { 
							content = "❌ **Failed to fetch Minecraft version information.** The Mojang API may be temporarily unavailable." 
						}
						return@action
					}

					// Get the latest release version
					val latestReleaseVersion = manifest.versions.find { 
						it.id == manifest.latest.release && it.type == "release" 
					}

					if (latestReleaseVersion == null) {
						respond { 
							content = "❌ **Could not find latest release version.** Please try again later." 
						}
						return@action
					}

					// Process the latest release to get changelog
					val update = updateService.processLatestRelease(latestReleaseVersion, manifest)
					if (update == null) {
						respond { 
							content = "❌ **Failed to process latest release information.** Please try again later." 
						}
						return@action
					}

					respond {
						embed {
							title = update.title
							color = DISCORD_GREEN

							description = update.description

							field {
								name = "Version"
								value = update.version
								inline = true
							}

							field {
								name = "Released"
								value = "<t:${update.releaseTime.epochSeconds}:F>"
								inline = true
							}

							field {
								name = "Type"
								value = "Official Release"
								inline = true
							}

							if (update.imageUrl != null) {
								thumbnail {
									url = update.imageUrl
								}
							}

							footer {
								text = "Latest Minecraft Release"
							}

							timestamp = update.releaseTime
						}

						actionRow {
							linkButton(update.changelogUrl) {
								label = "📋 View Full Changelog"
							}
							linkButton("https://www.minecraft.net/en-us/download") {
								label = "⬇️ Download Minecraft"
							}
						}
					}
				}
			}

			ephemeralSubCommand(::VersionArguments) {
				name = "version".toKey()
				description = "Get detailed information about a specific Minecraft version".toKey()

				action {
					val manifest = updateService.getLatestVersions()
					if (manifest == null) {
						respond { 
							content = "❌ **Failed to fetch Minecraft version information.** The Mojang API may be temporarily unavailable." 
						}
						return@action
					}

					val version = manifest.versions.find { 
						it.id.equals(arguments.version ?: manifest.latest.release, ignoreCase = true) 
					}

					if (version == null) {
						respond { 
							content = "❌ **Version '${arguments.version ?: "latest"}' not found.** Use `/minecraft latest` to see available versions." 
						}
						return@action
					}

					respond {
						embed {
							title = "📦 Minecraft ${version.id}"
							color = when (version.type) {
								"release" -> DISCORD_GREEN
								"snapshot" -> DISCORD_YELLOW
								else -> DISCORD_FUCHSIA
							}

							field {
								name = "Version Type"
								value = version.type.replaceFirstChar { it.uppercase() }
								inline = true
							}

							field {
								name = "Release Time"
								value = "<t:${kotlinx.datetime.Instant.parse(version.releaseTime).epochSeconds}:F>"
								inline = true
							}

							if (version.type == "release") {
								description = "This is an official release version of Minecraft."
							} else if (version.type == "snapshot") {
								description = "This is a development snapshot containing experimental features."
							}

							footer {
								text = "Version ID: ${version.id}"
							}
						}

						actionRow {
							linkButton("https://www.minecraft.net/en-us/updates") {
								label = "📋 View Changelog"
							}
						}
					}
				}
			}

			ephemeralSubCommand {
				name = "versions".toKey()
				description = "Browse all available Minecraft versions".toKey()

				action {
					val manifest = updateService.getLatestVersions()
					if (manifest == null) {
						respond { 
							content = "❌ **Failed to fetch Minecraft version information.** The Mojang API may be temporarily unavailable." 
						}
						return@action
					}

					editingPaginator {
						timeoutSeconds = PAGINATOR_TIMEOUT

						val releases = manifest.versions.filter { it.type == "release" }
						val snapshots = manifest.versions.filter { it.type == "snapshot" }

						// Releases page
						releases.chunked(CHUNK_SIZE).forEach { chunk ->
							page(
								Page {
									title = "🎮 Minecraft Releases"
									color = DISCORD_GREEN

									description = chunk.joinToString("\n") { version ->
										val time = kotlinx.datetime.Instant.parse(version.releaseTime)
										"**${version.id}** - <t:${time.epochSeconds}:d>"
									}

									footer {
										text = "${releases.size} release versions available"
									}
								}
							)
						}

						// Snapshots page
						if (snapshots.isNotEmpty()) {
							snapshots.take(50).chunked(CHUNK_SIZE).forEach { chunk ->
								page(
									Page {
										title = "📸 Minecraft Snapshots"
										color = DISCORD_YELLOW

										description = chunk.joinToString("\n") { version ->
											val time = kotlinx.datetime.Instant.parse(version.releaseTime)
											"**${version.id}** - <t:${time.epochSeconds}:d>"
										}

										footer {
											text = "${snapshots.size} snapshot versions available (showing recent 50)"
										}
									}
								)
							}
						}
					}.send()
				}
			}

			ephemeralSubCommand(::NotificationSetupArguments) {
				name = "setup".toKey()
				description = "Configure Minecraft update notifications for this server".toKey()

				check {
					hasPermission(Permission.ManageGuild)
				}

				action {
					val guildId = guild?.id ?: run {
						respond { content = "❌ This command can only be used in a server!" }
						return@action
					}

					// Resolve the channel
					val targetChannel = when (val channelArg = arguments.channel) {
						is TopGuildMessageChannel -> channelArg
						is TextChannel -> channelArg
						is NewsChannel -> channelArg
						is ResolvedChannel -> {
							try {
								when (val innerChannel = channelArg.fetchChannel()) {
									is TopGuildMessageChannel -> innerChannel
									is TextChannel -> innerChannel
									is NewsChannel -> innerChannel
									else -> {
										respond {
											content = "❌ **Unsupported channel type.** Please select a text or news channel."
										}
										return@action
									}
								}
							} catch (e: Exception) {
								logger.error(e) { "Failed to resolve channel" }
								respond {
									content = "❌ **Failed to access the selected channel.** Please ensure the bot has proper permissions."
								}
								return@action
							}
						}
						else -> {
							respond {
								content = "❌ **Unsupported channel type.** Please select a text or news channel."
							}
							return@action
						}
					}

					val config = MinecraftNotificationService.setConfig(
						guildId = guildId,
						channelId = targetChannel.id,
						pingRoleId = arguments.role?.id,
						includeSnapshots = arguments.includeSnapshots ?: false
					)

					if (config == null) {
						respond {
							content = "❌ **Failed to configure notifications.** The database may be unavailable. Please try again later."
						}
						return@action
					}

					val roleText = if (config.pingRoleId != null) {
						" and will ping <@&${config.pingRoleId}>"
					} else {
						""
					}

					val snapshotText = if (config.includeSnapshots) {
						" (including snapshots)"
					} else {
						" (releases only)"
					}

					respond {
						content = "✅ **Minecraft update notifications configured!**\n" +
								"📍 Updates will be sent to <#${config.channelId}>$roleText$snapshotText"
					}
				}
			}

			ephemeralSubCommand {
				name = "disable".toKey()
				description = "Disable Minecraft update notifications for this server".toKey()

				check {
					hasPermission(Permission.ManageGuild)
				}

				action {
					val guildId = guild?.id ?: run {
						respond { content = "❌ This command can only be used in a server!" }
						return@action
					}

					val success = MinecraftNotificationService.setEnabled(guildId, false)

					when (success) {
						null -> {
							respond { content = "❌ **Failed to update settings.** The database may be unavailable. Please try again later." }
						}
						true -> {
							respond { content = "🔕 **Minecraft update notifications disabled** for this server." }
						}
						false -> {
							respond { content = "❌ **No notification configuration found** for this server. Use `/minecraft setup` first." }
						}
					}
				}
			}

			ephemeralSubCommand {
				name = "enable".toKey()
				description = "Enable Minecraft update notifications for this server".toKey()

				check {
					hasPermission(Permission.ManageGuild)
				}

				action {
					val guildId = guild?.id ?: run {
						respond { content = "❌ This command can only be used in a server!" }
						return@action
					}

					val success = MinecraftNotificationService.setEnabled(guildId, true)

					when (success) {
						null -> {
							respond { content = "❌ **Failed to update settings.** The database may be unavailable. Please try again later." }
						}
						true -> {
							respond { content = "🔔 **Minecraft update notifications enabled** for this server." }
						}
						false -> {
							respond { content = "❌ **No notification configuration found** for this server. Use `/minecraft setup` first." }
						}
					}
				}
			}

			ephemeralSubCommand(::SnapshotToggleArguments) {
				name = "snapshots".toKey()
				description = "Toggle snapshot notifications for this server".toKey()

				check {
					hasPermission(Permission.ManageGuild)
				}

				action {
					val guildId = guild?.id ?: run {
						respond { content = "❌ This command can only be used in a server!" }
						return@action
					}

					val success = MinecraftNotificationService.setIncludeSnapshots(guildId, arguments.include ?: false)

					when (success) {
						null -> {
							respond { content = "❌ **Failed to update settings.** The database may be unavailable. Please try again later." }
						}
						true -> {
							val status = if (arguments.include ?: false) "enabled" else "disabled"
							respond { content = "📸 **Snapshot notifications $status** for this server." }
						}
						false -> {
							respond { content = "❌ **No notification configuration found** for this server. Use `/minecraft setup` first." }
						}
					}
				}
			}

			ephemeralSubCommand {
				name = "status".toKey()
				description = "Check the current notification configuration for this server".toKey()

				action {
					val guildId = guild?.id ?: run {
						respond { content = "❌ This command can only be used in a server!" }
						return@action
					}

					val config = MinecraftNotificationService.getConfig(guildId)

					if (config == null) {
						respond {
							content = "❌ **No notification configuration found** for this server or database is unavailable.\n" +
									"Use `/minecraft setup` to configure notifications."
						}
						return@action
					}

					val statusEmoji = if (config.enabled) "✅" else "❌"
					val statusText = if (config.enabled) "Enabled" else "Disabled"
					val roleText = if (config.pingRoleId != null) {
						"\n**📍 Ping Role:** <@&${config.pingRoleId}>"
					} else {
						"\n**📍 Ping Role:** None"
					}
					val snapshotText = if (config.includeSnapshots) {
						"\n**📸 Snapshots:** Included"
					} else {
						"\n**📸 Snapshots:** Releases only"
					}

					respond {
						content = "$statusEmoji **Minecraft Notifications:** $statusText\n" +
								"**📍 Channel:** <#${config.channelId}>$roleText$snapshotText"
					}
				}
			}

			ephemeralSubCommand {
				name = "remove".toKey()
				description = "Remove notification configuration for this server".toKey()

				check {
					hasPermission(Permission.ManageGuild)
				}

				action {
					val guildId = guild?.id ?: run {
						respond { content = "❌ This command can only be used in a server!" }
						return@action
					}

					val success = MinecraftNotificationService.removeConfig(guildId)

					when (success) {
						null -> {
							respond { content = "❌ **Failed to remove configuration.** The database may be unavailable. Please try again later." }
						}
						true -> {
							respond { content = "🗑️ **Minecraft notification configuration removed** for this server." }
						}
						false -> {
							respond { content = "❌ **No notification configuration found** for this server." }
						}
					}
				}
			}

			ephemeralSubCommand {
				name = "check".toKey()
				description = "Manually trigger a check for new Minecraft updates".toKey()

				check {
					hasPermission(Permission.Administrator)
				}

				action {
					respond { content = "🔍 **Checking for Minecraft updates...** This may take a moment." }

					checkTask?.callNow()
				}
			}
		}
	}

	private suspend fun checkForUpdates() {
		try {
			logger.debug { "Checking for Minecraft updates..." }
			
			val updates = updateService.checkForUpdates()
			
			if (updates.isEmpty()) {
				logger.debug { "No new Minecraft updates found" }
				return
			}

			logger.info { "Found ${updates.size} new Minecraft updates" }

			val configs = MinecraftNotificationService.getAllEnabledConfigs()
			if (configs.isEmpty()) {
				logger.debug { "No enabled notification configurations found" }
				return
			}

			for (update in updates) {
				// Track this version in database
				MinecraftNotificationService.addKnownVersion(
					version = update.version,
					versionType = update.type,
					releaseTime = update.releaseTime.toLocalDateTime(TimeZone.UTC),
					processed = true
				)

				// Send notifications to all configured guilds
				for (config in configs) {
					// Skip snapshots if guild doesn't want them
					if (update.isSnapshot && !config.includeSnapshots) {
						continue
					}

					try {
						val channel = kord.getChannelOf<TopGuildMessageChannel>(Snowflake(config.channelId))
						channel?.sendMinecraftUpdate(update, config.pingRoleId?.let { Snowflake(it) })
					} catch (e: Exception) {
						logger.warn(e) { "Failed to send notification to channel ${config.channelId} in guild ${config.guildId}" }
					}
				}
			}

		} catch (e: Exception) {
			logger.error(e) { "Error during update check" }
		} finally {
			// Schedule next check
			checkTask = scheduler.schedule(CHECK_DELAY, callback = ::checkForUpdates)
		}
	}

	private suspend fun TopGuildMessageChannel.sendMinecraftUpdate(
		update: ProcessedMinecraftUpdate,
		pingRoleId: Snowflake? = null
	) {
		val message = createMessage {
			// Ping the configured role if one is set
			if (pingRoleId != null) {
				content = "<@&$pingRoleId>"
			}

			updateEmbed(update)
		}

		// Create thread for discussion
		val threadTitle = if (update.isSnapshot) {
			"Snapshot ${update.version} Discussion"
		} else {
			"Minecraft ${update.version} Discussion"
		}

		when (this) {
			is TextChannel -> {
				try {
					startPublicThreadWithMessage(
						message.id, threadTitle.take(100) // Discord limit
					) { reason = "Thread created for Minecraft update discussion" }
				} catch (e: Exception) {
					logger.debug(e) { "Failed to create thread for update" }
				}
			}

			is NewsChannel -> {
				try {
					startPublicThreadWithMessage(
						message.id, threadTitle.take(100)
					) { reason = "Thread created for Minecraft update discussion" }
					message.publish()
				} catch (e: Exception) {
					logger.debug(e) { "Failed to create thread or publish message" }
				}
			}
		}
	}

	private fun MessageBuilder.updateEmbed(update: ProcessedMinecraftUpdate) {
		embed {
			title = update.title
			color = when {
				update.isNewRelease -> DISCORD_GREEN
				update.isSnapshot -> DISCORD_YELLOW
				else -> DISCORD_FUCHSIA
			}

			description = update.description

			field {
				name = "Version"
				value = update.version
				inline = true
			}

			field {
				name = "Type"
				value = update.type.replaceFirstChar { it.uppercase() }
				inline = true
			}

			field {
				name = "Released"
				value = "<t:${update.releaseTime.epochSeconds}:R>"
				inline = true
			}

			if (update.imageUrl != null) {
				thumbnail {
					url = update.imageUrl
				}
			}

			footer {
				text = "Minecraft Update Notification"
			}

			timestamp = Clock.System.now()
		}

		actionRow {
			linkButton(update.changelogUrl) {
				label = "📋 View Changelog"
			}
			linkButton("https://www.minecraft.net/en-us/download") {
				label = "⬇️ Download"
			}
		}
	}

	override suspend fun unload() {
		checkTask?.cancel()
		updateService.close()
		DatabaseConfig.close()
		super.unload()
	}

	@OptIn(KordPreview::class)
	public class VersionArguments : Arguments() {
		public val version: String? by optionalString {
			name = "version".toKey()
			description = "Specific version to get information for".toKey()
		}
	}

	@OptIn(KordPreview::class)
	public class NotificationSetupArguments : Arguments() {
		public val channel: Channel by channelConverter {
			name = "channel".toKey()
			description = "Channel to send Minecraft update notifications to".toKey()
		}

		public val role: Role? by optionalRole {
			name = "role".toKey()
			description = "Role to ping when updates are posted (optional)".toKey()
		}

		public val includeSnapshots: Boolean? by optionalBoolean {
			name = "include_snapshots".toKey()
			description = "Whether to include snapshot notifications (default: false)".toKey()
		}
	}

	@OptIn(KordPreview::class)
	public class SnapshotToggleArguments : Arguments() {
		public val include: Boolean? by optionalBoolean {
			name = "include".toKey()
			description = "Whether to include snapshot notifications".toKey()
		}
	}
}