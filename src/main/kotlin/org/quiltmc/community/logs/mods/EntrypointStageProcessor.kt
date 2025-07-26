/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.logs.mods

import org.quiltmc.community.cozy.modules.logs.data.Log
import org.quiltmc.community.cozy.modules.logs.data.Order
import org.quiltmc.community.cozy.modules.logs.types.LogProcessor

private val ENTRYPOINT_ERROR_REGEX = Regex(
	"""Could not execute entrypoint stage '.+?' due to errors, provided by '(.+?)' at '(.+?)'!""",
	RegexOption.IGNORE_CASE
)

public class EntrypointStageProcessor : LogProcessor() {
	override val identifier: String = "entrypoint_stage_error"
	override val order: Order = Order.Earlier

	override suspend fun process(log: Log) {
		val match = ENTRYPOINT_ERROR_REGEX.find(log.content) ?: return

		val modId = match.groupValues[1]
		val className = match.groupValues[2]

		log.addMessage(
			"**Entrypoint main provided by mod `$modId` failed during startup**\n" +
			"- `$className`"
		)

		log.hasProblems = true
	}
}
