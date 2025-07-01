import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import dev.kordex.gradle.plugins.kordex.DataCollection

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

plugins {
	`cozy-module`
	`shadow-module`

	id("com.github.jakemarsden.git-hooks")
}

allprojects {
repositories {
	mavenLocal()
	mavenCentral()
	google()
	gradlePluginPortal()
	maven("https://snapshots-repo.kordex.dev")
	maven("https://releases-repo.kordex.dev")
}
}

dependencies {
	implementation(libs.excelkt)
	implementation(libs.kmongo)
	implementation(libs.rgxgen)

	implementation(libs.ktor.client.encoding)
	implementation(libs.ktor.client.cio)
	implementation(libs.bundles.ktor.client)
	
	implementation("dev.kordex.gradle.plugins", "kordex", "1.7.1")

	implementation(libs.commons.text)
	implementation(libs.homoglyph)
	implementation(libs.jansi)
	implementation(libs.jsoup)
	implementation(libs.semver)

	implementation(libs.logback)
	implementation(libs.logback.groovy)
	implementation(libs.logging)
	implementation(libs.groovy)

	implementation(platform(libs.kotlin.bom))
	implementation(libs.kotlin.stdlib)
	implementation(libs.kx.ser)
	implementation(libs.graphql)

	implementation(projects.moduleLogParser)
	implementation(projects.moduleMinecraft)

}

kordEx {
	version = "1.0.0"

	bot {
		dataCollection(DataCollection.Minimal)

		mainClass = "org.quiltmc.community.AppKt"
	}

	module("dev-unsafe")

	module("func-phishing")
	module("func-tags")
	module("func-mappings")
	module("func-welcome")

	module("pluralkit")
}

graphql {
	client {
		schemaFile = rootProject.file("github.graphql")
//        sdlEndpoint = "https://docs.github.com/public/schema.docs.graphql"
		packageName = "quilt.ghgen"
		serializer = GraphQLSerializer.KOTLINX
	}
}

gitHooks {
	setHooks(
		mapOf("pre-commit" to "applyLicenses")
	)
}

tasks {
	jar {
		manifest {
			attributes(
				"Main-Class" to "org.quiltmc.community.AppKt"
			)

			if (System.getenv("GITHUB_SHA") != null) {
				attributes(
					"Implementation-Version" to System.getenv("GITHUB_SHA")
				)
			}
		}
	}
}
