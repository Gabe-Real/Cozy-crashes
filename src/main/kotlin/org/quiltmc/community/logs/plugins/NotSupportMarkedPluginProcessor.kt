/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.logs.plugins

import org.quiltmc.community.cozy.modules.logs.data.Log
import org.quiltmc.community.cozy.modules.logs.data.Order
import org.quiltmc.community.cozy.modules.logs.types.LogProcessor

// Matches: Could not load plugin 'PluginName vX.X.X' as it is not marked as supporting Loader!
private val UNSUPPORTED_LOADER_REGEX =
	"""Could not load plugin '(.+?)' as it is not marked as supporting (\w+)!"""
		.toRegex(RegexOption.IGNORE_CASE)

public class NotSupportMarkedPluginProcessor : LogProcessor() {
	override val identifier: String = "unsupported_loader_plugins"
	override val order: Order = Order.Earlier

	override suspend fun process(log: Log) {
		val matches = UNSUPPORTED_LOADER_REGEX.findAll(log.content).toList()

		if (matches.isEmpty()) return

		for (match in matches) {
			val plugin = match.groupValues[1].trim()
			val loader = match.groupValues[2].trim()

			log.addMessage(
				"**Plugin `$plugin` is not marked as supporting `$loader`.**\n" +
					"This plugin must explicitly declare support for `$loader` or be updated."
			)
			log.hasProblems = true
		}
	}
}
