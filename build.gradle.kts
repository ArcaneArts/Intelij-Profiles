import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    // Versions are declared in settings.gradle.kts (pluginManagement + the settings plugin).
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "art.arcane"
version = "1.0.6"

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

// Inject the build version into a resource the toolbar reads at runtime (for the dropdown's version
// footer), so we don't call any plugin-descriptor API to learn our own version — those lookups
// (PluginManagerCore.getPlugin / PluginManager.findEnabledPlugin) are @Internal on newer platforms.
tasks.processResources {
    filesMatching("profiles-build.properties") {
        expand("version" to project.version.toString())
    }
}

// Builds the installable plugin zip and copies it into OUT/ at the project root.
tasks.register<Copy>("buildOut") {
    group = "build"
    description = "Builds the plugin distribution zip and copies it into OUT/."
    dependsOn("buildPlugin")
    val outDir = layout.projectDirectory.dir("OUT")
    val currentZip = layout.buildDirectory.file("distributions/${project.name}-$version.zip")
    from(currentZip)
    into(outDir)
    doFirst {
        outDir.asFile.mkdirs()
        delete(outDir.asFile.listFiles() ?: emptyArray<File>())
    }
    doLast {
        logger.lifecycle("Plugin copied to: ${outDir.asFile}")
    }
}

// Build the plugin and install it straight into local IntelliJ IDEA install(s). Targets ALL detected
// IDEA config dirs by default (Ultimate + Community) so it lands in whichever IDE you actually run;
// pass -Pide=2025.3 (a substring of the config-dir name) to target just one. You MUST fully quit and
// reopen the IDE afterward (copied plugins are only picked up on startup).
tasks.register("deployToIde") {
    group = "build"
    description = "Build and install the plugin into local IntelliJ IDEA install(s). -Pide=2025.3 targets one. Restart the IDE to load."
    dependsOn("buildPlugin")
    doLast {
        val jetbrains = file("${System.getProperty("user.home")}/Library/Application Support/JetBrains")
        val filter = (project.findProperty("ide") as String?)?.lowercase()
        val ideDirs = (jetbrains.listFiles() ?: emptyArray())
            .filter { it.isDirectory && (it.name.startsWith("IntelliJIdea") || it.name.startsWith("IdeaIC")) }
            .filter { filter == null || it.name.lowercase().contains(filter) }
            .sortedBy { it.name }
        if (ideDirs.isEmpty()) {
            throw GradleException(
                "No IntelliJ IDEA config dirs found under $jetbrains" +
                    (filter?.let { " matching '$it'" } ?: "") + ".",
            )
        }
        val zip = layout.buildDirectory.dir("distributions").get().asFile.resolve("${project.name}-$version.zip")
        if (!zip.exists()) throw GradleException("Plugin zip not found: $zip (run buildPlugin first)")

        for (ideDir in ideDirs) {
            val pluginsDir = ideDir.resolve("plugins").apply { mkdirs() }
            delete(pluginsDir.resolve(project.name))
            copy {
                from(zipTree(zip))
                into(pluginsDir)
            }
            logger.lifecycle("Deployed ${zip.name} -> $pluginsDir/${project.name}")
        }
        logger.lifecycle("Installed v$version into ${ideDirs.size} IDE(s): ${ideDirs.joinToString { it.name }}.")
        logger.lifecycle("Fully QUIT and reopen the IDE (not just 'Restart' from a dialog) to load the new build.")
    }
}
