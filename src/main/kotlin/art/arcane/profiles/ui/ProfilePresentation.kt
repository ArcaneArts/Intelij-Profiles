package art.arcane.profiles.ui

import art.arcane.profiles.model.Profile
import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.ColorIcon
import java.awt.Color
import javax.swing.Icon

/** Turns a profile's optional color/icon into what the toolbar and dropdown actually render. */
object ProfilePresentation {

    /** The typed emoji/character, when the profile's icon isn't one of the built-in keys. */
    fun emoji(profile: Profile): String? {
        val raw = profile.icon?.trim().orEmpty()
        if (raw.isEmpty() || ProfileIcon.byKey(raw) != null) return null
        return raw
    }

    /** Parse a stored hex color string (with or without `#`), or null. */
    fun colorFromHex(hex: String?): Color? =
        hex?.takeIf { it.isNotBlank() }?.let { runCatching { ColorUtil.fromHex(it) }.getOrNull() }

    /** Swing icon: the chosen built-in icon, else a color dot, else a neutral default. */
    fun icon(profile: Profile): Icon {
        ProfileIcon.byKey(profile.icon)?.let { return it.icon }
        colorFromHex(profile.color)?.let { return ColorIcon(12, it) }
        return AllIcons.General.User
    }

    /** Label text, prefixed with the emoji when one is set. */
    fun label(profile: Profile): String {
        val e = emoji(profile)
        return if (e != null) "$e ${profile.name}" else profile.name
    }
}
