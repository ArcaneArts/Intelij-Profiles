import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    // Versions are declared in settings.gradle.kts (pluginManagement + the settings plugin).
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "art.arcane"
version = "1.0.0"

dependencies {
    intellijPlatform {
        // Build against the user's actual IDE version (2026.1 / build 261) so the
        // compiler catches any API drift. Since 2025.3 the Community/Ultimate artifacts are
        // unified under intellijIdea(...). Platform-level: depends only on
        // com.intellij.modules.platform, so it still loads in IDEA/WebStorm/PyCharm/etc.
        intellijIdea("2026.1.3")

        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // Don't launch a headless IDE to pre-index settings during the build: it's optional, slows the
    // build, and fails with "Only one instance of IDEA can be run at a time" when an IDE of the same
    // version is already open.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            // Open-ended upper bound so the plugin keeps loading in future builds.
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Builds the installable plugin zip and copies it into OUT/ at the project root.
tasks.register<Copy>("buildOut") {
    group = "build"
    description = "Builds the plugin distribution zip and copies it into OUT/."
    dependsOn("buildPlugin")
    from(layout.buildDirectory.dir("distributions")) {
        include("*.zip")
    }
    into(layout.projectDirectory.dir("OUT"))
    doLast {
        logger.lifecycle("Plugin copied to: ${layout.projectDirectory.dir("OUT").asFile}")
    }
}
