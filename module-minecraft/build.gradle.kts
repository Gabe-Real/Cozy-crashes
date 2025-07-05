/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */


plugins {
	`api-module`
	`cozy-module`
	`published-module`
}

dependencies {
	implementation(libs.ktor.client.cio)
	implementation(libs.kx.ser)

	implementation(libs.autolink)
	implementation(libs.commons.text)
	implementation(libs.flexver)
	implementation(libs.jsoup)
	implementation(libs.kaml)
	implementation(libs.logging)
	implementation(libs.semver)

	// Database dependencies
	implementation("org.mariadb.jdbc:mariadb-java-client:3.3.2")
	implementation("com.zaxxer:HikariCP:5.1.0")
	implementation("org.jetbrains.exposed:exposed-core:0.48.0")
	implementation("org.jetbrains.exposed:exposed-dao:0.48.0")
	implementation("org.jetbrains.exposed:exposed-jdbc:0.48.0")
	implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.48.0")

	// Additional dependencies for new implementation
	implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")

	implementation(platform(libs.kotlin.bom))
	implementation(libs.kotlin.stdlib)
}

kordEx {
	module("pluralkit")
	module("dev-unsafe")
}
