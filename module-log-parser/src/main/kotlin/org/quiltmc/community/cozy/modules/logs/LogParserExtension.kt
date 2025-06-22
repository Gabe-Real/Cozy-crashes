/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:Suppress("MagicNumber")

package org.quiltmc.community.cozy.modules.logs

import com.charleskorn.kaml.Yaml
import dev.kord.common.entity.ButtonStyle
import dev.kord.core.entity.Message
import dev.kord.core.event.Event
import dev.kord.rest.builder.message.create.MessageCreateBuilder
import dev.kord.rest.builder.message.embed
import dev.kord.rest.builder.message.actionRow
import dev.kordex.core.DISCORD_GREEN
import dev.kordex.core.DISCORD_RED
import dev.kordex.core.DISCORD_YELLOW
import dev.kordex.core.components.components
import dev.kordex.core.components.linkButton
import dev.kordex.core.components.publicButton
import dev.kordex.core.components.linkButton
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.i18n.toKey
import dev.kordex.core.utils.capitalizeWords
import dev.kordex.core.utils.envOrNull
import dev.kordex.core.utils.respond
import dev.kordex.core.utils.scheduling.Scheduler
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.quiltmc.community.cozy.modules.logs.data.LoaderType
import org.koin.core.component.inject
import org.quiltmc.community.cozy.modules.logs.config.LogParserConfig
import org.quiltmc.community.cozy.modules.logs.data.Log
import org.quiltmc.community.cozy.modules.logs.data.PastebinConfig
import org.quiltmc.community.cozy.modules.logs.events.DefaultEventHandler
import org.quiltmc.community.cozy.modules.logs.events.EventHandler
import org.quiltmc.community.cozy.modules.logs.events.PKEventHandler
import org.quiltmc.community.cozy.modules.logs.types.BaseLogHandler
import org.quiltmc.community.cozy.modules.logs.services.MclogsUploadService
import java.net.URI
import java.net.URL
import kotlin.time.Duration.Companion.minutes

public class LogParserExtension : Extension() {
	override val name: String = "quiltmc-log-parser"

	private var scheduler: Scheduler? = null

	private val configUrl: String = envOrNull("PASTEBIN_CONFIG_URL")
		?: "https://raw.githubusercontent.com/Gabe-Real/Cozy-crashes/refs/heads/master/module-log-parser/pastebins.yml"

	private val taskDelay: Long = envOrNull("PASTEBIN_REFRESH_MINS")?.toLong()
		?: 60

	private val config: LogParserConfig by inject()
	private val logger: KLogger = KotlinLogging.logger("org.quiltmc.community.cozy.modules.logs.LogParserExtension")
	private val yaml = Yaml.default

	internal val client: HttpClient = HttpClient(CIO)
	internal lateinit var pastebinConfig: PastebinConfig
	private lateinit var mclogsUploadService: MclogsUploadService

	private lateinit var eventHandler: EventHandler

	override suspend fun setup() {
		// TODO: Add commands
		// TODO: Add checks for event handling

		scheduler = Scheduler()
		pastebinConfig = getPastebinConfig()
		mclogsUploadService = MclogsUploadService(client)

		scheduler?.schedule(taskDelay.minutes, repeat = true) {
			pastebinConfig = getPastebinConfig()
		}

		eventHandler = if (bot.extensions.containsKey("ext-pluralkit")) {
			logger.info { "Loading PluralKit-based event handlers" }

			PKEventHandler(this)
		} else {
			logger.info { "Loading default event handlers, without PluralKit support" }

			DefaultEventHandler(this)
		}

		eventHandler.setup()
		config.getRetrievers().forEach { it.setup() }
		config.getProcessors().forEach { it.setup() }

		// Add slash command for uploading logs
		ephemeralSlashCommand {
			name = "upload-log".toKey()
			description = "Upload log content to mclo.gs for easy sharing".toKey()

			action {
				respond {
					content = "🔄 To upload a log to mclo.gs:\n" +
						"1. Post your log file or paste the log content in a message\n" +
						"2. I'll analyze it and show you the results\n" +
						"3. Look for the tip message about mclo.gs upload!\n\n" +
						"*Feature coming soon: Direct upload functionality!*"
				}
			}
		}
	}

	override suspend fun unload() {
		scheduler?.shutdown()
	}

	internal suspend fun handleMessage(message: Message, event: Event) {
		if (message.content.isEmpty() && message.attachments.isEmpty()) {
			return
		}

		val logs = (parseLinks(message.content) + message.attachments.map { it.url })
			.map { URI(it).toURL() }
			.map { handleLink(it, event) }
			.flatten()
			.filter {
				it.aborted ||
					it.hasProblems ||
					it.getMessages().isNotEmpty() ||					it.minecraftVersion != null ||
					it.getMods().isNotEmpty()
			}

		if (logs.isNotEmpty()) {
			message.respond(pingInReply = false) {
				addLogs(logs)

				// Add button for mclo.gs upload
				components {
					publicButton {
						label = "Upload to mclo.gs".toKey()
						style = ButtonStyle.Secondary
						action {
							if (logs.size == 1) {
								val log = logs.first()
								val uploadUrl = mclogsUploadService.uploadLog(log)

								if (uploadUrl != null) {
									respond {
										content = "## 🏓 Log successfully uploaded to mclo.gs\n-# Click the button below to view it..."
										actionRow {
											linkButton(uploadUrl) {
												label = "View on mclo.gs"
											}
										}
									}
								} else {
									respond {
										content = "## 📌 Failed to upload log to mclo.gs. Please try again later."
									}
								}
							} else {
								val uploadResults = mutableListOf<Pair<Int, String?>>()
								logs.forEachIndexed { index, log ->
									val uploadUrl = mclogsUploadService.uploadLog(log)
									uploadResults.add(index to uploadUrl)
								}

								val successfulUploads = uploadResults.filter { it.second != null }

								if (successfulUploads.isNotEmpty()) {
									val resultMessage = buildString {
										appendLine("## 📝 **Upload Results:**")
										uploadResults.forEach { (index, url) ->
											if (url != null) {
												appendLine("**Log ${index + 1}:** Successfully uploaded")
											} else {
												appendLine("**Log ${index + 1}:** Failed to upload")
											}
										}
									}
									
									respond {
										content = resultMessage
										
										// Split buttons into chunks of 5 (Discord's limit per action row)
										successfulUploads.chunked(5).forEach { chunk ->
											actionRow {
												chunk.forEach { (index, url) ->
													linkButton(url!!) {
														label = if (successfulUploads.size == 1) "View on mclo.gs" else "View Log ${index + 1}"
													}
												}
											}
										}
									}
								} else {
									respond {
										content = "## 📌 Failed to upload log to mclo.gs. Please try again later."
									}
								}
							}
						}
					}
				}
			}
		}
	}
	@Suppress("MagicNumber")
	private suspend fun MessageCreateBuilder.addLogs(logs: List<Log>) {
		if (logs.size > 10) {
			content = "**Warning:** I found ${logs.size} logs, but I can't provide results for more than 10 logs at " +
				"a time. You'll only see results for the first 10 logs below - please " +
				"limit the number of logs you post at once."
		}
		// Build all embeds first
		val allEmbeds = mutableListOf<dev.kord.rest.builder.message.EmbedBuilder.() -> Unit>()
		var totalCharacterCount = 0

		for (log in logs) {
			val logEmbeds = createEmbedsForLog(log)
			
			// Check if adding these embeds would exceed character limit
			val logCharacterCount = logEmbeds.sumOf { embed ->
				// Rough character count estimation
				val testBuilder = dev.kord.rest.builder.message.EmbedBuilder()
				embed(testBuilder)
				(testBuilder.title?.length ?: 0) + (testBuilder.description?.length ?: 0)
			}

			if (totalCharacterCount + logCharacterCount > 6000 && allEmbeds.isNotEmpty()) {
				// Would exceed limit, so we'll need to split into multiple messages
				break
			}

			allEmbeds.addAll(logEmbeds)
			totalCharacterCount += logCharacterCount
		}
		// Add embeds in chunks of 10 (Discord's limit per message)
		val embedChunks = allEmbeds.chunked(10)
		
		if (embedChunks.isNotEmpty()) {
			// Add first chunk to current message
			embedChunks[0].forEach { embedBuilder ->
				embed(embedBuilder)
			}
		}

		// Add extraEmbeds from logs separately (they are suspend functions)
		for (log in logs) {
			log.extraEmbeds.forEach { extraEmbed ->
				embed { extraEmbed(this) }
			}
		}

		// Handle remaining chunks - these would need to be sent as follow-up messages
		// For now, we'll limit to first 10 embeds and show warning if more exist
		if (allEmbeds.size > 10) {
			val extraEmbeds = allEmbeds.size - 10

			if (content == null) {
				content = ""
			} else {
				content += "\n\n"
			}

			content += "**Warning:** $extraEmbeds extra embeds were generated when parsing your logs. Please fix the " +
				"issues that have been detailed here, and try again with new logs. Alternatively, if you submitted " +
				"more than one log in the same message, you could try submitting them one at a time."
		}
	}

	private fun createEmbedsForLog(log: Log): List<dev.kord.rest.builder.message.EmbedBuilder.() -> Unit> {
		val embeds = mutableListOf<dev.kord.rest.builder.message.EmbedBuilder.() -> Unit>()

		val baseTitle = if (log.content.startsWith("---- Crashed! ----")) {
			"Crash Log"
		} else {
			"Log File"
		}

		val color = if (log.aborted) {
			DISCORD_RED
		} else if (log.hasProblems) {
			DISCORD_YELLOW
		} else {
			DISCORD_GREEN
		}

		val titleSuffix = if (log.aborted) {
			": Aborted"
		} else if (log.hasProblems) {
			": Problems Found"
		} else {
			""
		}

		val header = buildString {
			with(log.environment) {
				val mcVersion = log.minecraftVersion?.string ?: "Unknown"

				appendLine("**__Environment Info__**")
				appendLine()
				appendLine("**Minecraft Version:** `$mcVersion`")

				var addAnotherLine = false

				if (javaVersion != null) {
					appendLine("**Java Version:** `$javaVersion`")
					addAnotherLine = true
				}

				if (jvmVersion != null) {
					appendLine("**JVM Version:** `$jvmVersion`")
					addAnotherLine = true
				}

				if (addAnotherLine) {
					addAnotherLine = false
					appendLine()
				}

				if (jvmArgs != null) {
					appendLine("**Java Args:** `$jvmArgs`")
					addAnotherLine = true
				}

				if (addAnotherLine) {
					addAnotherLine = false
					appendLine()
				}

				if (os != null) {
					appendLine("**OS:** $os")
					addAnotherLine = true
				}

				if (cpu != null) {
					appendLine("**CPU:** `$cpu`")
					addAnotherLine = true
				}

				if (gpu != null) {
					appendLine("**GPU:** `$gpu`")
					addAnotherLine = true
				}

				if (systemMemory != null) {
					appendLine("**System Memory:** `$systemMemory`")
					addAnotherLine = true
				}

				if (addAnotherLine) {
					addAnotherLine = false
					appendLine()
				}

				if (gameMemory != null) {
					appendLine("**Game Memory:** `$gameMemory`")
					addAnotherLine = true
				}

				if (shaderpack != null) {
					appendLine("**Shaderpack:** `$shaderpack`")
					addAnotherLine = true
				}

				if (addAnotherLine) {
					appendLine()
				}
			}

			with(log.launcher) {
				if (this != null) {
					appendLine("**Launcher:** $name (`${version ?: "Unknown Version"}`)")
					appendLine()
				}
			}

			if (log.getLoaders().isNotEmpty()) {
				val pluginPlatforms = setOf(
					LoaderType.Paper,
					LoaderType.Spigot,
					LoaderType.Bukkit,
					LoaderType.Velocity,
					LoaderType.Bungeecord,
					LoaderType.Waterfall
				)

				val isPluginPlatform = pluginPlatforms.any { log.getLoaderVersion(it) != null }

				log.getLoaders()
					.toList()
					.sortedBy { it.first.name }
					.forEach { (loader, version) ->
						if (isPluginPlatform) {
							appendLine("**Platform:** ${loader.name.capitalizeWords()}")
							appendLine("**Version:** `${version.string}`")
						} else {
							appendLine("**Loader:** ${loader.name.capitalizeWords()} (`${version.string}`)")
						}
					}
			}

			val pluginPlatforms = setOf(
				LoaderType.Paper,
				LoaderType.Spigot,
				LoaderType.Bukkit,
				LoaderType.Velocity,
				LoaderType.Bungeecord,
				LoaderType.Waterfall
			)
			val isPluginPlatform = pluginPlatforms.any { log.getLoaderVersion(it) != null }
			val itemType = if (isPluginPlatform) "Plugins" else "Mods"

			appendLine(
				"**$itemType:** " + if (log.getMods().isNotEmpty()) {
					log.getMods().size
				} else {
					"None"
				}
			)

			appendLine()
		}.trim()

		val messages = if (log.aborted || log.getMessages().isNotEmpty()) {
			buildString {
				appendLine("__**Messages**__")
				appendLine()

				if (log.aborted) {
					appendLine("__**Log parsing aborted**__")
					appendLine(log.abortReason)
				} else {
					log.getMessages().forEach {
						appendLine(it)
						appendLine()
					}
				}
			}.trim()
		} else {
			""
		}

		// Combine header and messages
		val fullContent = if (messages.isNotEmpty()) {
			"$header\n\n$messages"
		} else {
			header
		}

		// Split content if it exceeds 2048 characters (embed description limit)
		val contentChunks = if (fullContent.length > 2048) {
			splitContentIntoChunks(fullContent, 2048)
		} else {
			listOf(fullContent)
		}

		// Create embeds for each chunk
		contentChunks.forEachIndexed { index, chunk ->
			val embedBuilder: dev.kord.rest.builder.message.EmbedBuilder.() -> Unit = {
				title = if (contentChunks.size > 1) {
					"$baseTitle$titleSuffix (${index + 1}/${contentChunks.size})"
				} else {
					"$baseTitle$titleSuffix"
				}
				
				// Ensure title doesn't exceed 256 characters
				if (title!!.length > 256) {
					title = title!!.take(253) + "..."
				}
				
				this.color = color
				description = chunk
			}
			embeds.add(embedBuilder)		}

		return embeds
	}

	private fun splitContentIntoChunks(content: String, maxChunkSize: Int): List<String> {
		if (content.length <= maxChunkSize) {
			return listOf(content)
		}

		val chunks = mutableListOf<String>()
		val lines = content.split("\n")
		var currentChunk = StringBuilder()

		for (line in lines) {
			// If adding this line would exceed the limit
			if (currentChunk.length + line.length + 1 > maxChunkSize) {
				// If current chunk is not empty, save it
				if (currentChunk.isNotEmpty()) {
					chunks.add(currentChunk.toString().trim())
					currentChunk = StringBuilder()
				}

				// If single line is too long, split it
				if (line.length > maxChunkSize) {
					var remainingLine = line
					while (remainingLine.length > maxChunkSize) {
						val splitPoint = maxChunkSize - 10 // Leave room for "..."
						chunks.add(remainingLine.take(splitPoint) + "...")
						remainingLine = "..." + remainingLine.drop(splitPoint)
					}
					if (remainingLine.isNotEmpty()) {
						currentChunk.append(remainingLine).append("\n")
					}
				} else {
					currentChunk.append(line).append("\n")
				}
			} else {
				currentChunk.append(line).append("\n")
			}
		}

		// Add the last chunk if it's not empty
		if (currentChunk.isNotEmpty()) {
			chunks.add(currentChunk.toString().trim())
		}

		return chunks
	}

	@Suppress("TooGenericExceptionCaught")
	private suspend fun handleLink(link: URL, event: Event): List<Log> {
		val strings: MutableList<String> = mutableListOf()
		val logs: MutableList<Log> = mutableListOf()

		for (retriever in config.getRetrievers()) {
			if (!checkPredicates(retriever, event) || !retriever._predicate(link, event)) {
				continue
			}

			try {
				strings.addAll(retriever.process(link).map { it.replace("\r\n", "\n") })
			} catch (e: Exception) {
				logger.error(e) {
					"Retriever ${retriever.identifier} threw exception for URL: $link"
				}
			}
		}

		strings.forEach { string ->
			val log = Log()

			log.content = string
			log.url = link

			for (parser in config.getParsers()) {
				if (!checkPredicates(parser, event) || !parser._predicate(log, event)) {
					continue
				}

				try {
					parser.process(log)

					if (log.aborted) {
						break
					}
				} catch (e: Exception) {
					logger.error(e) {
						"Parser ${parser.identifier} threw exception for URL: $link"
					}
				}
			}

			for (processor in config.getProcessors()) {
				if (!checkPredicates(processor, event) || !processor._predicate(log, event)) {
					continue
				}

				try {
					processor.process(log)

					if (log.aborted) {
						break
					}
				} catch (e: Exception) {
					logger.error(e) {
						"Processor ${processor.identifier} threw exception for URL: $link"
					}
				}
			}

			logs.add(log)
		}

		return logs
	}

	private suspend fun parseLinks(content: String): Set<String> =
		config.getUrlRegex().findAll(content).map { it.groups[1]!!.value }.toSet()

	private suspend fun getPastebinConfig(): PastebinConfig {
		val text = client.get(configUrl).bodyAsText()

		return yaml.decodeFromString(PastebinConfig.serializer(), text)
	}

	private suspend fun checkPredicates(handler: BaseLogHandler, event: Event) =
		config.getGlobalPredicates().all { it(handler, event) }
}
