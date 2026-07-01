package art.arcane.profiles.engine

/** User-visible lifecycle state for profile switching. */
sealed interface SwitchStatus {
    data class Idle(val activeProfileName: String?) : SwitchStatus

    data class Switching(
        val targetProfileName: String,
        val openedCount: Int,
        val targetCount: Int,
        val closingCount: Int,
    ) : SwitchStatus

    data class Failed(
        val targetProfileName: String,
        val missingCount: Int,
        val extraCount: Int,
    ) : SwitchStatus
}
