package art.arcane.profiles

import art.arcane.profiles.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the CRUD operations. ProfilesService is constructed directly (no platform
 * application needed) because the operations only mutate in-memory [BaseState]. XML round-trip
 * persistence is covered by the manual runIde restart check in the plan's verification section.
 */
class ProfilesServiceTest {

    private fun service() = ProfilesService()

    @Test
    fun `addProfile stores profile with normalized deduped paths`() {
        val svc = service()
        val p = svc.addProfile("Work", listOf("/work/a", "/work/a/", "/work/b"))
        assertEquals("Work", p.name)
        assertEquals(listOf("/work/a", "/work/b"), p.projectPaths)
        assertTrue(svc.hasProfile("Work"))
    }

    @Test
    fun `addProfile rejects duplicate names`() {
        val svc = service()
        svc.addProfile("Home")
        assertThrows(IllegalArgumentException::class.java) { svc.addProfile("Home") }
    }

    @Test
    fun `addProfile rejects blank name`() {
        val svc = service()
        assertThrows(IllegalArgumentException::class.java) { svc.addProfile("   ") }
    }

    @Test
    fun `replaceAll swaps the full set and prunes a stale active pointer`() {
        val svc = service()
        svc.addProfile("Old")
        svc.activeProfileName = "Old"
        svc.replaceAll(listOf(Profile("New", null, null, listOf("/x"))))
        assertFalse(svc.hasProfile("Old"))
        assertTrue(svc.hasProfile("New"))
        assertNull(svc.activeProfileName)
    }

    @Test
    fun `replaceAll keeps the active pointer when the profile survives`() {
        val svc = service()
        svc.addProfile("Keep")
        svc.activeProfileName = "Keep"
        svc.replaceAll(listOf(Profile("Keep", null, null, listOf("/y"))))
        assertEquals("Keep", svc.activeProfileName)
    }

    @Test
    fun `addProfile stores color and icon`() {
        val svc = service()
        svc.addProfile("Work", listOf("/w"), color = "3B82F6", icon = "star")
        val p = svc.profiles.first()
        assertEquals("3B82F6", p.color)
        assertEquals("star", p.icon)
    }

    @Test
    fun `replaceAll preserves color and icon`() {
        val svc = service()
        svc.replaceAll(listOf(Profile("A", "FF0000", "home", listOf("/a"))))
        val p = svc.profiles.first()
        assertEquals("FF0000", p.color)
        assertEquals("home", p.icon)
    }
}
