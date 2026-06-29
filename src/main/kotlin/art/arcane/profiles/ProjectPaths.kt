package art.arcane.profiles

import java.nio.file.Paths

/**
 * Pure helpers for normalizing and de-duplicating the project paths STORED in a profile. No
 * IntelliJ-platform dependencies, so this is unit-tested directly under plain JUnit. (Live-window
 * identity is decided separately, by real-path [art.arcane.profiles.engine.canonicalKey], in the engine.)
 */
object ProjectPaths {

    /** Absolute, separator-normalized form with any trailing separator stripped. */
    fun normalize(path: String): String =
        try {
            Paths.get(path).toAbsolutePath().normalize().toString().trimEnd('/', '\\')
        } catch (e: Exception) {
            path.trim().trimEnd('/', '\\')
        }

    fun sameProject(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /** Normalize a collection, dropping duplicates while preserving first-seen order. */
    fun normalizeAll(paths: Collection<String>): List<String> = paths.map(::normalize).distinct()
}
