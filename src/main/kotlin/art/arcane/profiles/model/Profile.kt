package art.arcane.profiles.model

/**
 * Immutable read model for a profile: a named set of real IntelliJ project paths, with an optional
 * color and icon for the toolbar.
 *
 * [icon] is either the key of a built-in icon (see ProfileIcon) or a short typed string such as an
 * emoji; [color] is a hex string. Both are optional.
 */
data class Profile(
    val name: String,
    val color: String?,
    val icon: String?,
    val projectPaths: List<String>,
)
