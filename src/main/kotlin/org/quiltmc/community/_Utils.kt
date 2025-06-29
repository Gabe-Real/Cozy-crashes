/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community

import dev.kord.common.entity.ArchiveDuration
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.UserFlag
import dev.kord.core.Kord
import dev.kord.core.behavior.UserBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.threads.ThreadChannelBehavior
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Message
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.embed
import dev.kord.rest.request.RestRequestException
import dev.kordex.core.DISCORD_FUCHSIA
import dev.kordex.core.builders.AboutBuilder
import dev.kordex.core.builders.ExtensibleBotBuilder
import dev.kordex.core.builders.about.CopyrightType
import dev.kordex.core.utils.env
import dev.kordex.core.utils.envOrNull
import dev.kordex.core.utils.loadModule
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

@Suppress("MagicNumber")  // It's the status code...
suspend fun Kord.getGuildIgnoring403(id: Snowflake) =
	try {
		getGuildOrNull(id)
	} catch (e: RestRequestException) {
		if (e.status.code != 403) {
			throw (e)
		}

		null
	}

fun String.chunkByWhitespace(length: Int): List<String> {
	if (length <= 0) {
		error("Length must be greater than 0")
	}

	if (contains("\n")) {
		error("String must be a single line")
	}

	val words = split(" ")
	var currentLine = ""
	val lines: MutableList<String> = mutableListOf()

	for (word in words) {
		if (word.length >= length) {
			val parts = word.chunked(length)

			if (currentLine.isNotEmpty()) {
				lines.add(currentLine)
				currentLine = ""
			}

			parts.forEach {
				if (it.length == length) {
					lines.add(it)
				} else if (it.isNotEmpty()) {
					currentLine = it
				}
			}
		} else {
			val newLength = currentLine.length + word.length + if (currentLine.isEmpty()) 0 else 1

			if (newLength > length) {
				lines.add(currentLine)
				currentLine = word
			} else {
				currentLine += if (currentLine.isEmpty()) word else " $word"
			}
		}
	}

	if (currentLine.isNotEmpty()) {
		lines.add(currentLine)
	}

	return lines
}

suspend fun ExtensibleBotBuilder.database(migrate: Boolean = false) {
	val url = env("DB_URL")

	hooks {
		beforeKoinSetup {
			loadModule {
			}

			loadModule {
			}

			if (migrate) {
				runBlocking {
				}
			}
		}
	}
}

suspend fun ExtensibleBotBuilder.common() {
	val logger = KotlinLogging.logger { }

	about {
		copyright("Autolink", "MIT", CopyrightType.Library, "https://github.com/robinst/autolink-java")

		copyright(
			"Apache: Commons Text",
			"Apache-2.0",
			CopyrightType.Library,
			"https://commons.apache.org/proper/commons-text/"
		)

		copyright("ExcelKt", "MIT", CopyrightType.Library, "https://github.com/evanrupert/ExcelKt")
		copyright("Homoglyph", "MIT", CopyrightType.Library, "https://github.com/codebox/homoglyph")
		copyright("KMongo", "Apache-2.0", CopyrightType.Library, "https://litote.org/kmongo/")
		copyright("Kotlin Semver", "MIT", CopyrightType.Library, "https://github.com/z4kn4fein/kotlin-semver")
		copyright("RgxGen", "Apache-2.0", CopyrightType.Library, "https://github.com/curious-odd-man/RgxGen")

		copyright("GraphQL", "MIT", CopyrightType.Tool, "https://graphql.org/")
	}

	extensions {
		sentry {
			val version: String? = object {}::class.java.`package`.implementationVersion

			enableIfDSN(envOrNull("SENTRY_DSN"))

			if (enable) {
				logger.info { "Sentry enabled." }
			}

			if (version != null) {
				release = version
			}
		}

		help {
			enableBundledExtension = false  // We have no chat commands
		}
	}

	plugins {
		if (this@common.devMode) {
			// Add plugin build folders here for testing in dev
			// pluginPath("module-tags/build/libs")
		}
	}
}

suspend fun ExtensibleBotBuilder.settings() {
	extensions {
	}
}

fun Guild.getMaxArchiveDuration(): ArchiveDuration {
	val features = features.filter {
		it.value == "THREE_DAY_THREAD_ARCHIVE" ||
			it.value == "SEVEN_DAY_THREAD_ARCHIVE"
	}.map { it.value }

	return when {
		features.contains("SEVEN_DAY_THREAD_ARCHIVE") -> ArchiveDuration.Week
		features.contains("THREE_DAY_THREAD_ARCHIVE") -> ArchiveDuration.ThreeDays

		else -> ArchiveDuration.Day
	}
}

// Logging-related extensions

suspend fun EmbedBuilder.userField(user: UserBehavior, role: String? = null, inline: Boolean = false) {
	field {
		name = role ?: "User"
		value = "${user.mention} (`${user.id}` / `${user.asUser().tag}`)"

		this.inline = inline
	}
}

fun EmbedBuilder.channelField(channel: MessageChannelBehavior, title: String, inline: Boolean = false) {
	field {
		this.name = title
		this.value = "${channel.mention} (`${channel.id}`)"

		this.inline = inline
	}
}

private const val CHANNEL_NAME_LENGTH = 75

private val THREAD_DELIMITERS = arrayOf(
	",", ".",
	"(", ")",
	"<", ">",
	"[", "]",
)

/**
 * Attempts to generate a thread name based on the message's content.
 *
 * Failing that, it returns a string of format `"$fallbackPrefix | ${message.id}"`
 */
fun Message.contentToThreadName(fallbackPrefix: String): String {
	@Suppress("SpreadOperator")
	return content.trim()
		.split("\n")
		.firstOrNull()
		?.split(*THREAD_DELIMITERS)
		?.firstOrNull()
		?.take(CHANNEL_NAME_LENGTH)

		?: "$fallbackPrefix | $id"
}

fun UserFlag.getName(): String = when (this) {
	UserFlag.ActiveDeveloper -> "Active Developer"
	UserFlag.BotHttpInteractions -> "HTTP-Based Commands"
	UserFlag.BugHunterLevel1 -> "Bug Hunter: Level 1"
	UserFlag.BugHunterLevel2 -> "Bug Hunter: Level 2"
	UserFlag.DiscordCertifiedModerator -> "Moderator Programs Alumni"
	UserFlag.DiscordEmployee -> "Discord Employee"
	UserFlag.DiscordPartner -> "Discord Partner"
	UserFlag.EarlySupporter -> "Early Supporter"
	UserFlag.HouseBalance -> "HypeSquad: Balance"
	UserFlag.HouseBravery -> "HypeSquad: Bravery"
	UserFlag.HouseBrilliance -> "HypeSquad: Brilliance"
	UserFlag.HypeSquad -> "HypeSquad"
	UserFlag.TeamUser -> "Team User"
	UserFlag.VerifiedBot -> "Verified Bot"
	UserFlag.VerifiedBotDeveloper -> "Early Verified Bot Developer"

	is UserFlag.Unknown -> "Unknown"
}

fun String.replaceParams(vararg pairs: Pair<String, Any?>): String {
	var result = this

	pairs.forEach {
		result = result.replace(":${it.first}", it.second.toString())
	}

	return result
}

@Suppress("SpreadOperator")
fun String.replaceParams(pairs: Map<String, Any>): String = this.replaceParams(
	*pairs.entries.map { it.toPair() }.toTypedArray()
)

suspend fun ThreadChannelBehavior.getFirstMessage() =
	getMessageOrNull(id)

suspend fun AboutBuilder.addGeneral(name: String, desc: String) {
	general {
		message {
			embed {
				color = DISCORD_FUCHSIA
				description = desc
				title = name

				thumbnail {
					url = APP_ICON
				}
			}

			actionRow {
				linkButton("https://ko-fi.com/gabe_real") {
					label = "Ko-fi"
				}

				linkButton("https://github.com/Gabe-Real/cozy-crashes") {
					label = "Source Code"
				}

				linkButton("https://gabereal.co.uk") {
					label = "Discord"
				}
			}
		}
	}
}
