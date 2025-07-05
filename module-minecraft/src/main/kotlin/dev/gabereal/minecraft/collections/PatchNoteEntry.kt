/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.gabereal.minecraft.collections

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

@Serializable
public data class MinecraftVersion(
	val id: String,
	val type: String, // release, snapshot, old_beta, old_alpha
	val url: String,
	val time: String,
	val releaseTime: String,
)

@Serializable
public data class MinecraftVersionManifest(
	val latest: LatestVersions,
	val versions: List<MinecraftVersion>
)

@Serializable
public data class LatestVersions(
	val release: String,
	val snapshot: String
)

@Serializable
public data class MinecraftUpdate(
	val version: String,
	val type: String,
	val releaseTime: Instant,
	val title: String,
	val description: String,
	val isSnapshot: Boolean,
	val changelogUrl: String?
)
