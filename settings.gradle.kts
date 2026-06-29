import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "intellij-profiles"

pluginManagement {
    plugins {
        // 2026.1 platform jars carry Kotlin 2.3.0 metadata, so the project must compile with
        // a matching Kotlin 2.3.x to read them.
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
    }
}

plugins {
    // Auto-provision the JDK toolchain (lets Gradle locate/download JDK 21 for compilation).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Provides the IntelliJ Platform plugin to the project and the repositories extension below.
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
