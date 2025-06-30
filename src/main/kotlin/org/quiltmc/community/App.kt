/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(PrivilegedIntent::class)

// Jesus christ this took ages to get it to push to github but thanks to lunar hehe, it worked!

package org.quiltmc.community

import dev.kord.common.entity.PresenceStatus
import dev.kord.gateway.ALL
import dev.kord.gateway.Intents
import dev.kord.gateway.PrivilegedIntent
import dev.kordex.core.ExtensibleBot
import dev.kordex.core.utils.env
import dev.kordex.core.utils.envOrNull
import dev.kordex.modules.func.mappings.extMappings
import dev.kordex.modules.func.phishing.DetectionAction
import dev.kordex.modules.func.phishing.extPhishing
import dev.kordex.modules.pluralkit.extPluralKit
import org.quiltmc.community.cozy.modules.logs.extLogParser
import org.quiltmc.community.cozy.modules.logs.processors.PiracyProcessor
import org.quiltmc.community.cozy.modules.logs.processors.ProblematicLauncherProcessor
import org.quiltmc.community.extensions.InformationExtension
import dev.gabereal.minecraft.MinecraftExtension
import org.quiltmc.community.logs.*
import org.quiltmc.community.logs.plugins.MissingPluginProcessor
import org.quiltmc.community.logs.plugins.NotSupportMarkedPluginProcessor
import org.quiltmc.community.logs.plugins.PluginErrorProcessor
import org.quiltmc.community.logs.plugins.ServerVersionCompatibilityProcessor
import org.quiltmc.community.logs.plugins.powergems.SeallibVersionProcessor
import org.quiltmc.community.logs.plugins.powergems.processors.*

val MODE = envOrNull("MODE")?.lowercase() ?: "quilt"
val ENVIRONMENT = envOrNull("ENVIRONMENT")?.lowercase() ?: "production"
internal val DISCORD_TOKEN = env("TOKEN")
const val APP_ICON = "https://cdn.discordapp.com/attachments/1377978022526849115/" +
	"1380643522423554140/cozy-discord-github.png?ex=68449ffa&is=68434e7a&" +
	"hm=4257248467fc4df6ecff96b72cd3446ef6cbbb68283b89b88f4e9cf405c13474&"

suspend fun setupCollab() = ExtensibleBot(DISCORD_TOKEN) {
	common()
	database()

	about {
		addGeneral(
			"Cozy: Collab",

			"Quilt's Discord bot, Community Collab edition."
		)
	}

	extensions {
		sentry {
			distribution = "collab"
		}
	}
}

suspend fun setupDev() = ExtensibleBot(DISCORD_TOKEN) {
	common()
	database()

	about {
		addGeneral(
			"Cozy: Dev Tools",

			"Quilt's Discord bot, Dev Tools edition.\n\n" +
				"Once provided mappings commands, but you should use the Allium Discord bot or " +
				"[Linkie Web](https://linkie.shedaniel.dev/) going forward."
		)
	}

	extensions {
		sentry {
			distribution = "dev"
		}
	}
}

suspend fun setupQuilt() = ExtensibleBot(DISCORD_TOKEN) {
	common()
	database(true)
	settings()

	presence {
		status = PresenceStatus.DoNotDisturb
		playing("with crashlogs \uD83D\uDCDD ")
	}
	about {
		addGeneral(
			"Cozy: Crashes",

			"An extremely reliable minecraft crash reporting variant of Cozy.\n\n" +
				"Provides a ton of information on what went wrong so you can get back to playing minecraft" +
				"in no time."
		)
	}

	chatCommands {
		defaultPrefix = "%"
		enabled = true
	}

	intents {
		+Intents.ALL
	}

	members {
		all()

		fillPresences = true
	}
	extensions {
		add(::InformationExtension)
		add(::MinecraftExtension)
		extPluralKit()
		extMappings {  }

		extLogParser {
			// Bundled non-default processors
			processor(PiracyProcessor())
			processor(ProblematicLauncherProcessor())
			// Other additional processors
			processor(RuleBreakingModProcessor())
			processor(GabeModProcessor())
			processor(DuplicateModProcessor())
			processor(FabricMissingProcessor())
			processor(MissingApiProcessor())
			processor(ServerWatchdogProcessor())
			processor(MissingModsTomlProcessor())
			// Plugin processors
			processor(MissingPluginProcessor())
			processor(PluginErrorProcessor())
			processor(ServerVersionCompatibilityProcessor())
			processor(NotSupportMarkedPluginProcessor())

			// Specific plugin processors:
			// # PowerGems
			processor(SeallibVersionProcessor())
			processor(MissingSeallibProcessor())
			processor(PowerGemsDebugProcessor())
			processor(PowerGemsErrorProcessor())
			processor(PowerGemsPlayerProcessor())
			processor(PowerGemsPerformanceProcessor())
		}

		help {
			enableBundledExtension = true
		}

		extPhishing {
			detectionAction = DetectionAction.Kick
			logChannelName = "cozy-logs"
			requiredCommandPermission = null
		}

		sentry {
			distribution = "community"
		}
	}
}

suspend fun setupShowcase() = ExtensibleBot(DISCORD_TOKEN) {
	common()
	database()
	settings()

	about {
		addGeneral(
			"Cozy: Showcase",

			"Quilt's Discord bot, Showcase edition.\n\n" +
				"This bot is currently in development, but someday we hope it'll let you post in the showcase " +
				"channels from your project servers."
		)
	}

	extensions {
		sentry {
			distribution = "showcase"
		}
	}
}

suspend fun main() {
	val bot = when (MODE) {
		"collab" -> setupCollab()
		"dev" -> setupDev()
		"quilt" -> setupQuilt()
		"showcase" -> setupShowcase()

		else -> error("Invalid mode: $MODE")
	}

	bot.start()
}
