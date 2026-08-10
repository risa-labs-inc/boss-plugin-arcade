import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "0.1.11"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.64.jar"))
    } else {
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // JSON parsing for leaderboard RPC responses. Not bundled — the host provides
    // kotlinx-serialization on the plugin classpath (shared parent-first package).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation(kotlin("test"))
}

// Keep the default :jar (runs under `build`) from colliding with buildPluginJar's
// archive name; the host's downloader skips *-thin.jar.
tasks.jar {
    archiveClassifier.set("thin")
}

tasks.register<Jar>("buildPluginJar") {
    // processResources filters the version into plugin.json; depend on it and bundle its output
    // (via sourceSets.main.output) rather than the raw src/main/resources, so the embedded
    // plugin.json version always matches `version` instead of the hardcoded source value.
    dependsOn(tasks.processResources)
    archiveFileName.set("boss-plugin-arcade-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Arcade Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.arcade.ArcadeDynamicPlugin"
        )
    }

    from(sourceSets.main.get().output)
}

// Sync version from build.gradle.kts into plugin.json (single source of truth). `version` is a
// build-script property, not a file input, so declare it explicitly — otherwise the task stays
// UP-TO-DATE across version bumps and bundles a stale version into the jar.
tasks.processResources {
    inputs.property("version", version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}
