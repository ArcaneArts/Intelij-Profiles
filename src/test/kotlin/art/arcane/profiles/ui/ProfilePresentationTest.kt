package art.arcane.profiles.ui

import art.arcane.profiles.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePresentationTest {

    private fun profile(name: String, icon: String? = null) =
        Profile(name = name, color = null, icon = icon, projectPaths = emptyList())

    @Test
    fun shortNameIsReturnedUnchanged() {
        assertEquals("Work", ProfilePresentation.toolbarLabel(profile("Work")))
    }

    @Test
    fun longNameIsEllipsizedToMaxChars() {
        val label = ProfilePresentation.toolbarLabel(profile("A really really long profile name"), maxChars = 10)
        assertTrue("label='$label'", label.length <= 10)
        assertTrue("label='$label'", label.endsWith("…"))
    }

    @Test
    fun emojiProfileGetsSingleGraphemePrefix() {
        // A ZWJ family emoji is several code points but ONE grapheme; only that grapheme should prefix.
        val label = ProfilePresentation.toolbarLabel(profile("Home", icon = "👨‍👩‍👧 extra"))
        assertTrue("label='$label'", label.endsWith("Home"))
        assertTrue("label='$label'", label.startsWith("👨‍👩‍👧 "))
    }

    @Test
    fun builtInIconKeyAddsNoEmojiPrefix() {
        // "star" is a built-in ProfileIcon key, so emoji() returns null -> no prefix.
        assertEquals("Fav", ProfilePresentation.toolbarLabel(profile("Fav", icon = "star")))
    }

    @Test
    fun toolbarIconIsNullForEmojiProfile() {
        assertNull(ProfilePresentation.toolbarIcon(profile("Home", icon = "🏠")))
    }
}
