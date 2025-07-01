/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Data class for Mojang's version_manifest.json
// https://piston-meta.mojang.com/mc/game/version_manifest.json

package dev.gabereal.minecraft.collections
import kotlinx.serialization.Serializable


@Serializable
public data class VersionManifest(
    val latest: Latest,
    val versions: List<Version>
)


@Serializable
public data class Latest(
    val release: String,
    val snapshot: String
)


@Serializable
public data class Version(
    val id: String,
    val type: String,
    val url: String,
    val time: String,
    val releaseTime: String
)
