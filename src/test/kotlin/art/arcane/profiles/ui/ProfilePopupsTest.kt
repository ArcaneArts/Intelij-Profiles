package art.arcane.profiles.ui

import art.arcane.profiles.model.Profile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePopupsTest {

    @Test
    fun `row label shows switching instead of active for the target profile`() {
        val label = ProfilePopups.rowLabel(
            Profile("Work", null, null, listOf("/a", "/b")),
            active = true,
            switching = true,
        )

        assertTrue(label.contains("Work (switching)"))
        assertFalse(label.contains("(active)"))
        assertTrue(label.contains("2 projects"))
    }
}
