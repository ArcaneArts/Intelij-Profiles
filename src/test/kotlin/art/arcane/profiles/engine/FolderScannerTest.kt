package art.arcane.profiles.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Scans a temp org/repo tree mirroring the user's ~/Developer/RemoteGit layout and asserts the
 * two-level mapping (org -> profile, repo -> project), dot-folder filtering, and the "recommended"
 * (pre-checked) defaults that distinguish real repos from a standalone-repo folder.
 */
class FolderScannerTest {

    private val root: Path = Files.createTempDirectory("profiles-scan")

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun file(parent: Path, name: String): Path =
        Files.write(parent.resolve(name), byteArrayOf())

    @Test
    fun `top-level files are ignored and each org folder becomes a profile`() {
        file(root, "AGENTS.md")
        file(root, ".DS_Store")
        // org "ArcaneArts" with two real git repos and a dot tooling folder
        Files.createDirectories(root.resolve("ArcaneArts/arcane_jaspr/.git"))
        Files.createDirectories(root.resolve("ArcaneArts/Intelij-Profiles/.git"))
        Files.createDirectories(root.resolve("ArcaneArts/.codegraph"))
        // standalone repo "Alembic": itself a repo, its children are internal folders (no markers)
        Files.createDirectories(root.resolve("Alembic/.git"))
        Files.createDirectories(root.resolve("Alembic/lib"))
        Files.createDirectories(root.resolve("Alembic/build"))

        val profiles = FolderScanner.scan(root)

        // Sorted case-insensitively: Alembic, ArcaneArts. Files at root produce no profiles.
        assertEquals(listOf("Alembic", "ArcaneArts"), profiles.map { it.name })

        val arcane = profiles.first { it.name == "ArcaneArts" }
        // Both repos detected and recommended; the .codegraph dot-folder is not listed.
        assertEquals(setOf("arcane_jaspr", "Intelij-Profiles"), arcane.projects.map { it.name }.toSet())
        assertTrue(arcane.projects.all { it.recommended })
        assertTrue(arcane.recommended)

        val alembic = profiles.first { it.name == "Alembic" }
        // .git is a dot-folder (skipped); lib/build are listed but not recommended.
        assertEquals(setOf("build", "lib"), alembic.projects.map { it.name }.toSet())
        assertTrue(alembic.projects.none { it.recommended })
        assertFalse(alembic.recommended)
    }

    @Test
    fun `non-directory root yields nothing`() {
        val f = Files.write(root.resolve("plain.txt"), byteArrayOf())
        assertTrue(FolderScanner.scan(f).isEmpty())
    }

    @Test
    fun `build markers other than git mark a project as recommended`() {
        Files.createDirectories(root.resolve("Group/gradleApp"))
        Files.write(root.resolve("Group/gradleApp/build.gradle.kts"), byteArrayOf())
        Files.createDirectories(root.resolve("Group/justAFolder"))

        val group = FolderScanner.scan(root).first { it.name == "Group" }
        assertTrue(group.projects.first { it.name == "gradleApp" }.recommended)
        assertFalse(group.projects.first { it.name == "justAFolder" }.recommended)
    }
}
