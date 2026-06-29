package art.arcane.profiles.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the heart of a profile switch: given the target projects and what's currently open, decide
 * what to open, what to close, and whether we're done. Keys stand in for canonical project paths.
 */
class ReconcilePlannerTest {

    private fun plan(targets: List<String>, open: Set<String>) = ReconcilePlanner.plan(targets, open)

    @Test
    fun `converged when the open set already equals the targets`() {
        val p = plan(listOf("/a", "/b"), setOf("/a", "/b"))
        assertTrue(p.converged)
        assertTrue(p.toOpenKeys.isEmpty())
        assertTrue(p.toCloseKeys.isEmpty())
    }

    @Test
    fun `switching from many to one closes the many and opens the one`() {
        val p = plan(listOf("/x"), setOf("/a", "/b", "/c"))
        assertEquals(listOf("/x"), p.toOpenKeys)
        assertEquals(setOf("/a", "/b", "/c"), p.toCloseKeys.toSet())
        assertFalse(p.converged)
    }

    @Test
    fun `switching from one to many keeps the shared one and opens the rest`() {
        val p = plan(listOf("/a", "/b", "/c"), setOf("/a"))
        assertEquals(listOf("/b", "/c"), p.toOpenKeys) // primary /a already open
        assertTrue(p.toCloseKeys.isEmpty())
    }

    @Test
    fun `a project shared by both profiles is neither opened nor closed`() {
        // open {/a,/b}, switch to {/b,/c}: /b is shared and must stay untouched.
        val p = plan(listOf("/b", "/c"), setOf("/a", "/b"))
        assertEquals(listOf("/c"), p.toOpenKeys)
        assertEquals(listOf("/a"), p.toCloseKeys)
    }

    @Test
    fun `an already-open target is never reopened (no duplicate window)`() {
        val p = plan(listOf("/a", "/b"), setOf("/a", "/b"))
        assertTrue(p.toOpenKeys.isEmpty())
    }

    @Test
    fun `from the welcome screen with nothing open, all targets open, primary first`() {
        val p = plan(listOf("/a", "/b"), emptySet())
        assertEquals(listOf("/a", "/b"), p.toOpenKeys)
        assertTrue(p.toCloseKeys.isEmpty())
        assertFalse(p.converged)
    }

    @Test
    fun `toOpen preserves target order with the primary first`() {
        val p = plan(listOf("/c", "/a", "/b"), setOf("/a"))
        assertEquals(listOf("/c", "/b"), p.toOpenKeys)
    }

    @Test
    fun `a completely different profile closes all and opens all`() {
        val p = plan(listOf("/x", "/y"), setOf("/a", "/b"))
        assertEquals(listOf("/x", "/y"), p.toOpenKeys)
        assertEquals(setOf("/a", "/b"), p.toCloseKeys.toSet())
    }

    @Test
    fun `empty targets are rejected (the engine handles empty profiles before planning)`() {
        assertThrows(IllegalArgumentException::class.java) { plan(emptyList(), setOf("/a")) }
    }
}
