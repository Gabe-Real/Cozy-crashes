/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

plugins {
	kotlin("jvm")
	kotlin("plugin.serialization")

	id("com.expediagroup.graphql")
	id("com.google.devtools.ksp")
	id("dev.kordex.gradle.kordex")
	id("dev.yumi.gradle.licenser")
}

group = "org.quiltmc.community"
version = "1.1.0-SNAPSHOT"

repositories {
	maven {
		name = "Sleeping Town"
		url = uri("https://repo.sleeping.town")

		content {
			includeGroup("com.unascribed")
		}
	}
}

configurations.all {
	resolutionStrategy.cacheDynamicVersionsFor(10, "seconds")
	resolutionStrategy.cacheChangingModulesFor(10, "seconds")
}

license {
	rule(rootProject.file("codeformat/HEADER"))
}

sourceSets {
	main {
		java {
			srcDir(file("${layout.buildDirectory.get()}/generated/ksp/main/kotlin/"))
		}
	}

	test {
		java {
			srcDir(file("${layout.buildDirectory.get()}/generated/ksp/test/kotlin/"))
		}
	}
}

val sourceJar = task("sourceJar", Jar::class) {
	dependsOn(tasks["classes"])
	archiveClassifier.set("sources")
	from(sourceSets.main.get().allSource)
}

java {
	// Compile using JDK 24 toolchain but emit Java 17 bytecode for runtime compatibility
	toolchain {
		languageVersion = JavaLanguageVersion.of(24)
		vendor = JvmVendorSpec.ADOPTIUM
	}

	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
	// Use JDK 24 toolchain for Kotlin, still target JVM 17 bytecode
	jvmToolchain(24)

	compilerOptions {
		jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
	}
}

kordEx {
	// Required due to that GraphQL client, because one of their other modules uses Spring 3, apparently.
	// No, I don't think that's a good reason either.
	// -- gdude

	jvmTarget = 17
}
