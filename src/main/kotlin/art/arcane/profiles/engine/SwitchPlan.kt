package art.arcane.profiles.engine

import java.nio.file.Path

/**
 * Canonical real-path key. Resolves symlinks, macOS `/private` aliasing, case-insensitive APFS, and
 * trailing slashes so that two spellings of the same folder collapse to one key. This is the single
 * identity used to decide "is this folder already open?" and "is this open project an extra?" —
 * having one identity function is what prevents duplicate windows.
 */
internal fun canonicalKey(path: Path): String =
    try {
        path.toRealPath().toString()
    } catch (e: Exception) {
        // Folder may not exist (deleted on disk) — fall back to a normalized absolute spelling.
        path.toAbsolutePath().normalize().toString().trimEnd('/')
    }

/** Result of resolving a profile's raw stored paths against the filesystem. */
internal data class ResolvedTarget(
    /** Existing project directories, de-duplicated by canonical key, original order preserved. */
    val targets: List<Path>,
    /** Raw paths that don't exist on disk (reported to the user, never opened). */
    val missing: List<String>,
)

/** Pure path planning, unit-tested without a running IDE via an injected [exists] predicate. */
internal object TargetResolver {

    fun resolve(rawPaths: List<String>, exists: (Path) -> Boolean): ResolvedTarget {
        val targets = ArrayList<Path>()
        val missing = ArrayList<String>()
        val seen = HashSet<String>()
        for (raw in rawPaths) {
            val nio = runCatching { Path.of(raw) }.getOrNull()
            if (nio == null || !exists(nio)) {
                missing.add(raw)
                continue
            }
            if (seen.add(canonicalKey(nio))) {
                targets.add(nio)
            }
        }
        return ResolvedTarget(targets, missing)
    }
}
