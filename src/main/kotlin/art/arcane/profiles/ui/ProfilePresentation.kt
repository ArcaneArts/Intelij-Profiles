package art.arcane.profiles.ui

import art.arcane.profiles.model.Profile
import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.ColorIcon
import java.awt.Color
import javax.swing.Icon

/** Turns a profile's optional color/icon into what the toolbar and dropdown actually render. */
object ProfilePresentation {

    /** Max characters shown in the main-toolbar combo label before ellipsis (keeps the widget narrow). */
    const val TOOLBAR_MAX_LABEL_CHARS: Int = 22

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

    /**
     * The main-toolbar text for [profile]: an optional single-grapheme emoji prefix (never the full,
     * possibly-long user string) plus the profile name, the whole thing ellipsized to [maxChars] so a
     * long name or a multi-glyph emoji can never blow out the toolbar width and crowd the neighbouring
     * Project/Branch widgets. Built-in icons contribute no prefix (they are shown via [toolbarIcon]).
     */
    fun toolbarLabel(profile: Profile, maxChars: Int = TOOLBAR_MAX_LABEL_CHARS): String {
        val prefix = firstGrapheme(emoji(profile))?.let { "$it " } ?: ""
        val name = profile.name.trim()
        val budget = (maxChars - prefix.length).coerceAtLeast(1)
        val shownName =
            if (name.length <= budget) name
            else name.take((budget - 1).coerceAtLeast(1)).trimEnd() + "…"
        return prefix + shownName
    }

    /** The toolbar icon: none when an emoji is shown in the label, otherwise the profile's [icon]. */
    fun toolbarIcon(profile: Profile): Icon? =
        if (emoji(profile) != null) null else icon(profile)

    /** The first user-perceived character (grapheme cluster) of [s], or null if blank. */
    fun firstGrapheme(s: String?): String? {
        val text = s?.trim().orEmpty()
        if (text.isEmpty()) return null
        val breaker = java.text.BreakIterator.getCharacterInstance()
        breaker.setText(text)
        val end = breaker.next()
        return if (end == java.text.BreakIterator.DONE) text else text.substring(0, end)
    }
}
