package art.arcane.profiles.io

import art.arcane.profiles.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Export/import must round-trip exactly, including tricky names, nulls, and empty path sets. */
class ProfilesJsonTest {

    @Test
    fun `round-trips profiles with colors, icons, and multiple paths`() {
        val profiles = listOf(
            Profile("Work", "3B82F6", "star", listOf("/work/a", "/work/b")),
            Profile("Home", null, null, listOf("/home/x")),
        )
        assertEquals(profiles, ProfilesJson.decode(ProfilesJson.encode(profiles)))
    }

    @Test
    fun `round-trips tricky names, emoji icons, and empty path lists`() {
        val profiles = listOf(
            Profile("Client \"Acme\" \\ R&D", "FF0000", "🚀", emptyList()),
            Profile("emoji name 🎉", null, "home", listOf("C:\\repos\\win")),
        )
        assertEquals(profiles, ProfilesJson.decode(ProfilesJson.encode(profiles)))
    }

    @Test
    fun `decodes an empty array to no profiles`() {
        assertTrue(ProfilesJson.decode("[]").isEmpty())
    }

    @Test
    fun `tolerates objects missing optional fields`() {
        val decoded = ProfilesJson.decode("""[{"name":"Bare"}]""")
        assertEquals(listOf(Profile("Bare", null, null, emptyList())), decoded)
    }
}
