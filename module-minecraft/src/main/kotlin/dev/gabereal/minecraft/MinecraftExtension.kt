/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.gabereal.minecraft

import dev.gabereal.minecraft.collections.PatchNote
import dev.gabereal.minecraft.collections.PatchNoteEntries
import dev.gabereal.minecraft.collections.PatchNoteEntry
import dev.gabereal.minecraft.collections.VersionManifest
import dev.gabereal.minecraft.database.DatabaseConfig
import dev.gabereal.minecraft.database.MinecraftNotificationService
import dev.kord.common.annotation.KordPreview
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.builder.components.emoji
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
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
import dev.kordex.core.checks.hasPermission
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.application.slash.ephemeralSubCommand
import dev.kordex.core.commands.converters.impl.channel as channelConverter
import dev.kordex.core.commands.converters.impl.message
import dev.kordex.core.commands.converters.impl.optionalRole
import dev.kordex.core.commands.converters.impl.optionalString
import dev.kordex.core.commands.converters.impl.string
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.i18n.toKey
import dev.kordex.core.pagination.pages.Page
import dev.kordex.core.utils.envOrNull
import dev.kordex.core.utils.scheduling.Scheduler
import dev.kordex.core.utils.scheduling.Task
import dev.kordex.core.utils.toReaction
import dev.kordex.parser.Cursor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.apache.commons.text.StringEscapeUtils
import kotlin.system.exitProcess

private const val PAGINATOR_TIMEOUT = 60_000L  // One minute
private const val CHUNK_SIZE = 10
private val MAIN_GUILD_ID = envOrNull("MAIN_GUILD_ID")

// 1) Piston‑Meta manifest for “latest.release” & “latest.snapshot”
private const val MOJANG_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest.json"
// 2) **Old** patchnotes API (your existing endpoint)
private const val PATCHNOTE_URL = "https://launchercontent.mojang.com/v2/javaPatchNotes.json"
private const val BASE_URL = "https://launchercontent.mojang.com/v2"

private const val CHECK_DELAY = 60L

private val LINK_REGEX = "<a href=\"?(?<url>[^\"\\s]+)\"?[^>]*>(?<text>[^<]+)</a>".toRegex()

@Suppress("MagicNumber", "UnderscoresInNumericLiterals")
private val CHANNELS: List<Snowflake> = listOf(
	Snowflake(1384066726613876789),  // Community
)

public class MinecraftExtension : Extension() {
	override val name: String = "minecraft"

	private val logger = KotlinLogging.logger { }

	private val client = HttpClient {
		install(ContentNegotiation) {
			json(
				Json {
					ignoreUnknownKeys = true
				}
			)
		}

		expectSuccess = true
	}

	private val scheduler = Scheduler()

	private var checkTask: Task? = null
	private var knownVersions: MutableSet<String> = mutableSetOf()
	private lateinit var currentEntries: PatchNoteEntries

	@OptIn(KordPreview::class)
	override suspend fun setup() {
		// Initialize database (non-fatal if it fails)
		try {
			DatabaseConfig.init()
			logger.info { "Database initialized successfully" }
		} catch (e: Exception) {
			logger.error(e) { "Failed to initialize database - notification features will be disabled" }
		}

		populateVersions()

		checkTask = scheduler.schedule(CHECK_DELAY, callback = ::checkTask)

		ephemeralSlashCommand {
			name = "mc".toKey()
			description = "Commands related to Minecraft updates".toKey()

			allowInDms = false

			guild(guildId)

			ephemeralSubCommand(::CheckArguments) {
				name = "get".toKey()
				description = "Retrieve the patch notes for a given Minecraft version, or the latest if not supplied".toKey()

				action {
					if (!::currentEntries.isInitialized) {
						respond { content = "Still setting up - try again a bit later!" }
						return@action
					}

					val patch = if (arguments.version == null) {
						currentEntries.entries.first()
					} else {
						currentEntries.entries.firstOrNull { it.version.equals(arguments.version, true) }
					}

					if (patch == null) {
						respond { content = "Unknown version supplied: `${arguments.version}`" }
						return@action
					}

					respond {
						patchNotes(patch.get())
					}
				}
			}

			ephemeralSubCommand {
				name = "versions".toKey()
				description = "Get a list of patch note versions.".toKey()

				action {
					if (!::currentEntries.isInitialized) {
						respond { content = "Still setting up - try again a bit later!" }

						return@action
					}

					editingPaginator {
						timeoutSeconds = PAGINATOR_TIMEOUT

						knownVersions.chunked(CHUNK_SIZE).forEach { chunk ->
							page(
								Page {
									title = "Patch note versions"
									color = DISCORD_FUCHSIA

									description = chunk.joinToString("\n") { "**»** `$it`" }

									footer {
										text = "${currentEntries.entries.size} versions"
									}
								}
							)
						}
					}.send()
				}
			}

			ephemeralSubCommand(::CheckArguments) {
				name = "forget".toKey()
				description = "Forget a version (the last one by default), allowing it to be relayed again.".toKey()

				check {
					hasPermission(Permission.Administrator)
				}

				action {
					if (!::currentEntries.isInitialized) {
						respond { content = "Still setting up - try again a bit later!" }
						return@action
					}

					val version = if (arguments.version == null) {
						currentEntries.entries.first().version
					} else {
						currentEntries.entries.firstOrNull {
							it.version.equals(arguments.version, true)
						}?.version
					}

					if (version == null) {
						respond { content = "Unknown version supplied: `${arguments.version}`" }
						return@action
					}

					knownVersions.remove(version)

					respond { content = "Version forgotten: `$version`" }
				}
			}

			ephemeralSubCommand(::UpdateArguments) {
				name = "update".toKey()
				description = "Edit the given message to replace its embed. Useful when formatting code changes.".toKey()

				check {
					hasPermission(Permission.Administrator)
				}

				action {
					if (!::currentEntries.isInitialized) {
						respond { content = "Still setting up - try again a bit later!" }
						return@action
					}

					val entry = currentEntries.entries.firstOrNull {
						it.version.equals(arguments.version, true)
					}

					if (entry == null) {
						respond { content = "Unknown version supplied: `${arguments.version}`" }
						return@action
					}

					arguments.message.edit {
						patchNotes(entry.get())
					}

					respond { content = "Message edit to match version: `${entry.version}`" }
				}
			}

			ephemeralSubCommand {
				name = "run".toKey()
				description = "Run the check task now, without waiting for it.".toKey()

				check {
					hasPermission(Permission.Administrator)
				}

				action {
					respond { content = "Checking now..." }

					checkTask?.callNow()
				}
			}

			// New notification configuration commands
			ephemeralSubCommand(::NotificationSetupArguments) {
				name = "setup".toKey()
				description = "Configure Minecraft update notifications for this server".toKey()

				check {
					hasPermission(Permission.ManageGuild)
				}

				action {
					val guildId = guild?.id ?: run {
						respond { content = "This command can only be used in a server!" }
						return@action
					}

					// Resolve the channel and validate it supports messaging
					val channelArg = arguments.channel
					logger.info { "Channel type received: ${channelArg::class.qualifiedName}" }

					val targetChannel = when (channelArg) {
						is TopGuildMessageChannel -> {
							logger.info { "Direct match as TopGuildMessageChannel" }
							channelArg
						}
						is TextChannel -> {
							logger.info { "Direct match as TextChannel" }
							channelArg
						}
						is NewsChannel -> {
							logger.info { "Direct match as NewsChannel" }
							channelArg
						}
						is ResolvedChannel -> {
							logger.info { "Got ResolvedChannel, trying fetchChannel() method to extract inner channel" }
							try {
								// Use fetchChannel() method instead of asChannel()
								val innerChannel = channelArg.fetchChannel()
								logger.info { "Successfully extracted channel using fetchChannel(), type: ${innerChannel::class.qualifiedName}" }

								when (innerChannel) {
									is TopGuildMessageChannel -> {
										logger.info { "Inner channel is TopGuildMessageChannel" }
										innerChannel
									}
									is TextChannel -> {
										logger.info { "Inner channel is TextChannel" }
										innerChannel
									}
									is NewsChannel -> {
										logger.info { "Inner channel is NewsChannel" }
										innerChannel
									}
									else -> {
										logger.warn { "Inner channel type not supported: ${innerChannel::class.qualifiedName}" }
										respond {
											content = "The selected channel (${innerChannel::class.simpleName}) is not supported. Please select a text channel."
										}
										return@action
									}
								}
							} catch (e: Exception) {
								logger.error(e) { "Failed to extract channel from ResolvedChannel using fetchChannel()" }
								// Try the fallback fetchChannelOrNull() method
								try {
									val innerChannel = channelArg.fetchChannelOrNull()
									if (innerChannel != null) {
										logger.info { "Fallback successful using fetchChannelOrNull(), type: ${innerChannel::class.qualifiedName}" }
										when (innerChannel) {
											is TopGuildMessageChannel -> {
												logger.info { "Inner channel is TopGuildMessageChannel" }
												innerChannel
											}
											is TextChannel -> {
												logger.info { "Inner channel is TextChannel" }
												innerChannel
											}
											is NewsChannel -> {
												logger.info { "Inner channel is NewsChannel" }
												innerChannel
											}
											else -> {
												logger.warn { "Inner channel type not supported: ${innerChannel::class.qualifiedName}" }
												respond {
													content = "The selected channel (${innerChannel::class.simpleName}) is **not supported**. Please select a text channel."
												}
												return@action
											}
										}
									} else {
										logger.error { "fetchChannelOrNull() returned null" }
										respond {
											content = "**Failed to process** the selected channel. Please try selecting a different channel."
										}
										return@action
									}
								} catch (fallbackException: Exception) {
									logger.error(fallbackException) { "Both fetchChannel() and fetchChannelOrNull() failed" }
									respond {
										content = "**Failed to process** to process the selected channel. Please try selecting a different channel."
									}
									return@action
								}
							}
						}
						else -> {
							logger.warn { "Completely unsupported channel type: ${channelArg::class.qualifiedName}" }
							respond {
								content = "The selected channel (${channelArg::class.simpleName}) is **not supported**. Please select a text channel."
							}
							return@action
						}
					}

					val config = MinecraftNotificationService.setConfig(
						guildId = guildId,
						channelId = targetChannel.id,
						pingRoleId = arguments.role?.id
					)

					if (config == null) {
						respond {
							content = "**Failed to configure notifications**. The database may be unavailable. Please try again later."
						}
						return@action
					}

					val roleText = if (config.pingRoleId != null) {
						" and will ping <@&${config.pingRoleId}>"
					} else {
						""
					}

					respond {
						content = "Minecraft update notifications configured!\n" +
								"Updates will be sent to <#${config.channelId}>$roleText"
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
						respond { content = "This command can only be used in a server!" }
						return@action
					}

					val success = MinecraftNotificationService.setEnabled(guildId, false)

					when {
						success == null -> {
							respond { content = "**Failed to update settings**. The database may be unavailable. Please try again later." }
						}
						success -> {
							respond { content = "Minecraft update notifications **disabled** for this server." }
						}
						else -> {
							respond { content = "**No notification configuration found** for this server." }
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
						respond { content = "This command can only be used in a server!" }
						return@action
					}

					val success = MinecraftNotificationService.setEnabled(guildId, true)

					when {
						success == null -> {
							respond { content = "**Failed to update** settings. The database may be unavailable. Please try again later." }
						}
						success -> {
							respond { content = "Minecraft update notifications **enabled** for this server." }
						}
						else -> {
							respond { content = "**No notification configuration found** for this server. Use `/mc setup` first." }
						}
					}
				}
			}

			ephemeralSubCommand {
				name = "status".toKey()
				description = "Check the current notification configuration for this server".toKey()

				action {
					val guildId = guild?.id ?: run {
						respond { content = "This command can only be used in a server!" }
						return@action
					}

					val config = MinecraftNotificationService.getConfig(guildId)

					if (config == null) {
						respond {
							content = "No notification configuration found for this server or database is unavailable.\n" +
									"Use `/mc setup` to configure notifications."
						}
						return@action
					}

					val statusEmoji = if (config.enabled) "✅" else "❌"
					val statusText = if (config.enabled) "Enabled" else "Disabled"
					val roleText = if (config.pingRoleId != null) {
						"\n**Ping Role:** <@&${config.pingRoleId}>"
					} else {
						"\n**Ping Role:** None"
					}

					respond {
						content = "$statusEmoji **Minecraft Notifications:** $statusText\n" +
								"**Channel:** <#${config.channelId}>$roleText"
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
						respond { content = "This command can only be used in a server!" }
						return@action
					}

					val success = MinecraftNotificationService.removeConfig(guildId)

					when {
						success == null -> {
							respond { content = "**Failed to remove configuration**. The database may be unavailable. Please try again later." }
						}
						success -> {
							respond { content = "Minecraft notification configuration removed for this server." }
						}
						else -> {
							respond { content = "**No notification configuration found** for this server." }
						}
					}
				}
			}
		}
	}

public suspend fun populateVersions() {
	// Load your old-patchnotes API once at startup
	currentEntries = client.get(PATCHNOTE_URL).body()
	knownVersions.addAll(currentEntries.entries.map { it.version })
}

@Suppress("TooGenericExceptionCaught")
public suspend fun checkTask() {
	try {
		// 1) Grab the manifest to see if latest.release/snapshot changed
		val manifest = client.get(MOJANG_MANIFEST_URL).body<VersionManifest>()

		listOf(manifest.latest.snapshot, manifest.latest.release).forEach { vid ->
			if (vid !in knownVersions) {
				// 2) Fetch from your OLD API, then filter down to this version
			   val allNotes = client.get(PATCHNOTE_URL).body<PatchNoteEntries>()
				val note = allNotes.entries.find { it.version == vid }
					?: throw IllegalStateException("Patchnotes for $vid not found")

			   relayUpdate(note.get())
				knownVersions.add(vid)
			}
		}
	} catch (t: Throwable) {
		logger.error(t) { "Check task run failed" }
	} finally {
		checkTask = scheduler.schedule(CHECK_DELAY, callback = ::checkTask)
	}
}

	@Suppress("TooGenericExceptionCaught")
	public suspend fun relayUpdate(patchNote: PatchNote): Any {
		val configs = MinecraftNotificationService.getAllEnabledConfigs()

		if (configs.isEmpty()) {
			logger.info { "No enabled notification configurations found, skipping relay" }
			return Unit
		}

		configs.forEach { config ->
			try {
				val channel = kord.getChannelOf<TopGuildMessageChannel>(Snowflake(config.channelId))
				channel?.relay(patchNote, config.pingRoleId?.let { Snowflake(it) })
			} catch (t: Throwable) {
				logger.warn(t) { "Unable to send notification to channel ${config.channelId} in guild ${config.guildId}" }
			}
		}

		return Unit
	}

	public fun String.formatHTML(): String {
		var result = StringEscapeUtils.unescapeHtml4(trim('\n'))

		result = result.replace("\u200B", "")
		result = result.replace("<p></p>", "")

		result = result.replace("<hr/?>".toRegex(), "\\_\\_\\_\\_\\_\\_\\_\\_\\_\\_\\_\\_")
		result = result.replace("</hr>", "")

		result = result.replace("[\n]*</p>\n+<p>[\n]*".toRegex(), "\n\n")
		result = result.replace("[\n]*<[/]*p>[\n]*".toRegex(), "\n")

		result = result.replace("<strong>", "**")
		result = result.replace("</strong>", "**")

		result = result.replace("<em>", "_")
		result = result.replace("</em>", "_")

		result = result.replace("<code>", "`")
		result = result.replace("</code>", "`")

		@Suppress("MagicNumber")
		for (i in 1..6) {
			result = result.replace("[\n]*<h$i>[\n]*".toRegex(), "\n\n${"#".repeat(i)} ")
			result = result.replace("[\n]*</h$i>[\n]*".toRegex(), "\n")
		}

		result = result.replace("[\n]*<[ou]l>[\n]*".toRegex(), "\n\n")
		result = result.replace("[\n]*</[ou]l>[\n]*".toRegex(), "\n\n")

		result = result.replace("[\n]*</li>\n+<li>[\n]*".toRegex(), "\n- ")
		result = result.replace("([\n]{2,})?<li>[\n]*".toRegex(), "\n- ")
		result = result.replace("[\n]*</li>[\n]*".toRegex(), "\n\n")

		val links = LINK_REGEX.findAll(result)

		links.forEach {
			result = result.replace(
				it.value,
				"[${it.groups["text"]?.value}](${it.groups["url"]?.value})"
			)
		}

		val cursor = Cursor(result)
		var isQuote = false

		result = ""

		@Suppress("LoopWithTooManyJumpStatements")  // Nah.
		while (cursor.hasNext) {
			result += (
				cursor.consumeWhile { it != '<' }?.prefixQuote(isQuote)
					?: break
				)

			val temp = cursor.consumeWhile { it != '>' }
				?.plus(cursor.nextOrNull() ?: "")
				?: break

			if (temp == "<blockquote>") {
				isQuote = true

				if (cursor.peekNext() == '\n') {
					cursor.next()
				}

				continue
			} else if (temp == "</blockquote>") {
				isQuote = false

				continue
			}

			result += temp.prefixQuote(isQuote)
		}

		result = result.replace("&#60", "<")

		return result.trim()
	}

	public fun String.prefixQuote(prefix: Boolean): String =
		if (prefix) {
			split("\n")
				.joinToString("\n") {
					"> $it"
				}
		} else {
			this
		}

	public fun String.truncateMarkdown(maxLength: Int = 3000): Pair<String, Int> {
		var result = this

		if (length > maxLength) {
			val truncated = result.substring(0, maxLength).substringBeforeLast("\n")
			val remaining = result.substringAfter(truncated).count { it == '\n' }

			result = truncated

			return result to remaining
		}

		return result to 0
	}

	private fun MessageBuilder.patchNotes(patchNote: PatchNote, maxLength: Int = 3000) {
		val (truncated, remaining) = patchNote.body.formatHTML().truncateMarkdown(maxLength)

		actionRow {
			linkButton("https://quiltmc.org/mc-patchnotes/#${patchNote.version}") {
				label = "Read more..."

				emoji("🔗".toReaction() as ReactionEmoji.Unicode)
			}
		}

	   embed {
		   title = patchNote.title
		   color = DISCORD_GREEN
		   description = truncated
		   if (remaining > 0) description += "\n\n[... $remaining more lines]"
		   patchNote.image?.url?.let { imageUrl ->
			   thumbnail { url = "$BASE_URL$imageUrl" }
		   }
		   footer {
			   text = "URL: https://quiltmc.org/mc-patchnotes/#${patchNote.version}"
		   }
	   }
	}

	private suspend fun TopGuildMessageChannel.relay(patchNote: PatchNote, pingRoleId: Snowflake? = null) {
		val message = createMessage {
			// Ping the configured role if one is set
			if (pingRoleId != null) {
				content = "<@&$pingRoleId>"
			}

			patchNotes(patchNote)
		}

		val title = if (patchNote.title.startsWith("minecraft ", true)) {
			patchNote.title.split(" ", limit = 2).last()
		} else {
			patchNote.title
		}

		when (this) {
			is TextChannel -> startPublicThreadWithMessage(
				message.id, title
			) { reason = "Thread created for Minecraft update" }

			is NewsChannel -> {
				startPublicThreadWithMessage(
					message.id, title
				) { reason = "Thread created for Minecraft update" }

				message.publish()
			}
		}
	}

	public fun getLatest(): PatchNoteEntry =
		currentEntries.entries.first()

	override suspend fun unload() {
		checkTask?.cancel()
		client.close()
		DatabaseConfig.close()

		super.unload()
	}

	public suspend fun PatchNoteEntry.get(): PatchNote =
		client.get("$BASE_URL/$contentPath").body<PatchNote>()

	@OptIn(KordPreview::class)
	public class CheckArguments : Arguments() {
		public val version: String? by optionalString {
			name = "version".toKey()
			description = "Specific version to get patch notes for".toKey()
		}
	}

	@OptIn(KordPreview::class)
	public class UpdateArguments : Arguments() {
		public val version: String by string {
			name = "version".toKey()
			description = "Specific version to get patch notes for".toKey()
		}

		public val message: Message by message {
			name = "message".toKey()
			description = "Message to edit with a new embed".toKey()
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
	}
}

// In-dev testing function
@Suppress("unused")
private suspend fun main() {
	val ext = MinecraftExtension()
	ext.populateVersions()

	val current = ext.getLatest()

	with(ext) {
		println(current.get().body.formatHTML())
	}

	exitProcess(0)
}