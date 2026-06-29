import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    // Versions are declared in settings.gradle.kts (pluginManagement + the settings plugin).
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "art.arcane"
version = "1.0.2"

dependencies {
    intellijPlatform {
        // Build against the LOWEST supported IDE (2025.3.2 / build 253) so the compiler prevents us
        // from accidentally using newer-only APIs. Since 2025.3 the Community/Ultimate artifacts are
        // unified under intellijIdea(...). Platform-level (depends only on com.intellij.modules.platform),
        // so it loads across IDEA/WebStorm/PyCharm/etc.
        intellijIdea("2025.3.2")

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
            sinceBuild = "253"
            // Open-ended upper bound so the plugin keeps loading in future builds.
            untilBuild = provider { null }
        }
    }

    // `./gradlew verifyPlugin` checks binary compatibility across the supported IDE range.
    // Fail only on real compatibility/structure problems; internal-API usage (the deliberate,
    // documented veto-free forceCloseProjectAsync close, which has no public equivalent) is reported
    // but accepted — the Marketplace allows it.
    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        ides {
            recommended()
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

// Build the plugin and install it straight into the latest local IntelliJ IDEA. Restart the IDE
// (or Settings > Plugins, it'll be there) to load the new build.
tasks.register("deployToIde") {
    group = "build"
    description = "Build and install the plugin into the latest local IntelliJ IDEA (restart to load)."
    dependsOn("buildPlugin")
    doLast {
        val jetbrains = file("${System.getProperty("user.home")}/Library/Application Support/JetBrains")
        val ideDir = jetbrains.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("IntelliJIdea") }
            ?.maxByOrNull { it.name }
            ?: throw GradleException("No IntelliJ IDEA config directory found under $jetbrains")
        val pluginsDir = ideDir.resolve("plugins").apply { mkdirs() }
        val zip = layout.buildDirectory.dir("distributions").get().asFile
            .listFiles { f -> f.extension == "zip" }
            ?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("No plugin zip found in build/distributions")
        delete(pluginsDir.resolve("intellij-profiles"))
        copy {
            from(zipTree(zip))
            into(pluginsDir)
        }
        logger.lifecycle("Deployed ${zip.name} -> ${pluginsDir}/intellij-profiles")
        logger.lifecycle("Restart ${ideDir.name} to load the new build.")
    }
}
