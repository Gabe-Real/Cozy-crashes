/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.cozy.modules.logs.parsers

import org.quiltmc.community.cozy.modules.logs.Version
import org.quiltmc.community.cozy.modules.logs.data.LoaderType
import org.quiltmc.community.cozy.modules.logs.data.Log
import org.quiltmc.community.cozy.modules.logs.data.Order
import org.quiltmc.community.cozy.modules.logs.types.LogParser

private val PATTERNS = linkedMapOf(
	"\\| Quilt Loader\\s+\\| quilt_loader\\s+\\| (\\S+).+"
		.toRegex(RegexOption.IGNORE_CASE) to LoaderType.Quilt,  // Quilt mods table

	": Loading .+ with Quilt Loader (\\S+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Quilt,
	": Loading .+ with Fabric Loader (\\S+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Fabric,

	"--fml.forgeVersion, ([^\\s,]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Forge,
	// Won't show up in a valid log but here anyways
	"""^\s*at\s+net\.minecraftforge\..*""".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Forge,
	// Older versions
	"MinecraftForge v([^\\s,]+) Initialized".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Forge,

	// Plugin platforms - improved patterns to capture server build versions
	// More specific patterns first to avoid conflicts
	"This server is running CraftBukkit version ([^\\s(]+).*Spigot".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Spigot,
	"This server is running Paper version ([^\\s(]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Paper,
	"This server is running Spigot version ([^\\s(]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Spigot,
	"This server is running Bukkit version ([^\\s(]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Bukkit,
	// Generic CraftBukkit pattern - should come after more specific ones
	"This server is running CraftBukkit version ([^\\s(]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Bukkit,
	"You are running paper version ([^\\s(]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Paper,
	"Starting Velocity ([\\d\\.\\w\\-]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Velocity,
	"Velocity ([\\d\\.\\w\\-]+) is starting up".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Velocity,
	"Starting BungeeCord version ([^\\s]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Bungeecord,
	"BungeeCord version ([^\\s]+) by SpigotMC".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Bungeecord,
	"Starting Waterfall version ([^\\s]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Waterfall,
	"Waterfall version ([^\\s]+) by PaperMC".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Waterfall,
	""".*Waterfall has reached end of life and is no longer maintained.*""".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Waterfall,
	"Loading Folia ([^\\s(]+) ?(?:\\([^)]*\\))? for Minecraft".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Folia,

	// Fallback patterns for basic Minecraft server detection (will use Bukkit as default)
	"Starting minecraft server version ([\\d\\.]+)".toRegex(RegexOption.IGNORE_CASE) to LoaderType.Bukkit,
)

public class LoaderParser : LogParser() {
	override val identifier: String = "loader"
	override val order: Order = Order.Earlier
	override suspend fun process(log: Log) {
		for ((pattern, loader) in PATTERNS) {
			val match = pattern.find(log.content)
				?: continue

			// Extract version from capture group if available, otherwise use "Unknown"
			val version = if (match.groups.size > 1 && match.groups[1] != null) {
				match.groups[1]!!.value
			} else {
				"Unknown"
			}

			log.setLoaderVersion(loader, Version(version))

			if (loader == LoaderType.Forge) {
					log.addMessage(
						"**It looks like you're using the Forge Loader.** " +
							"Please note that we do not fully support Forge yet but are currently in the works to do so, " +
							"while we can provide semi-accurate logs not everything will work as intended. " +
							"Thank you for understanding."

					)
			}

			if (loader == LoaderType.Waterfall) {
				log.abort(
					"**It looks like you're using the Waterfall Loader.**\n\n" +
					"As of March 2024, **Waterfall loader updates have ended** and the project has been archived. " +
					"In the past years Waterfall didn't receive much love from their team their great contributor " +
					"community. They have also seen less and less traffic in the support channels on their Discord." +
					"\n\n Additionally, Mojang made huge investments into the core engine of the game which resulted " +
					"in big and complicated changes to the inner workings of the game. While these changes are very " +
					"welcome and Waterfall have been pushed them for some years, that also means that there was a " +
					"bunch of work ahead of them for adapting their projects to these changes. Due to all of this, " +
					"their forum and sub-pages now have big red banners indicating this project will no longer be " +
					"updated.\n\n" +

					"Whilst it is still safe and perfectly ok to use Waterfall, we will not provide any support for " +
					"it. We would also like to recommend using the [Velocity](https://papermc.io/software/velocity) " +
					"loader as it is what originally Waterfall was based off of.\n\n"+

					"For more information on what happened, feel free to check out the following links:\n\n" +
					"- [Thread by PaperMC](https://forums.papermc.io/threads/announcing-the-end-of-life-"+
					"of-waterfall.1088/)\n\n" +

					"- [The Waterfall website](https://papermc.io/software/waterfall)"
				)
			}

			return
		}
	}
}
