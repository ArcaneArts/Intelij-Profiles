package art.arcane.profiles.model

/**
 * Immutable read model for a profile: a named set of real IntelliJ project paths.
 * The persisted form lives in [art.arcane.profiles.ProfilesService.ProfileEntry];
 * UI and switching logic consume this clean model instead.
 */
data class Profile(
    val name: String,
    val color: String?,
    val projectPaths: List<String>,
)
