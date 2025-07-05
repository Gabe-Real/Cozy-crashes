/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.gabereal.minecraft.collections

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

@Serializable
public data class MinecraftVersionDetails(
	val id: String,
	val type: String,
	val time: String,
	val releaseTime: String,
	val assets: String?,
	val downloads: Map<String, DownloadInfo>?
)

@Serializable
public data class DownloadInfo(
	val sha1: String,
	val size: Long,
	val url: String
)

@Serializable 
public data class ProcessedMinecraftUpdate(
	val version: String,
	val type: String,
	val releaseTime: Instant,
	val title: String,
	val description: String,
	val imageUrl: String?,
	val changelogUrl: String,
	val isSnapshot: Boolean,
	val isNewRelease: Boolean
)
