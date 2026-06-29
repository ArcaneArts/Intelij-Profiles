package art.arcane.profiles.ui

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Curated set of built-in icons a profile can use. The profile stores the [key]; if a profile's
 * icon string isn't one of these keys it's treated as a typed emoji/character instead.
 */
enum class ProfileIcon(val key: String, val display: String, val icon: Icon) {
    USER("user", "User", AllIcons.General.User),
    FOLDER("folder", "Folder", AllIcons.Nodes.Folder),
    MODULE("module", "Module", AllIcons.Nodes.Module),
    STAR("star", "Star", AllIcons.Nodes.Favorite),
    SETTINGS("settings", "Settings", AllIcons.General.Settings),
    RUN("run", "Run", AllIcons.Actions.Execute),
    BRANCH("branch", "Branch", AllIcons.Vcs.Branch),
    PLUS("plus", "Plus", AllIcons.General.Add),
    ;

    companion object {
        fun byKey(key: String?): ProfileIcon? =
            key?.let { k -> entries.firstOrNull { it.key == k } }
    }
}
