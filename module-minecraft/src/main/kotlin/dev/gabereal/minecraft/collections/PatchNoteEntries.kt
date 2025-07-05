/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.gabereal.minecraft.collections

import kotlinx.serialization.Serializable

@Serializable
public data class MinecraftNewsEntry(
	val title: String,
	val description: String,
	val publishedDate: String,
	val url: String,
	val imageUrl: String?
)

@Serializable
public data class MinecraftNews(
	val entries: List<MinecraftNewsEntry>
)
