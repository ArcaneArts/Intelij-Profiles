package art.arcane.profiles.engine

/**
 * The pure decision for a reconcile pass: given the profile's target project keys (canonical
 * real-paths) and the keys of the currently-open projects, decide what to open, what to close, and
 * whether the open set already matches the profile.
 *
 * No platform dependencies, so the heart of "what a switch does" is exhaustively unit-tested.
 */
internal data class ReconcilePlan(
    /** Target keys not open yet, in target order (the first target is the primary). */
    val toOpenKeys: List<String>,
    /** Open keys that aren't in the profile (extras to close). */
    val toCloseKeys: List<String>,
    /** True when the open set already equals the target set — nothing to do but focus. */
    val converged: Boolean,
)

internal object ReconcilePlanner {

    fun plan(targetKeys: List<String>, openKeys: Set<String>): ReconcilePlan {
        require(targetKeys.isNotEmpty()) { "a profile switch needs at least one target" }
        val targetSet = targetKeys.toSet()
        val toOpen = targetKeys.filter { it !in openKeys }
        val toClose = openKeys.filter { it !in targetSet }
        return ReconcilePlan(
            toOpenKeys = toOpen,
            toCloseKeys = toClose,
            converged = toOpen.isEmpty() && toClose.isEmpty(),
        )
    }
}
