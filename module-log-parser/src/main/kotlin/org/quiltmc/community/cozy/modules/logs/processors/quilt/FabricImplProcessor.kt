/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.cozy.modules.logs.processors.quilt

import dev.kord.core.event.Event
import org.quiltmc.community.cozy.modules.logs.data.LoaderType
import org.quiltmc.community.cozy.modules.logs.data.Log
import org.quiltmc.community.cozy.modules.logs.data.Order
import org.quiltmc.community.cozy.modules.logs.types.LogProcessor

private val ENTRYPOINT_ERROR_REGEX = Regex(
	"""Could not execute entrypoint stage '.+?' due to errors, provided by '(.+?)' at '(.+?)'!""",
	RegexOption.IGNORE_CASE
)

public class FabricImplProcessor : LogProcessor() {
	override val identifier: String = "quilt-fabric-impl"
	override val order: Order = Order.Default

	override suspend fun predicate(log: Log, event: Event): Boolean =
		log.getLoaderVersion(LoaderType.Quilt) != null

	override suspend fun process(log: Log) {
		var classNotFoundLine: Int? = null
		var suspectedMod: String? = null
		var suspectedPackage: String? = null

		for ((index, line) in log.content.lines().mapIndexed { idx, s -> idx to s }) {
			if (classNotFoundLine == null) {
				val match = ENTRYPOINT_ERROR_REGEX.find(line)
				if (match != null) {
					classNotFoundLine = index
					suspectedMod = match.groupValues[1].trim()
					continue
				}
			}

			if (classNotFoundLine != null && index > classNotFoundLine && suspectedPackage == null) {
				if (line.startsWith("Caused by: java.lang.ClassNotFoundException:")) {
					suspectedPackage = line.split("ClassNotFoundException:").lastOrNull()?.trim()
				}
			}
		}

		if (
			suspectedMod != null &&
			suspectedPackage != null &&
			".fabricmc." in suspectedPackage &&
			(".impl." in suspectedPackage || ".mixin." in suspectedPackage)
		) {
			log.hasProblems = true
			log.addMessage(
				"Mod `$suspectedMod` may be using Fabric internals:\n`$suspectedPackage`"
			)
		}
	}
}
