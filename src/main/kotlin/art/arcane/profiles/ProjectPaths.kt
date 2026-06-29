package art.arcane.profiles

import java.nio.file.Paths

/**
 * Pure helpers for comparing project locations. No IntelliJ-platform dependencies, so this is
 * unit-tested directly under plain JUnit. Profile membership and "is the current window set this
 * profile?" detection both rely on comparing normalized paths.
 */
object ProjectPaths {

    /** Absolute, separator-normalized form with any trailing separator stripped. */
    fun normalize(path: String): String =
        try {
            Paths.get(path).toAbsolutePath().normalize().toString().trimEnd('/')
        } catch (e: Exception) {
            path.trim().trimEnd('/')
        }

    fun sameProject(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /** Normalize a collection, dropping duplicates while preserving first-seen order. */
    fun normalizeAll(paths: Collection<String>): List<String> = paths.map(::normalize).distinct()

    /** True when the two collections refer to exactly the same set of projects (order-insensitive). */
    fun sameSet(a: Collection<String>, b: Collection<String>): Boolean =
        normalizeAll(a).toSet() == normalizeAll(b).toSet()
}
