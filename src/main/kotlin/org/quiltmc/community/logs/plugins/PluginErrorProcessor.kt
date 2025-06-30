/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.logs.plugins

import dev.kord.core.event.Event
import org.quiltmc.community.cozy.modules.logs.data.LoaderType
import org.quiltmc.community.cozy.modules.logs.data.Log
import org.quiltmc.community.cozy.modules.logs.data.Order
import org.quiltmc.community.cozy.modules.logs.types.LogProcessor

// Common error patterns that indicate plugin issues
private val PLUGIN_ERROR_PATTERNS = arrayOf(
	"""Plugin ([^\s]+) has failed to load""".toRegex(RegexOption.IGNORE_CASE),
	"""Error loading plugin ([^\s]+)""".toRegex(RegexOption.IGNORE_CASE),
	"""Plugin ([^\s]+) threw an exception""".toRegex(RegexOption.IGNORE_CASE),
	"""Could not load '([^\s]+)' plugin""".toRegex(RegexOption.IGNORE_CASE),
	"""Plugin ([^\s]+) is not compatible""".toRegex(RegexOption.IGNORE_CASE),
	"""Plugin ([^\s]+) requires a newer version""".toRegex(RegexOption.IGNORE_CASE),
	"""Outdated plugin: ([^\s]+)""".toRegex(RegexOption.IGNORE_CASE),
	"""Plugin ([^\s]+) is designed for an older server version""".toRegex(RegexOption.IGNORE_CASE)
)

public class PluginErrorProcessor : LogProcessor() {
	override val identifier: String = "plugin_errors"
	override val order: Order = Order.Default

	override suspend fun predicate(log: Log, event: Event): Boolean =
		// Only run on plugin server platforms
		log.getLoaderVersion(LoaderType.Paper) != null ||
		log.getLoaderVersion(LoaderType.Spigot) != null ||
		log.getLoaderVersion(LoaderType.Bukkit) != null ||
		log.getLoaderVersion(LoaderType.Velocity) != null ||
		log.getLoaderVersion(LoaderType.Bungeecord) != null ||
		log.getLoaderVersion(LoaderType.Waterfall) != null
	override suspend fun process(log: Log) {
		val errorPlugins = mutableSetOf<String>()

		PLUGIN_ERROR_PATTERNS.forEach { pattern ->
			pattern.findAll(log.content).forEach { match ->
				val pluginName = match.groupValues[1].trim()
				errorPlugins.add(pluginName)
			}
		}

		if (errorPlugins.isNotEmpty()) {
			val pluginList = errorPlugins.joinToString(", ") { "`$it`" }

			log.addMessage(
				"**Plugin loading errors detected for:** $pluginList\n" +
				"Common causes include:\n" +
				"• Outdated plugins not compatible with your server version\n" +
				"• Missing dependencies\n" +
				"• Corrupted plugin files\n" +
				"• Plugin conflicts\n\n" +
				"Consider updating the plugins or checking their compatibility with your server version."
			)
			log.hasProblems = true
		}
	}
}
