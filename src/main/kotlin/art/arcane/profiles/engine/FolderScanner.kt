package art.arcane.profiles.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Folder scan backing the "Create Profiles from Folder" importer. Given a root, every immediate
 * sub-directory is a candidate PROFILE (named after the folder) and each of ITS immediate
 * sub-directories is a candidate PROJECT. Hidden dot-folders (`.git`, `.idea`, `.codegraph`, …) are
 * never offered as candidates. A project is [ScannedProject.recommended] (pre-checked in the dialog)
 * when it looks like a real project root; a profile is recommended when at least one of its projects
 * is — so org/repo trees pre-check cleanly while standalone-repo folders default to unchecked.
 *
 * Operates on java.nio only (no IntelliJ-platform dependency), so it is unit-tested against a temp
 * directory.
 */
object FolderScanner {

    data class ScannedProject(val path: String, val name: String, val recommended: Boolean)

    data class ScannedProfile(val name: String, val projects: List<ScannedProject>) {
        val recommended: Boolean get() = projects.any { it.recommended }
    }

    /** Files/dirs at a repo root that mark it as a real project worth opening as a window. */
    private val PROJECT_MARKERS: List<String> = listOf(
        ".git", ".idea",
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "pom.xml", "pubspec.yaml", "package.json", "Cargo.toml", "go.mod",
    )

    fun scan(root: Path): List<ScannedProfile> {
        if (!Files.isDirectory(root)) return emptyList()
        return listVisibleDirs(root).map { orgDir ->
            val projects = listVisibleDirs(orgDir).map { repoDir ->
                ScannedProject(
                    path = repoDir.toString(),
                    name = repoDir.fileName.toString(),
                    recommended = looksLikeProject(repoDir),
                )
            }
            ScannedProfile(orgDir.fileName.toString(), projects)
        }
    }

    /** Immediate sub-directories, dot-folders excluded, sorted case-insensitively by name. */
    private fun listVisibleDirs(dir: Path): List<Path> =
        try {
            Files.newDirectoryStream(dir).use { stream ->
                stream
                    .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                    .sortedBy { it.fileName.toString().lowercase() }
            }
        } catch (e: IOException) {
            emptyList()
        }

    private fun looksLikeProject(dir: Path): Boolean =
        PROJECT_MARKERS.any { Files.exists(dir.resolve(it)) }
}
